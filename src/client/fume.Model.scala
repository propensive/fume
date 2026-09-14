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

import denominative.dysasymptotics.linearSize

import probably.TestEvent

// The accumulated state of one suite's event stream: a flat, insertion-ordered fold of
// `TestEvent`s, from which `Documenting` derives the report document and the live board
// derives its filling-in table. The tree structure of the original report is not rebuilt —
// each `Ref` carries its full suite path, so depth and grouping are derivable — and every
// snapshot (`state()`) is immutable, so renderers never race the consuming thread.
object Model:
  // One line of the results display, in declaration order: suites interleave with the tests
  // beneath them exactly as the (depth-first) run visits them. That order is RECONSTRUCTED
  // from each ref's path when a snapshot is taken (see `order`), not taken from arrival: a
  // `--list` pre-pass runs the whole suite body (every `SuiteStarted`) before it emits a single
  // `TestScheduled`, so by arrival every suite would precede every test.
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
      if n < ref.path.size then
        val prefix: List[Text] = ref.path.keep(n)

        suiteLine(TestEvent.Ref(t"", prefix.last.or(t""), Unset, prefix, t"", 0))
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
      case TestEvent.TestScheduled(ref, kind, _, _, _) =>
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

      case event@TestEvent.BenchmarkRecorded(ref, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
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

  // The lines in depth-first declaration order, whatever order they arrived in. A suite takes
  // its place among its siblings from the FIRST test beneath it (its own arrival index only
  // when it has none) rather than from its announcement — which, after a listing pre-pass,
  // precedes every test — and is followed by its own children, recursively. In a plain run a
  // suite is announced just before its first test anyway, so the order is exactly arrival
  // order.
  private def order(lines: List[Line]): List[Line] =
    import sortingAlgorithms.timsort

    val indexed: List[(Line, Ordinal)] = lines.indexed

    def pathOf(line: Line): List[Text] = line match
      case Line.SuiteLine(ref)   => ref.path
      case Line.EntryLine(entry) => entry.ref.path

    // Each suite's arrival index, and the arrival index of the first entry beneath it, by
    // path.
    var own: Ledger[Text, Int] = Ledger()
    var least: Ledger[Text, Int] = Ledger()

    indexed.each: pair =>
      pair(0) match
        case Line.SuiteLine(ref) =>
          val key = suitePath(ref.path)
          if own(key).absent then own = own.define(key, pair(1).n0)

        case Line.EntryLine(entry) =>
          List.range(1, entry.ref.path.size).each: n =>
            val ancestor = suitePath(entry.ref.path.keep(n))
            if least(ancestor).absent then least = least.define(ancestor, pair(1).n0)

    def rank(line: Line, index: Ordinal): Int = line match
      case Line.SuiteLine(ref) => least(suitePath(ref.path)).or(index.n0)
      case _                   => index.n0

    // A line's parent is the LONGEST proper prefix of its path which is a suite line (a test
    // scheduled before its suite is announced hangs from the nearest ancestor present), or
    // the empty key at the root.
    def parentOf(path: List[Text]): Text =
      List.range(1, path.size).reverse.seek { n => own(suitePath(path.keep(n))).present }
      . lay(t"") { n => suitePath(path.keep(n)) }

    val children: Map[Text, List[(Line, Ordinal)]] =
      indexed.group { (line, _) => parentOf(pathOf(line)) }

    def walk(parent: Text): List[Line] =
      children(parent).or(Nil).order { (line, index) => rank(line, index) }
      . bind[List[Line], Line, List[Line]]: (line, _) =>
          line match
            case Line.SuiteLine(ref) => line :: walk(suitePath(ref.path))
            case _                   => List(line)

    walk(t"")

  def state(): State = mutex:
    val lines: List[Line] = order:
      lines0.reverse.bind[List[Line], Line, List[Line]]: token =>
        if token.starts(t"s:")
        then suites0(token.skip(2)).lay(Nil: List[Line]) { ref => List(Line.SuiteLine(ref)) }
        else entries0(token.skip(2)).lay(Nil: List[Line]) { entry => List(Line.EntryLine(entry)) }

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
