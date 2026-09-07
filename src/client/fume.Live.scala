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

  // The single owner of the client's stdin. The launcher holds the terminal in RAW mode,
  // so a user's Ctrl+C never becomes a SIGNAL — it arrives as the byte 0x03 (ETX) on this
  // very stream, interleaved with the CSI cursor-position replies the size probe requests.
  // A pump task feeds every byte through `offer`: ETX (and Ctrl+D) set the abort flag;
  // a complete `\e[<rows>;<cols>R` reply parks in `reply` for `probeSize` to collect.
  // A keypress the pump should act on: arrows move the scrolling viewport.
  enum Key:
    case Idle, Up, Down

  final class Input(aborted: juca.AtomicBoolean):
    val reply: juca.AtomicReference[String | Null] = juca.AtomicReference(null)

    @scala.caps.unsafe.untrackedCaptures
    private var pending: String = ""
    @scala.caps.unsafe.untrackedCaptures
    private var collecting: Boolean = false

    def offer(byte: Int): Key =
      if byte == 3 || byte == 4 then
        aborted.set(true)
        Key.Idle
      else if byte == 27 then
        collecting = true
        pending = ""
        Key.Idle
      else if collecting then
        if pending == "[" && byte == 'A'.toInt then
          collecting = false
          Key.Up
        else if pending == "[" && byte == 'B'.toInt then
          collecting = false
          Key.Down
        else if byte == 'R'.toInt then
          reply.set(pending)
          collecting = false
          Key.Idle
        else if pending.length > 15 then
          collecting = false
          Key.Idle
        else
          pending = pending + byte.toChar
          Key.Idle
      else Key.Idle

final class Live(model: Model, initialWidth: Int, winched: juca.AtomicBoolean, input: Live.Input)
   (using stdio: Stdio):

  private val geometry: Live.Geometry = Live.Geometry(initialWidth, 24)

  private given decimalizer: Decimalizer = Decimalizer(4)
  private given style: TableStyle = tableStyles.thickTableStyle

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

  // The probed terminal width, for the final report to replay at once the board has gone.
  def columns: Int = geometry.columns

  // Asks the terminal its size directly: save the cursor, jump to the far corner, request
  // the cursor position (whose reply is thus the terminal's dimensions), restore. The reply
  // arrives on stdin, whose single owner is the `Input` pump; the probe collects it from
  // there. A terminal that never replies (or a pipe) leaves the previous values standing
  // after a short deadline.
  private def probeSize(): Unit =
    input.reply.set(null)
    stdio.print(Text("\u001b7\u001b[4095C\u001b[4095B\u001b[6n\u001b8"))
    stdio.out.flush()

    def deadline(remaining: Int): String | Null =
      val collected = input.reply.get()

      if collected != null || remaining <= 0 then collected else
        jl.Thread.sleep(10L)
        deadline(remaining - 1)

    val reply: String | Null = deadline(50)

    // The reply body is `<rows>;<cols>` (the pump strips the framing); anything else
    // leaves the size unchanged.
    if reply != null then
      val body: String = if reply.startsWith("[") then reply.substring(1).nn else reply

      Text(body).cut(t";") match
        case rows :: cols :: Nil =>
          safely(rows.as[Int]).let { value => geometry.rows = value.max(4) }
          safely(cols.as[Int]).let { value => geometry.columns = value.max(20) }

        case _ =>
          ()

  private case class Row
    ( mark:    Teletype,
      hash:    Text,
      title:   Teletype,
      count:   Text,
      time:    Teletype,
      started: Boolean )

  private def rows(): List[Row] =
    val state = model.state()
    val active: List[Text] = state.active.map(_.id)

    state.lines.map:
      case Model.Line.SuiteLine(ref) =>
        val depth = Documenting.depth(ref)

        Row(e"   ", ref.id, e"${t"  "*depth}${Fg(Palette.foreground)}($Bold(${ref.name}))", t"", e"",
            started = false)

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

        Row(mark, entry.ref.id, e"${t"  "*depth}${entry.ref.name}", count, time,
            started = running || !idle)

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

  private def tabulation(visible: List[Row]): Tabulation[Teletype] =
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

    Scaffold[Row](defs*).tabulate(visible)

  // The current report, exactly the lines `Render` would print, as structured `Teletype`.
  private def renderedLines(): List[Teletype] =
    val buffer = scala.collection.mutable.ArrayBuffer[Teletype]()

    // The progress table earns its place only when the run contains unit tests: their
    // marks, counts and timings live there. A measurement-only run would show an empty
    // shell of names the group tables already carry, so it is skipped.
    val checks: Boolean =
      model.state().lines.exists:
        case Model.Line.EntryLine(entry) => entry.kind.or(t"check") == t"check"
        case _                           => false

    if checks then tabulation(rows()).grid(width).render.each(buffer.append(_))

    val document = Documenting.document(model.state())

    document.groups.each: group =>
      Render.groupLines(group, width, terse = false) { line => buffer.append(line) }

    buffer.to(List)

  // The whole report lives in ONE scrolling viewport: docked to the bottom it tracks new
  // results; scrolled up (the arrow keys) it holds position while content grows, with a
  // proportional scrollbar on the right edge.
  private val scroller: ScrollFixture =
    ScrollFixture()((renderedLines().stdlib): scala.List[Teletype])

  // The whole run's progress, one full-width row pinned beneath the viewport: filled blocks
  // (with an eighth-block partial for smoothness) for the SCHEDULED tests that have finished,
  // a subdued track for the remainder, and a done/total count at the right. An entry counts
  // as done once it has recorded something and is no longer running; with no schedule (a
  // pre-event suite) the bar shows only the track.
  private def progressLine(width: Int): Teletype =
    val state = model.state()

    val entries: List[Model.Entry] =
      state.lines.bind[List[Model.Entry], Model.Entry, List[Model.Entry]]:
        case Model.Line.EntryLine(entry) => List(entry)
        case _                           => Nil

    val active: List[Text] = state.active.map(_.id)
    val total: Int = entries.stdlib.length

    val done: Int =
      entries.stdlib.count: entry =>
        val recorded =
          !entry.completions.nil || !entry.benches.nil || !entry.strains.nil
            || entry.hotspots.present

        recorded && !active.has(entry.ref.id)

    val label: Text = t" $done/$total"
    val span: Int = (width - label.length).max(1)
    val eighths: Long = if total == 0 then 0L else done.toLong*span*8L/total
    val filled: Text = t"█"*(eighths/8L).toInt
    val partial: Text = Figures.partials.stdlib((eighths%8L).toInt)
    val track: Text = t"░"*(span - filled.length - partial.length).max(0)

    e"${Fg(Palette.accented)}($filled$partial)${Fg(Palette.subdued)}($track$label)"

  private def repaint(): Unit = root0.let: root =>
    val bar: Teletype = progressLine(width)

    val pane: Pane =
      stack
        ( scrolling(scroller),
          panel(minHeight = 1, maxHeight = 1):
            val extent = summon[Extent^]
            extent.move(Prim, Prim)
            extent.put(bar) )

    paint(root, pane)
    painted0 = jl.System.currentTimeMillis

  // Moves the viewport; called by the stdin pump on arrow keys — immediate, deliberately
  // bypassing the tick throttle.
  def scroll(delta: Int): Unit = mutex:
    if root0.present then
      scroller.scroll(delta)
      repaint()

  // Called by the one-second timer: begins live painting unless the run already finished.
  // The board takes over the WHOLE terminal via the alternate screen buffer — the primary
  // buffer (and its scrollback) is untouched, and the final report prints there after
  // `finish` switches back.
  def activate(): Unit = mutex:
    if !done0 && root0.absent then
      probeSize()
      stdio.print(Text("\u001b[?1049h\u001b[?25l"))
      stdio.out.flush()
      val geometry0: Live.Geometry = geometry
      val root = new ScreenRoot(() => geometry0.columns, () => geometry0.rows)

      // `flush` re-shows the hardware cursor after every frame unless told otherwise — the
      // board has no caret, so it stays hidden for the whole run (`finish` restores it).
      root.cursor(false)
      root0 = root
      used0 = true
      repaint()

  // Called by the run's own pulse task, every ~200ms while the suite runs: a forwarded
  // SIGWINCH set `winched`, and this beat re-probes the terminal's size and repaints —
  // every table re-tabulates at the new width, and the `ScreenRoot` reframes and fully
  // redraws. (The pulse must be owned by the INVOCATION, not spawned from the activation
  // timer: parasite's structured concurrency ends a task's children with the task, and the
  // timer finishes moments after it fires.)
  def pulse(): Unit = mutex:
    if root0.present && winched.getAndSet(false) then
      probeSize()
      root0.let(_.invalidate())
      repaint()

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
