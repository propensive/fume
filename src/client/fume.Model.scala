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

// The accumulated state of one suite's event stream: a flat, insertion-ordered fold of
// `TestEvent`s, from which `Documenting` derives the report document and the live board
// derives its filling-in table. The tree structure of the original report is not rebuilt —
// each `Ref` carries its full suite path, so depth and grouping are derivable — and every
// snapshot (`state()`) is immutable, so renderers never race the consuming thread.
object Model:
  // One line of the results display, in declaration order: suites interleave with the tests
  // beneath them exactly as the (depth-first) run visits them.
  enum Line:
    case SuiteLine(ref: TestEvent.Ref)
    case EntryLine(entry: Entry)

  // One named test's accumulated results: unit-test outcomes per coordinate, and the
  // measurement records of the other kinds, in arrival order.
  case class Entry
    ( ref:         TestEvent.Ref,
      kind:        Optional[Text],
      completions: List[(List[TestEvent.Coordinate], TestEvent.Outcome)],
      benches:     List[TestEvent.BenchmarkRecorded],
      strains:     List[TestEvent.StrainRecorded],
      hotspots:    Optional[TestEvent.HotspotsRecorded],
      anchor:      Optional[TestEvent.AnchorRecorded] )

  case class State
    ( lines:          List[Line],
      details:        List[(TestEvent.Ref, List[TestEvent])],
      active:         List[TestEvent.Ref],
      fatal:          Optional[(TestEvent.Trace, List[TestEvent.Ref])],
      completed:      Optional[Boolean],
      nothingMatched: Boolean )

final class Model:
  import Model.{Entry, Line, State}

  private val mutex: Mutex = Mutex()

  // Reversed insertion order; `state()` reverses. A test's line is added on first sight
  // (usually `TestStarted`), a suite's on `SuiteStarted`.
  @scala.caps.unsafe.untrackedCaptures
  private var lines0: List[Text] = Nil
  @scala.caps.unsafe.untrackedCaptures
  private var suites0: Ledger[Text, TestEvent.Ref] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var entries0: Ledger[Text, Entry] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var details0: Ledger[Text, (TestEvent.Ref, List[TestEvent])] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var active0: List[TestEvent.Ref] = Nil
  @scala.caps.unsafe.untrackedCaptures
  private var fatal0: Optional[(TestEvent.Trace, List[TestEvent.Ref])] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var completed0: Optional[Boolean] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var nothing0: Boolean = false

  private def entry(ref: TestEvent.Ref, kind: Optional[Text]): Entry =
    entries0(ref.id).or:
      val entry = Entry(ref, kind, Nil, Nil, Nil, Unset, Unset)
      entries0 = entries0.define(ref.id, entry)
      lines0 = ref.id :: lines0
      entry

  private def update(ref: TestEvent.Ref, kind: Optional[Text])(lambda: Entry => Entry): Unit =
    val entry0 = entry(ref, kind)
    val entry2 = lambda(if entry0.kind.absent then entry0.copy(kind = kind) else entry0)
    entries0 = entries0.define(ref.id, entry2)

  private def detail(ref: TestEvent.Ref, event: TestEvent): Unit =
    val (_, existing) = details0(ref.id).or((ref, Nil))
    details0 = details0.define(ref.id, (ref, event :: existing))

  def handle(event: TestEvent): Unit = mutex:
    event match
      case TestEvent.SuiteStarted(ref, _) =>
        if suites0(ref.id).absent then
          suites0 = suites0.define(ref.id, ref)
          lines0 = ref.id :: lines0
        active0 = ref :: active0

      case TestEvent.SuiteEnded(ref, _) =>
        active0 = active0.filter(_.id != ref.id)

      case TestEvent.TestStarted(ref, _) =>
        entry(ref, Unset)
        active0 = ref :: active0

      case TestEvent.TestEnded(ref, _) =>
        active0 = active0.filter(_.id != ref.id)

      case TestEvent.TestCompleted(ref, kind, coordinates, outcome, _, _) =>
        update(ref, kind): entry =>
          entry.copy(completions = (coordinates, outcome) :: entry.completions)

      case event@TestEvent.BenchmarkRecorded(ref, _, _, _, _, _, _, _, _, _, _, _, _) =>
        update(ref, t"bench") { entry => entry.copy(benches = event :: entry.benches) }

      case event@TestEvent.StrainRecorded(ref, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
        update(ref, t"stress") { entry => entry.copy(strains = event :: entry.strains) }

      case event@TestEvent.HotspotsRecorded(ref, _, _, _, _) =>
        update(ref, t"profile") { entry => entry.copy(hotspots = event) }

      case event@TestEvent.AnchorRecorded(ref, _, _, _, _, _, _, _) =>
        update(ref, Unset) { entry => entry.copy(anchor = event) }

      case event@TestEvent.DetailCaptures(ref, _)       => detail(ref, event)
      case event@TestEvent.DetailCompare(ref, _, _, _)  => detail(ref, event)
      case event@TestEvent.DetailMessage(ref, _)        => detail(ref, event)
      case event@TestEvent.DetailThrows(ref, _, _)      => detail(ref, event)

      case TestEvent.NothingMatched(_) =>
        nothing0 = true

      case TestEvent.RunTerminated(error, active, _) =>
        fatal0 = (error, active)

      case TestEvent.RunCompleted(passed, _) =>
        completed0 = passed

  def finished: Boolean = mutex(completed0.present || fatal0.present)

  def state(): State = mutex:
    val lines: List[Line] =
      lines0.reverse.map: id =>
        suites0(id).lay(Line.EntryLine(entries0(id).option.get)) { ref => Line.SuiteLine(ref) }

    val details: List[(TestEvent.Ref, List[TestEvent])] =
      details0.to[List].map { (pair: (Text, (TestEvent.Ref, List[TestEvent]))) =>
        (pair(1)(0), pair(1)(1).reverse) }

    State
      ( lines.map:
          case Line.EntryLine(entry) =>
            Line.EntryLine:
              entry.copy
                ( completions = entry.completions.reverse,
                  benches = entry.benches.reverse,
                  strains = entry.strains.reverse )
          case line => line,
        details,
        active0.reverse,
        fatal0,
        completed0,
        nothing0 )
