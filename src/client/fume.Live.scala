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

import soundness.*

import probably.TestEvent

import fume.Figures.measurable
import columnAttenuation.ignoreAttenuation

// The live results board: an Ultimatum-managed inline block holding a pre-rendered
// Escritoire table of the running suite's results, filled in as events arrive — activated
// only when a suite is still producing after one second (a fast run never shows it, and
// renders statically instead), and finished in place when the run completes, leaving its
// final frame in the scrollback. Repaints are diffed by the `InlineRoot`, so only changed
// cells are rewritten.
final class Live(model: Model, width: Int)(using stdio: Stdio):
  private given decimalizer: Decimalizer = Decimalizer(4)
  private given style: TableStyle = tableStyles.defaultTableStyle

  // The most rows the board shows: the newest results matter — earlier ones have scrolled
  // past review anyway — and repainting an unbounded table would dominate a large suite.
  private val window: Int = 18

  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var root0: Optional[InlineRoot] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var done0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var used0: Boolean = false

  private case class Row(mark: Teletype, hash: Text, title: Teletype, count: Text, time: Teletype)

  private def rows(): List[Row] =
    val state = model.state()

    val active: List[Text] = state.active.map(_.id)

    val all: List[Row] =
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

    val length = all.stdlib.length

    if length <= window then all
    else
      val elided = length - window
      val notice = Row(e"   ", t"", e"${Fg(Palette.subdued)}(… $elided earlier)", t"", e"")
      val visible: List[Row] = (all.stdlib.drop(elided)).to(List)
      notice :: visible

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

  private val pane: Pane = stack(tabular(tabulation()))

  private def repaint(): Unit = root0.let { root => paint(root, pane) }

  // Called by the one-second timer: begins live painting unless the run already finished.
  def activate(): Unit = mutex:
    if !done0 && root0.absent then
      root0 = new InlineRoot(() => width, () => 0)
      used0 = true
      repaint()

  // Called on every event: repaints (diffed) if the board is active.
  def tick(): Unit = mutex(repaint())

  // Ends the board, leaving its last frame in place with the cursor below it.
  def finish(): Unit = mutex:
    root0.let(_.finish())
    root0 = Unset
    done0 = true

  // Whether the board ever painted: the static renderer skips its own results table when
  // the board's final frame already shows it.
  def used: Boolean = mutex(used0)
