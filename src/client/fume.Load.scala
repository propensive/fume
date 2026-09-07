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
┃    Fume, version 0.2.0.                                                                          ┃
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

import java.lang as jl
import java.lang.management as jlm
import java.util.concurrent.atomic as juca

import soundness.*

import fume.Figures.measurable

// The load gate: `--max-load` holds a run back until the machine is quiet enough to measure
// on. Benchmarks and stress tests are the reason it exists — a timing taken while a compile
// is still finishing is not a timing of anything — but the gate is deliberately indifferent
// to the selection: asking for it is what turns it on.
//
// The system load average is a UNIX notion of "how many processes wanted to run", averaged
// over the last minute, so it is SLOW: it decays with a 60-second time constant, and a
// machine that has just gone idle still reports the work it was doing. That is exactly the
// property wanted here — the gate waits out the tail of the previous job — but it also means
// the bar moves in slow, smooth steps rather than tracking the instantaneous CPU load.
object Load:
  // The scale is logarithmic because load averages are: the difference between 0.5 and 1.0
  // matters as much as the difference between 8 and 16, and a linear bar sized for a busy
  // 16-core machine would render every interesting value in its first two cells.
  private def log2(value: Double): Double = jl.Math.log(value)/jl.Math.log(2.0)
  private def exp2(value: Double): Double = jl.Math.pow(2.0, value)

  private def cores: Int = jl.Runtime.getRuntime.nn.availableProcessors

  // The 1-minute load average, or `Unset` where the platform does not keep one:
  // `getSystemLoadAverage` answers a negative number then, as it always does on Windows.
  def average: Optional[Double] =
    val value: Double = jlm.ManagementFactory.getOperatingSystemMXBean.nn.getSystemLoadAverage
    if value < 0.0 then Unset else value

  // Two decimal places, without `Figures.scaled`'s unit machinery: a load average is a bare
  // number, never scaled or suffixed.
  def show(load: Double): Text =
    val hundredths: Long = (load*100.0).toLong
    t"${hundredths/100}.${(hundredths%100).show.pad(2, Rtl, '0')}"

  // The bar's horizontal mapping: `lower` and `upper` are the loads at its two ends, both
  // powers of two, and `span` is its width in cells. The range starts an octave below the
  // target — so the target itself is never flush against the left edge, and a machine that
  // is already quiet still shows a stub of bar — and ends at the smallest power of two at or
  // above twice the core count, which is a thoroughly overloaded machine and therefore a
  // sensible full scale.
  case class Scale(lower: Double, upper: Double, span: Int):
    private def octaves: Double = log2(upper) - log2(lower)

    // Where `load` falls, as a fraction of the bar. Zero (an idle machine) has no logarithm,
    // and anything at or below `lower` is off the left end; both clamp to the left edge.
    def position(load: Double): Double =
      if load <= 0.0 then 0.0 else ((log2(load) - log2(lower))/octaves).max(0.0).min(1.0)

    def eighths(load: Double): Long = (position(load)*span*8.0).toLong
    def column(load: Double): Int = (position(load)*span).toInt

  object Scale:
    def apply(target: Double, span: Int): Scale =
      val lower: Double = exp2(log2(target.max(0.0625)).floor - 1.0)
      val upper: Double = exp2(log2((cores*2).toDouble.max(target*4.0)).ceiling)
      Scale(lower, upper.max(lower*2.0), span)

  private val subscripts: List[Text] =
    List(t"₀", t"₁", t"₂", t"₃", t"₄", t"₅", t"₆", t"₇", t"₈", t"₉")

  // `₄`, `₁₆`: the value in Unicode subscript digits.
  private def subscript(value: Int): Text =
    if value < 10 then subscripts.stdlib(value)
    else t"${subscript(value/10)}${subscripts.stdlib(value%10)}"

  // The ruler above the bar: a `╷` at each power of two the scale covers, labelled with the
  // value in subscript digits, e.g. `╷₄     ╷₈     ╷₁₆`. Powers below 1 are marked but not
  // labelled — there are no subscript decimal points — and a mark whose label would overlap
  // its predecessor's is dropped, so a narrow terminal thins the ruler instead of garbling
  // it.
  //
  // `limit` is the room the ruler has, which is the whole terminal WIDTH rather than the
  // bar's span: the topmost mark sits at the bar's right-hand edge, so its label can only be
  // written into the space the bar's own numeric label occupies on the line below.
  def ruler(scale: Scale, limit: Int): Text =
    def recur(load: Double, column: Int, acc: Text): Text =
      if load > scale.upper then acc else
        val position: Int = scale.column(load)
        val mark: Text = if load < 1.0 then t"╷" else t"╷${subscript(load.toInt)}"

        if position < column || position + mark.length > limit
        then recur(load*2.0, column, acc)
        else recur(load*2.0, position + mark.length, acc + t" "*(position - column) + mark)

    recur(scale.lower, 0, t"")

  // The colour of one cell of the bar: unfilled cells are track, and the filled portion is
  // `pass` up to the target column and `warning` beyond it.
  def colour(eighths: Int, column: Int, boundary: Int): Color in Srgb =
    if eighths == 0 then Palette.track
    else if column < boundary then Palette.pass
    else Palette.warning

  // The character in one cell of the bar: a space where the cell is wholly filled or wholly
  // empty — its background colour says which — and an eighth-block glyph where the boundary
  // between fill and track falls inside the cell.
  def glyph(eighths: Int): Text =
    if eighths == 0 || eighths == 8 then t" " else Figures.partials.stdlib(eighths)

  // The bar itself: eighth-block granularity, and two colours — the portion of the bar below
  // the target load is `pass`, the portion above it `warning`, so an overloaded machine shows
  // exactly how far past the threshold it is, and a quiet one is wholly green.
  def bar(load: Double, target: Double, scale: Scale): Teletype =
    val filled: Long = scale.eighths(load)
    val boundary: Int = scale.column(target)

    // Every cell is painted as a BACKGROUND — a whole cell is a space on its colour, and a
    // fractional one an eighth-block glyph in the fill colour ON the track colour. Drawing
    // the fill as a foreground glyph over the terminal's own background would leave the
    // unfilled fraction of the boundary cell bare, showing as a sliver of black between the
    // fill and the track.
    def cell(column: Int): Teletype =
      val eighths: Int = (filled - column*8L).max(0L).min(8L).toInt
      val ink: Color in Srgb = colour(eighths, column, boundary)

      if eighths == 0 || eighths == 8 then e"${Bg(ink)}(${glyph(eighths)})"
      else e"${Bg(Palette.track)}(${Fg(ink)}(${glyph(eighths)}))"

    def recur(column: Int, acc: Teletype): Teletype =
      if column >= scale.span then acc else recur(column + 1, e"$acc${cell(column)}")

    recur(0, e"")

  // Polls until the load average falls below `target`, painting the ruler once and repainting
  // the bar in place — a `\r` return, never a cursor-position escape, so the line the run's
  // own output will overwrite is the only line touched. Without a terminal (a CI log, a pipe)
  // there is nothing to animate: the wait is announced, and its end reported, in two lines.
  //
  // Polling is every fifth of a second: more often than the kernel recomputes the average
  // (every five seconds or so on Linux and macOS alike), but it costs nothing, it keeps
  // Ctrl+C responsive — the abort flag is only read between polls — and it means the bar
  // redraws the moment the value does.
  //
  // `0.2*Second`, NOT a bare `0.2` or `200L`: `snooze`'s `Long` overload is in NANOSECONDS,
  // so `snooze(200L)` would spin, repainting as fast as the terminal could take it.
  def settle(target: Double, width: Int, tty: Boolean, aborted: juca.AtomicBoolean)
     (using Stdio, Monitor, Environment)
  :   Unit =

    average.lay(Render.announce(t"this platform reports no load average; not waiting")):
      initial =>
        if initial < target
        then Render.announce(t"load average is ${show(initial)}; starting")
        else
          Render.announce(t"waiting for the load average to fall below ${show(target)}")
          // Room to the bar's right for the numeric label, wide enough for a three-digit
          // load average. The width is fixed, and the label right-aligned within it, so the
          // bar does not reflow as the number gains or loses a digit.
          val labelWidth: Int = 8
          val scale: Scale = Scale(target, (width - labelWidth).max(8))
          val stdio: Stdio = summon[Stdio]

          // Ctrl+C during the wait cannot arrive as a SIGNAL: the Ethereal launcher holds the
          // client's terminal in raw mode for the whole invocation, so an interrupt is the
          // byte 0x03 on stdin, and the `trap` in `runClient` never fires. The board's input
          // pump — the usual reader of those bytes — does not exist yet, since it is created
          // with the first suite, so the gate drains stdin itself, through the very
          // `Live.Input` that decides which bytes mean "abort". Keystrokes that mean nothing
          // here are swallowed, which is what would happen to them anyway.
          val input: Live.Input = Live.Input(aborted)

          def drain(): Unit = if stdio.in.available() > 0 then
            input.offer(stdio.in.read())
            drain()

          if tty then
            Out.println(ruler(scale, width))
            // Hide the hardware cursor for the duration: it would otherwise sit at the end of
            // the bar, blinking over the load figure and jumping on every repaint.
            stdio.print(Text("\u001b[?25l"))

          def recur(): Unit =
            average.let: load =>
              if tty then
                val label: Text = show(load).pad(labelWidth, Rtl)
                Out.print(e"\r${bar(load, target, scale)}${Fg(Palette.subdued)}($label)")
                stdio.out.flush()
                drain()

              if load >= target && !aborted.get then
                snooze(0.2*Second)
                recur()

          // The cursor comes back however the wait ends — the threshold met, Ctrl+C, or an
          // exception on the way out.
          try recur() finally if tty then stdio.print(Text("\u001b[?25h"))

          // The bar is left painted, but finished with a newline so the run's first line does
          // not land on top of it.
          if tty then Out.println()

          average.let: load =>
            if aborted.get then Render.announce(t"interrupted while waiting for the load to fall")
            else Render.announce(t"load average is ${show(load)}; starting")
