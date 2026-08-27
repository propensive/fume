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

  // Reversed insertion order; `state()` reverses. A test's line is added on first sight —
  // a `schedule` call from a listing pre-pass, or its first event — and a suite's likewise.
  // Line tokens are `s:`-prefixed suite PATHS (a scheduled suite has no id yet; the path is
  // the stable identity a later `SuiteStarted` merges into) and `e:`-prefixed entry ids.
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

  private def suitePath(path: List[Text]): Text = path.join(t"/")

  private def suiteLine(ref: TestEvent.Ref): Unit =
    val key = suitePath(ref.path)
    if suites0(key).absent then
      suites0 = suites0.define(key, ref)
      lines0 = t"s:$key" :: lines0
    else if ref.file != t"" then
      // A real `SuiteStarted` ref replaces a scheduled placeholder, in place.
      suites0 = suites0.define(key, ref)

  private def entry(ref: TestEvent.Ref, kind: Optional[Text]): Entry =
    entries0(ref.id).or:
      val entry = Entry(ref, kind, Nil, Nil, Nil, Unset, Unset)
      entries0 = entries0.define(ref.id, entry)
      lines0 = t"e:${ref.id}" :: lines0
      entry

  private def update(ref: TestEvent.Ref, kind: Optional[Text])(lambda: Entry => Entry): Unit =
    val entry0 = entry(ref, kind)

    // A real event's ref replaces a scheduled placeholder (real file and line); the kind
    // fills in if the placeholder had none.
    val entry1 =
      entry0.copy
        ( ref = if ref.file != t"" || entry0.ref.file == t"" then ref else entry0.ref,
          kind = if entry0.kind.absent then kind else entry0.kind )

    entries0 = entries0.define(ref.id, lambda(entry1))

  // Seeds one line of the run's SCHEDULE from a `TestScheduled` event (a listing pre-pass):
  // the test's real ref and kind are known before anything runs, so its table rows can
  // render blank and fill in as the results arrive. Ancestor suites materialize from the
  // ref's path prefixes, merged by path with their `SuiteStarted` refs later.
  private def scheduled(ref: TestEvent.Ref, kind: Text): Unit =
    def prefixes(n: Int): Unit =
      if n < ref.path.stdlib.length then
        val prefix: List[Text] = (ref.path.stdlib.take(n)).to(List)

        suiteLine(TestEvent.Ref(t"", prefix.stdlib.last, Unset, prefix, t"", 0))
        prefixes(n + 1)

    prefixes(1)
    // `update`, not `entry`: an `AnchorRecorded` (emitted at declaration, ahead of the
    // schedule) may have created the entry already, kindless; the schedule fills it in.
    update(ref, kind) { entry => entry }

  private def detail(ref: TestEvent.Ref, event: TestEvent): Unit =
    val (_, existing) = details0(ref.id).or((ref, Nil))
    details0 = details0.define(ref.id, (ref, event :: existing))

  def handle(event: TestEvent): Unit = mutex:
    event match
      case TestEvent.TestScheduled(ref, kind) =>
        scheduled(ref, kind)

      case TestEvent.SuiteStarted(ref, _) =>
        suiteLine(ref)
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
      lines0.reverse.map: token =>
        if token.starts(t"s:")
        then Line.SuiteLine(suites0(token.skip(2)).option.get)
        else Line.EntryLine(entries0(token.skip(2)).option.get)

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
