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

// Numeric formatting shared by fume's renderers: one implementation decides digits and
// units; the renderers decide only colour and styling. A port of probably's `Format`, plus
// the Student's-t quantile table needed to recompute a benchmark's confidence interval from
// the `BenchmarkRecorded` event's raw statistics.
object Figures:
  val metrics = textMetrics.wideCharacterWidthMetric

  given measurable: Char is Measurable:
    def width(char: Char): Int = char match
      case '✓' | '✗' | '⎇' | '↑' | '↓' => 1
      case _                           => metrics.width(char)

  // A scaled quantity: `unit` counts how many divisions by 1000 were applied (0-2 selects
  // µs/ms/s or kB/MB/GB); 3 means the value overflowed the unit sequence and `whole` holds
  // the raw number with no fraction.
  case class Figure(whole: Text, fraction: Text, unit: Int)

  def scaled(n: Long, unit: Int = 0): Figure =
    if unit >= 3 then Figure(n.show, t"", 3)
    else if n > 100000L then scaled(n/1000L, unit + 1)
    else Figure((n/1000L).show, (n%1000L).show.pad(3, Rtl, '0'), unit)

  val timeUnits: List[Text] = List(t"µs", t"ms", t"s ")
  val memoryUnits: List[Text] = List(t"kB", t"MB", t"GB")

  // Basis points of `part` within `whole`, for percentages rendered to two decimal places.
  def basisPoints(part: Double, whole: Double): Long =
    if whole == 0.0 then 0L else (part*10000.0/whole).toLong

  def percent(basisPoints: Long): Text =
    t"${basisPoints/100}.${(basisPoints%100).show.pad(2, Rtl, '0')}"

  // The left-aligned eighth-block characters, indexed by the eighths of a cell they fill:
  // index 0 is empty, and a whole cell is `█` rather than an eighth index. Shared by every
  // bar fume draws — the progress line, the histograms and the load gate.
  val partials: List[Text] = List(t"", t"▏", t"▎", t"▍", t"▌", t"▋", t"▊", t"▉")

  // A histogram bar of `samples` scaled against `max` over a 40-cell span: full blocks
  // with a final fractional character from the eighth-block sequence. Any nonzero count
  // shows at least the thinnest bar.
  def bar(samples: Long, max: Long): Text =
    val eighths = (if max == 0L then 0L else samples*320L/max).max(if samples > 0L then 1L else 0L)
    t"█"*(eighths/8L).toInt + partials.stdlib((eighths%8L).toInt)

  val sparkBlocks: List[Text] = List(t"▁", t"▂", t"▃", t"▄", t"▅", t"▆", t"▇", t"█")

  def abbreviate(text: Text, max: Int = 800): Text =
    if text.length <= max then text else t"${text.keep(max)}…"

  // The two-sided Student's-t quantile for the percentiles Sedentary reports, banded by
  // degrees of freedom exactly as `Benchmark.tQuantile` upstream: the confidence interval a
  // `BenchmarkRecorded` event implies is `tQuantile(confidence, runs - 1)*sd/√runs`.
  def tQuantile(percentile: Int, df: Int): Double =
    val band: Int =
      if df <= 4 then 0 else if df <= 9 then 1 else if df <= 19 then 2
      else if df <= 29 then 3 else 4

    val table: List[List[Double]] =
      List
        ( List(0.941, 1.190, 1.533, 2.132, 2.333, 2.601, 2.999, 3.747),
          List(0.883, 1.100, 1.383, 1.833, 1.973, 2.167, 2.398, 2.821),
          List(0.861, 1.066, 1.328, 1.729, 1.850, 2.012, 2.205, 2.539),
          List(0.854, 1.055, 1.311, 1.699, 1.815, 1.967, 2.150, 2.462),
          List(0.842, 1.036, 1.282, 1.645, 1.751, 1.881, 2.054, 2.326) )

    val index: Int = percentile match
      case 80 => 0
      case 85 => 1
      case 90 => 2
      case 95 => 3
      case 96 => 4
      case 97 => 5
      case 98 => 6
      case _  => 7

    table.stdlib(band).stdlib(index)
