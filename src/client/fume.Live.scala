                                                                                                  /*
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                                                                                                  ┃
┃                          ╭────────╮                                                              ┃
┃                          │   ╭────╯                                                              ┃
┃                          │   │                                                                   ┃
┃                          │   ╰──╮╭───╮ ╭───╮╭───╮╌────╮╌────╮╭────────╮                          ┃
┃                          │   ╭──╯│   │ │   ││   ╭─╮   ╭─╮   ││   ╭─╮  │                          ┃
┃                          │   │   │   │ │   ││   │ │   │ │   ││   ╰─╯  │                          ┃
┃                          │   │   │   │ │   ││   │ │   │ │   ││   ╭────╯                          ┃
┃                          │   │   │   ╰─╯   ││   │ │   │ │   ││   ╰────╮                          ┃
┃                          ╰───╯   ╰────╌╰───╯╰───╯ ╰───╯ ╰───╯╰────────╯                          ┃
┃                                                                                                  ┃
┃    Fume, version 0.1.0.                                                                          ┃
┃    © Copyright 2026 Jon Pretty, Propensive OÜ.                                                   ┃
┃                                                                                                  ┃
┃    The primary distribution site is:                                                             ┃
┃                                                                                                  ┃
┃        https://propensive.dev/fume/                                                              ┃
┃                                                                                                  ┃
┃    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file     ┃
┃    except in compliance with the License. You may obtain a copy of the License at                ┃
┃                                                                                                  ┃
┃        https://www.apache.org/licenses/LICENSE-2.0                                               ┃
┃                                                                                                  ┃
┃    Unless required by applicable law or agreed to in writing,  software distributed under the    ┃
┃    License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    ┃
┃    either express or implied. See the License for the specific language governing permissions    ┃
┃    and limitations under the License.                                                            ┃
┃                                                                                                  ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
                                                                                                  */
package fume

import java.io as ji
import java.lang as jl

import soundness.*

import probably.TestEvent

import fume.Figures.measurable
import columnAttenuation.ignoreAttenuation

// The live results board: an Ultimatum-managed inline block holding the CURRENT report —
// the progress table (every declared test, a ▶ mark on the running one) followed by the
// measurement group tables so far, each new benchmark row appearing the moment its
// `BenchmarkRecorded` event arrives — activated only when a suite is still producing after
// one second (a fast run never shows it, and renders statically instead), and finished in
// place when the run completes. Repaints are diffed by the `InlineRoot`, so only changed
// cells are rewritten, and are throttled so an event storm cannot dominate the run.
//
// The document is rendered through `Render` into a CAPTURING `Stdio` and replayed line by
// line into an Ultimatum panel: the panel's `Extent` is itself the nearest `Stdio`, so the
// same renderer draws the live board and the final report. Only the last `window` lines
// show — the newest results matter, and an inline block taller than the screen cannot be
// redrawn in place.
final class Live(model: Model, width: Int)(using stdio: Stdio):
  private given decimalizer: Decimalizer = Decimalizer(4)
  private given style: TableStyle = tableStyles.defaultTableStyle

  private val window: Int = 24
  private val throttle: Long = 100L

  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var root0: Optional[InlineRoot] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var done0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var used0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var painted0: Long = 0L

  private case class Row(mark: Teletype, hash: Text, title: Teletype, count: Text, time: Teletype)

  private def rows(): List[Row] =
    val state = model.state()
    val active: List[Text] = state.active.map(_.id)

    state.lines.map:
      case Model.Line.SuiteLine(ref) =>
        val depth = Documenting.depth(ref)
        Row(e"   ", ref.id, e"${t"  "*depth}${Fg(Palette.foreground)}($Bold(${ref.name}))", t"", e"")

      case Model.Line.EntryLine(entry) =>
        val depth = Documenting.depth(entry.ref)
        val running = active.has(entry.ref.id)

        val mark: Teletype =
          if running then e"${Bg(Palette.accented)}($Bold(${Fg(Palette.black)}( ▶ )))"
          else Documenting.entryStatus(entry).symbol

        val count: Text =
          if entry.completions.nil then t"" else entry.completions.stdlib.length.show

        val time: Teletype =
          entry.benches.prim.lay(averageTime(entry)): bench =>
            timeOf(bench.mean.toLong)

        Row(mark, entry.ref.id, e"${t"  "*depth}${entry.ref.name}", count, time)

  private def averageTime(entry: Model.Entry): Teletype =
    val durations: List[Long] = entry.completions.map { completion => completion(1).duration }

    if durations.nil then e"" else
      timeOf(durations.stdlib.foldLeft(0L)(_ + _)/durations.stdlib.length)

  private def timeOf(n: Long): Teletype =
    val figure = Figures.scaled(n)
    if figure.unit >= 3 then figure.whole.teletype else
      val color = figure.unit match
        case 0 => Palette.cold
        case 1 => Palette.warm
        case _ => Palette.hot

      val unit = Figures.timeUnits.stdlib(figure.unit)
      e"${Fg(Palette.foreground)}(${figure.whole}.${figure.fraction}) ${Fg(color)}($unit)"

  private def tabulation(): Tabulation[Teletype] =
    val defs: List[escritoire.Column[Row, Teletype]] =
      List
        ( escritoire.Column[Row, Teletype, Teletype](e"")(_.mark),

          escritoire.Column[Row, Teletype, Teletype](e"$Bold(Hash)"): row =>
            e"${Fg(Palette.informative)}(${row.hash})",

          escritoire.Column[Row, Teletype, Teletype](e"$Bold(Test)", sizing = Render.Stretch)
             (_.title),

          escritoire.Column[Row, Teletype, Teletype]
             (e"$Bold(Count)", TextAlignment.Right, sizing = Render.Rigid):
            row => row.count.teletype,

          escritoire.Column[Row, Teletype, Teletype]
             (e"$Bold(Time)", TextAlignment.Right, sizing = Render.Rigid)(_.time) )

    Scaffold[Row](defs*).tabulate(rows())

  // The current report, rendered exactly as `Render` would print it, captured as lines.
  private def renderedLines(): List[Text] =
    val buffer = ji.ByteArrayOutputStream()
    val print = ji.PrintStream(buffer, true, "UTF-8")
    val capture: Stdio = Stdio(print, print, null, stdio.termcap)

    locally:
      given Stdio = capture
      tabulation().grid(width).render.each(Out.println(_))
      val document = Documenting.document(model.state())
      document.groups.each(Render.renderGroup(_, width, terse = false))

    print.flush()

    val all: List[Text] = Text(buffer.toString("UTF-8").nn).cut(t"\n")

    // Drop the trailing empty segment a final newline leaves behind.
    val trimmed: List[Text] =
      (all.stdlib.reverse.dropWhile(_ == t"").reverse).to(List)

    val length = trimmed.stdlib.length

    if length <= window then trimmed
    else
      val elided = length - window
      val notice: Text = t"… $elided earlier lines"
      val visible: List[Text] = (trimmed.stdlib.drop(elided)).to(List)
      notice :: visible

  private def repaint(): Unit = root0.let: root =>
    val lines: List[Text] = renderedLines()

    val pane: Pane =
      stack(panel(minHeight = lines.stdlib.length.max(1)):
        // Written through the `Board` surface directly: the contextual `Extent^` is tracked,
        // so it cannot serve as the pure `Stdio` that `Out` requires under capture checking.
        val extent = summon[Extent^]

        lines.indexed.each: (line, index) =>
          extent.move(Prim, index)
          extent.put(line))

    paint(root, pane)
    painted0 = jl.System.currentTimeMillis

  // Called by the one-second timer: begins live painting unless the run already finished.
  def activate(): Unit = mutex:
    if !done0 && root0.absent then
      // The height function is the block's CEILING (reframe clamps the measured height by
      // it), not a fixed size: allow the whole window plus the elision line and borders.
      root0 = new InlineRoot(() => width, () => window + 4)
      used0 = true
      repaint()

  // Called on every event: repaints (diffed, throttled) if the board is active.
  def tick(): Unit = mutex:
    if root0.present && jl.System.currentTimeMillis - painted0 >= throttle then repaint()

  // Ends the board, leaving its last frame in place with the cursor below it.
  def finish(): Unit = mutex:
    root0.let(_.finish())
    root0 = Unset
    done0 = true

  // Whether the board ever painted.
  def used: Boolean = mutex(used0)
