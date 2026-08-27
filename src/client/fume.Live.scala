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
import java.util.concurrent.atomic as juca

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
// The document is collected from `Render` as STRUCTURED `Teletype` lines and written into
// an Ultimatum panel through the grid's styled `put`: styling must survive as data — the
// grid writes plain `Text` literally, so pre-rendered ANSI bytes would appear as garbage.
// Only the last `window` lines show — the newest results matter, and an inline block taller
// than the screen cannot be redrawn in place.
object Live:
  // The terminal's live dimensions, in a little pure holder of its own: the `ScreenRoot`'s
  // size thunks capture THIS value rather than the whole `Live` (whose `Monitor` and
  // `Stdio` captures they must not carry).
  final class Geometry(initialColumns: Int, initialRows: Int):
    @scala.caps.unsafe.untrackedCaptures
    var columns: Int = initialColumns
    @scala.caps.unsafe.untrackedCaptures
    var rows: Int = initialRows

final class Live(model: Model, initialWidth: Int, winched: juca.AtomicBoolean)
   (using stdio: Stdio):

  private val geometry: Live.Geometry = Live.Geometry(initialWidth, 24)

  private given decimalizer: Decimalizer = Decimalizer(4)
  private given style: TableStyle = tableStyles.defaultTableStyle

  private val throttle: Long = 100L

  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var root0: Optional[ScreenRoot] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var done0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var used0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var painted0: Long = 0L

  private def width: Int = geometry.columns
  private def window: Int = (geometry.rows - 1).max(4)

  // The probed terminal width, for the final report to replay at once the board has gone.
  def columns: Int = geometry.columns

  // Asks the terminal its size directly: save the cursor, jump to the far corner, request
  // the cursor position (whose reply is thus the terminal's dimensions), restore. The
  // launcher holds the client's terminal in raw mode, so the reply arrives unbuffered on
  // the invocation's stdin. A terminal that never replies (or a pipe) leaves the previous
  // values standing after a short deadline.
  private def probeSize(): Unit =
    stdio.print(Text("\u001b7\u001b[4095C\u001b[4095B\u001b[6n\u001b8"))
    stdio.out.flush()

    val builder = StringBuilder()

    def deadline(remaining: Int): Unit =
      if remaining > 0 then
        if stdio.in.available() > 0 then
          val byte = stdio.in.read()
          if byte == 'R'.toInt then ()
          else
            if byte > 0 then builder.append(byte.toChar)
            deadline(remaining)
        else
          jl.Thread.sleep(10L)
          deadline(remaining - 1)

    deadline(50)

    // The reply is `\e[<rows>;<cols>R`; anything else leaves the size unchanged.
    val reply: String = builder.toString
    val bracket: Int = reply.lastIndexOf('[')

    if bracket >= 0 then
      Text(reply.substring(bracket + 1).nn).cut(t";") match
        case rows :: cols :: Nil =>
          safely(rows.as[Int]).let { value => geometry.rows = value.max(4) }
          safely(cols.as[Int]).let { value => geometry.columns = value.max(20) }

        case _ =>
          ()

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

        val idle: Boolean =
          entry.completions.nil && entry.benches.nil && entry.strains.nil
            && entry.hotspots.absent

        val mark: Teletype =
          if running then e"${Bg(Palette.accented)}($Bold(${Fg(Palette.black)}( ▶ )))"
          else if idle then e" ${Fg(Palette.subdued)}(·) "
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

  // The current report, exactly the lines `Render` would print, as structured `Teletype`.
  private def renderedLines(): List[Teletype] =
    val buffer = scala.collection.mutable.ArrayBuffer[Teletype]()

    tabulation().grid(width).render.each(buffer.append(_))

    val document = Documenting.document(model.state())

    document.groups.each: group =>
      Render.groupLines(group, width, terse = false) { line => buffer.append(line) }

    val all: List[Teletype] = buffer.to(List)
    val length = all.stdlib.length

    if length <= window then all
    else
      val elided = length - window
      val notice: Teletype = e"${Fg(Palette.subdued)}(… $elided earlier lines)"
      val visible: List[Teletype] = (all.stdlib.drop(elided)).to(List)
      notice :: visible

  private def repaint(): Unit = root0.let: root =>
    val lines: List[Teletype] = renderedLines()

    val pane: Pane =
      stack(panel(minHeight = lines.stdlib.length.max(1)):
        // Written through the `Board` surface directly: the contextual `Extent^` is tracked,
        // so it cannot serve as the pure `Stdio` that `Out` requires under capture checking —
        // and the `Teletype` overload of `put` is what carries the styling into the grid.
        val extent = summon[Extent^]

        lines.indexed.each: (line, index) =>
          extent.move(Prim, index)
          extent.put(line))

    paint(root, pane)
    painted0 = jl.System.currentTimeMillis

  // Called by the one-second timer: begins live painting unless the run already finished.
  // The board takes over the WHOLE terminal via the alternate screen buffer — the primary
  // buffer (and its scrollback) is untouched, and the final report prints there after
  // `finish` switches back.
  def activate()(using Monitor): Unit =
    mutex:
      if !done0 && root0.absent then
        probeSize()
        stdio.print(Text("\u001b[?1049h\u001b[?25l"))
        stdio.out.flush()
        val geometry0: Live.Geometry = geometry
        root0 = new ScreenRoot(() => geometry0.columns, () => geometry0.rows)
        used0 = true
        repaint()

    heartbeat()

  // A background pulse while the board is active: a forwarded SIGWINCH sets `winched`, and
  // the next beat re-probes the terminal's size and repaints — the tables re-tabulate at
  // the new width, and the `ScreenRoot` reframes and fully redraws.
  private def heartbeat()(using Monitor): Unit =
    import abstractables.durationAbstractable
    import probates.cancelProbate

    // Single-owner: the heartbeat reads and writes board state only under the same mutex
    // as every other entry point, which the separation checker cannot see through `async`.
    scala.caps.unsafe.unsafeAssumeSeparate:
      async:
        def loop(): Unit =
          if !mutex(done0) then
            snooze(200L)

            if winched.getAndSet(false) then mutex:
              if root0.present then
                probeSize()
                root0.let(_.invalidate())
                repaint()

            loop()

        loop()
    . unit

  // Called on every event: repaints (diffed, throttled) if the board is active.
  def tick(): Unit = mutex:
    if root0.present && jl.System.currentTimeMillis - painted0 >= throttle then repaint()

  // Ends the board: leaves the alternate screen, restoring the primary buffer for the
  // final report.
  def finish(): Unit = mutex:
    if root0.present then
      stdio.print(Text("\u001b[?1049l\u001b[?25h"))
      stdio.out.flush()

    root0 = Unset
    done0 = true

  // Whether the board ever painted.
  def used: Boolean = mutex(used0)
