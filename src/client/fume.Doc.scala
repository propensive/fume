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

import soundness.*

import probably.TestEvent

// The renderer-agnostic report document: one structural representation of a suite's results,
// built by `Documenting` from the accumulated event `Model` and rendered by `Render` (in
// colour or tersely) — and, later, by an HTML backend. Data is semantic, never preformatted:
// backends decide colour and glyphs, and `Format` decides digits.
object Doc:
  enum Status:
    case Pass, Fail, Throws, CheckThrows, Mixed, Suite, Bench, Stress, Profile, AspirePass,
      AspireFail

    private val nbsp = ' '

    def symbol: Teletype = this match
      case Pass        => e"${Bg(Palette.pass)}($Bold(${Fg(Palette.black)}( ✓ )))"
      case Fail        => e"${Bg(Palette.fail)}($Bold(${Fg(Palette.black)}( ✗ )))"
      case Throws      => e"${Bg(Palette.warning)}($Bold(${Fg(Palette.black)}( ! )))"
      case CheckThrows => e"${Bg(Palette.critical)}($Bold(${Fg(Palette.black)}( ‼ )))"
      case Mixed       => e"${Bg(Palette.mixed)}($Bold(${Fg(Palette.black)}( ? )))"
      case Suite       => e"   "
      case Bench       => e"${Bg(Palette.benchmark)}($Bold(${Fg(Palette.black)}($nbsp*$nbsp)))"
      case Stress      => e"${Bg(Palette.benchmark)}($Bold(${Fg(Palette.black)}($nbsp≈$nbsp)))"
      case Profile     => e"${Bg(Palette.benchmark)}($Bold(${Fg(Palette.black)}($nbsp%$nbsp)))"
      case AspirePass  => e"${Bg(Palette.aspirePass)}($Bold(${Fg(Palette.black)}( ↑ )))"
      case AspireFail  => e"${Bg(Palette.aspireFail)}($Bold(${Fg(Palette.black)}( ↓ )))"

    def describe: Teletype = this match
      case Pass        => e"Pass"
      case Fail        => e"Fail"
      case Throws      => e"Throws exception"
      case CheckThrows => e"Exception in check"
      case Mixed       => e"Mixed"
      case Suite       => e"Suite"
      case Bench       => e"Benchmark"
      case Stress      => e"Stress"
      case Profile     => e"Profile"
      case AspirePass  => e"Aspire passed"
      case AspireFail  => e"Aspire failed"

    def word: Text = this match
      case Pass        => t"pass"
      case Fail        => t"fail"
      case Throws      => t"throws"
      case CheckThrows => t"check-throws"
      case Mixed       => t"mixed"
      case Suite       => t"suite"
      case Bench       => t"bench"
      case Stress      => t"stress"
      case Profile     => t"profile"
      case AspirePass  => t"aspire-pass"
      case AspireFail  => t"aspire-fail"

    def failed: Boolean = this match
      case Fail | Throws | CheckThrows | Mixed => true
      case _                                   => false

  object Status:
    // The outcome vocabulary of `TestEvent.Outcome`, one word per verdict.
    def of(outcome: Text): Status = outcome match
      case t"pass"         => Pass
      case t"fail"         => Fail
      case t"throws"       => Throws
      case t"check-throws" => CheckThrows
      case t"aspire-pass"  => AspirePass
      case t"aspire-fail"  => AspireFail
      case _               => Mixed

    // The collective status of a set of outcomes: their common status, or `Mixed` when they
    // disagree.
    def collective(statuses: List[Status]): Status = statuses match
      case head :: tail => if tail.all(_ == head) then head else Mixed
      case _            => Mixed

  enum Datum:
    case Blank
    case Gap                                     // a sparse-grid hole: an omitted combination
    case Str(text: Text)
    case Hash(id: Text)
    case Title(name: Text, depth: Int)
    case Mark(status: Status)                    // a glyph in colour output, a word in terse
    case Num(value: Long)
    case Time(nanos: Long)
    case Memory(bytes: Long)
    case Rate(perSecond: Long)                   // operations per second
    case Percent(fraction: Double)
    case Conf(percentile: Int, basisPoints: Long)  // e.g. P95 ±2.10%
    case Ratio(factor: Double)                   // baseline-relative; 1.0 renders as ★
    case Delta(datum: Datum, negative: Boolean)  // arithmetic baseline-relative: signed

  // `stretch` marks the column which absorbs the table's spare width, so that every table
  // spans the full terminal width; exactly one column of each table should set it.
  case class Column(title: Text, numeric: Boolean = false, stretch: Boolean = false)

  // One sequence of a sparkline panel: block levels (1-8) per step, with cells beyond the
  // sustained concurrency flagged for subdual, and the sustained (N, throughput) summary.
  case class Spark
    ( label:     Text,
      cells:     List[Optional[(Int, Boolean)]],
      sustained: Optional[(Long, Long)] )

  enum Block:
    // A table of cells; a biaxial entry renders as a crosstab: its second axis's values
    // become the columns and each cell holds only the headline datum. `highlight` lists the
    // indices of rows to render with the winner's background — set once every scheduled row
    // has its data.
    case Table
      ( title:     Optional[TestEvent.Ref],
        columns:   List[Column],
        rows:      List[List[Datum]],
        highlight: List[Int] = Nil )
    case Sparkline(steps: List[Long], sequence: List[Spark])
    case Histogram(title: Optional[TestEvent.Ref], total: Long, frames: List[TestEvent.Hotspot])

    // Scheduled measurements that have not yet recorded anything: one name line each, so a
    // live view shows what is coming without an empty table crowding out the results.
    case Pending(refs: List[TestEvent.Ref])

  // A group of measurement blocks belonging to one suite, of one kind (`bench`, `stress`,
  // `profile` or axial `check` grids), rendered with a ribbon header — or, while NOTHING in
  // the group has begun evaluating, collapsed to a single pending line, its members elided.
  case class Group
    ( suite:   Optional[TestEvent.Ref],
      kind:    Text,
      blocks:  List[Block],
      pending: Boolean = false )

  // One row of the global results table, aggregating a test's runs across all its cells.
  case class SummaryRow(status: Status, ref: TestEvent.Ref, count: Int, min: Long, max: Long, avg: Long)

  case class Totals(passed: Int, failed: Int, aspirePassed: Int, aspireFailed: Int):
    def total: Int = passed + failed + aspirePassed + aspireFailed
    def pass: Boolean = failed == 0 && total > 0

    def +(other: Totals): Totals =
      Totals
        ( passed + other.passed,
          failed + other.failed,
          aspirePassed + other.aspirePassed,
          aspireFailed + other.aspireFailed )

  object Totals:
    def zero: Totals = Totals(0, 0, 0, 0)

  case class Document
    ( results:        List[SummaryRow],
      totals:         Totals,
      groups:         List[Group],
      failures:       List[(TestEvent.Ref, List[TestEvent])],
      fatal:          Optional[(TestEvent.Trace, List[TestEvent.Ref])],
      nothingMatched: Boolean )
