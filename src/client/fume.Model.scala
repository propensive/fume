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
┃    Fume, version 0.4.0.                                                                          ┃
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

// The accumulated state of a run's event streams — every suite of the run folds into the one
// model, so the board and the report cover the whole run: a flat, insertion-ordered fold of
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
      anchor:      Optional[TestEvent.AnchorRecorded],
      // The axes a listing pre-pass announced for the test, with their values where they are
      // known ahead: what a chart can lay out before a single record arrives.
      axes:        List[TestEvent.AxisSchedule] = Nil )

  case class State
    ( lines:          List[Line],
      details:        List[(TestEvent.Ref, List[TestEvent])],
      active:         List[TestEvent.Ref],
      fatals:         List[(TestEvent.Trace, List[TestEvent.Ref])],
      completed:      Optional[Boolean],
      nothingMatched: Int,
      // Whether a schedule seeded the run, so a total is known ahead of the results.
      scheduled:      Boolean = false )

final class Model:
  import Model.{Entry, Line, State}

  private val mutex: Mutex = Mutex()

  // Reversed insertion order; `state()` reverses. A test's line is added on first sight —
  // a `schedule` call from a listing pre-pass, or its first event — and a suite's likewise.
  // Line tokens are `s:`-prefixed suite PATHS (a scheduled suite has no id yet; the path is
  // the stable identity a later `SuiteStarted` merges into) and `e:`-prefixed entry ids.
  @scala.caps.unsafe.untrackedCaptures
  private var lines0: List[(Text, Text)] = Nil

  // The tokens in display order, computed when a snapshot is taken and kept until a line is
  // added: the order depends only on the lines' paths, not on what the entries hold, so a
  // result arriving for a known test leaves it standing. With thousands of tests, ordering
  // on every snapshot would dominate every repaint.
  @scala.caps.unsafe.untrackedCaptures
  private var ordered0: Optional[List[Text]] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var suites0: Ledger[Text, TestEvent.Ref] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var entries0: Ledger[Text, Entry] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var details0: Ledger[Text, (TestEvent.Ref, List[TestEvent])] = Ledger()
  @scala.caps.unsafe.untrackedCaptures
  private var active0: List[TestEvent.Ref] = Nil
  // Per-suite terminal events accumulate rather than overwrite: a run has one `RunTerminated`
  // per suite that threw (reversed in `state()`), one `RunCompleted` per suite that finished
  // (their conjunction is the run's verdict), and one `NothingMatched` per suite the selection
  // admitted nothing from (a count, so the report can tell "nothing anywhere" from "nothing
  // in one suite"). The run as a whole is over only when the host says so (`finish`).
  @scala.caps.unsafe.untrackedCaptures
  private var fatals0: List[(TestEvent.Trace, List[TestEvent.Ref])] = Nil
  @scala.caps.unsafe.untrackedCaptures
  private var completed0: Optional[Boolean] = Unset
  @scala.caps.unsafe.untrackedCaptures
  private var nothing0: Int = 0
  @scala.caps.unsafe.untrackedCaptures
  private var done0: Boolean = false
  @scala.caps.unsafe.untrackedCaptures
  private var scheduled0: Boolean = false

  // The suite (its class name) whose events are arriving, set by `enter` before each suite of
  // the run streams. Keys are qualified by it: a nested suite invoked from two top-level
  // suites carries the same name-based path under each, and reported twice under the old
  // model-per-suite arrangement, so it stays twice-reported here.
  @scala.caps.unsafe.untrackedCaptures
  private var scope0: Text = t""

  def enter(scope: Text): Unit = mutex { scope0 = scope }

  // Marks the whole run over: the board, which stays up until then, may close.
  def finish(): Unit = mutex { done0 = true }

  // A suite's key: its name-based path, qualified by the scope it arrived under.
  private def qualify(scope: Text, path: List[Text]): Text = t"$scope:${path.join(t"/")}"
  private def suitePath(path: List[Text]): Text = qualify(scope0, path)

  // An entry's key within the run. A test's 6-hex id hashes only its immediate suite and its
  // name, so two suites of a run can hold tests with the same id; qualifying it by the full
  // path keeps them distinct in the one model (the id alone is still what users see).
  private def key(ref: TestEvent.Ref): Text = t"${suitePath(ref.path)}#${ref.id}"

  private def suiteLine(ref: TestEvent.Ref): Unit =
    val key = suitePath(ref.path)
    if suites0(key).absent then
      suites0 = suites0.define(key, ref)
      lines0 = (t"s:$key", scope0) :: lines0
      ordered0 = Unset
    else if ref.file != t"" then
      // A real `SuiteStarted` ref replaces a scheduled placeholder, in place.
      suites0 = suites0.define(key, ref)

  private def entry(ref: TestEvent.Ref, kind: Optional[Text]): Entry =
    entries0(key(ref)).or:
      val entry = Entry(ref, kind, Nil, Nil, Nil, Unset, Unset)
      entries0 = entries0.define(key(ref), entry)
      lines0 = (t"e:${key(ref)}", scope0) :: lines0
      ordered0 = Unset
      entry

  private def update(ref: TestEvent.Ref, kind: Optional[Text])(lambda: Entry => Entry): Unit =
    val entry0 = entry(ref, kind)

    // A real event's ref replaces a scheduled placeholder (real file and line); the kind
    // fills in if the placeholder had none.
    val entry1 =
      entry0.copy
        ( ref = if ref.file != t"" || entry0.ref.file == t"" then ref else entry0.ref,
          kind = if entry0.kind.absent then kind else entry0.kind )

    entries0 = entries0.define(key(ref), lambda(entry1))

  // Seeds one line of the run's SCHEDULE from a `TestScheduled` event (a listing pre-pass):
  // the test's real ref and kind are known before anything runs, so its table rows can
  // render blank and fill in as the results arrive. Ancestor suites materialize from the
  // ref's path prefixes, merged by path with their `SuiteStarted` refs later.
  private def scheduled(ref: TestEvent.Ref, kind: Text, axes: List[TestEvent.AxisSchedule]): Unit =
    def prefixes(n: Int): Unit =
      if n < ref.path.size then
        val prefix: List[Text] = ref.path.keep(n)

        suiteLine(TestEvent.Ref(t"", prefix.last.or(t""), Unset, prefix, t"", 0))
        prefixes(n + 1)

    prefixes(1)
    // `update`, not `entry`: an `AnchorRecorded` (emitted at declaration, ahead of the
    // schedule) may have created the entry already, kindless; the schedule fills it in.
    update(ref, kind) { entry => entry.copy(axes = axes) }

  private def detail(ref: TestEvent.Ref, event: TestEvent): Unit =
    val (_, existing) = details0(key(ref)).or((ref, Nil))
    details0 = details0.define(key(ref), (ref, event :: existing))

  // A listing pre-pass has seeded the whole schedule: the total is known ahead of the results.
  def listed(): Unit = mutex { scheduled0 = true }

  def handle(event: TestEvent): Unit = mutex:
    event match
      // A `TestScheduled` seeds a row, whether from a listing pre-pass (the whole schedule,
      // ahead of the run: see `listed`) or from a queued runner announcing one assertion it
      // has just deferred, which says nothing about the total.
      case TestEvent.TestScheduled(ref, kind, _, _, axes) =>
        scheduled(ref, kind, axes)

      case TestEvent.SuiteStarted(ref, _) =>
        suiteLine(ref)
        active0 = ref :: active0

      case TestEvent.SuiteEnded(ref, _) =>
        active0 = active0.filter(key(_) != key(ref))

      case TestEvent.TestStarted(ref, _) =>
        entry(ref, Unset)
        active0 = ref :: active0

      case TestEvent.TestEnded(ref, _) =>
        active0 = active0.filter(key(_) != key(ref))

      case TestEvent.TestCompleted(ref, kind, coordinates, outcome, _, _) =>
        update(ref, kind): entry =>
          entry.copy(completions = entry.completions + List((coordinates, outcome)))

      case event@TestEvent.BenchmarkRecorded(ref, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
        update(ref, t"bench") { entry => entry.copy(benches = entry.benches + List(event)) }

      case event@TestEvent.StrainRecorded(ref, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
        update(ref, t"stress") { entry => entry.copy(strains = entry.strains + List(event)) }

      case event@TestEvent.HotspotsRecorded(ref, _, _, _, _) =>
        update(ref, t"profile") { entry => entry.copy(hotspots = event) }

      case event@TestEvent.AnchorRecorded(ref, _, _, _, _, _, _, _) =>
        update(ref, Unset) { entry => entry.copy(anchor = event) }

      case event@TestEvent.DetailCaptures(ref, _)       => detail(ref, event)
      case event@TestEvent.DetailCompare(ref, _, _, _)  => detail(ref, event)
      case event@TestEvent.DetailMessage(ref, _)        => detail(ref, event)
      case event@TestEvent.DetailThrows(ref, _, _)      => detail(ref, event)

      case TestEvent.NothingMatched(_) =>
        nothing0 += 1

      case TestEvent.RunTerminated(error, active, _) =>
        fatals0 = (error, active) :: fatals0

      case TestEvent.RunCompleted(passed, _) =>
        completed0 = completed0.lay(passed)(_ && passed)

  def finished: Boolean = mutex(done0)

  // The lines in depth-first declaration order, whatever order they arrived in. A suite takes
  // its place among its siblings from the FIRST test beneath it (its own arrival index only
  // when it has none) rather than from its announcement — which, after a listing pre-pass,
  // precedes every test — and is followed by its own children, recursively. In a plain run a
  // suite is announced just before its first test anyway, so the order is exactly arrival
  // order.
  //
  // Every key is qualified by the scope (the suite class) a line arrived under, not the
  // current one: the run's suites all share the model, and two of them can announce suites
  // with the same name-based path.
  private def order(lines: List[(Line, Text, Text)]): List[Text] =
    import sortingAlgorithms.timsort

    val indexed: List[((Line, Text, Text), Ordinal)] = lines.indexed

    def pathOf(line: Line): List[Text] = line match
      case Line.SuiteLine(ref)   => ref.path
      case Line.EntryLine(entry) => entry.ref.path

    // Each suite's arrival index, and the arrival index of the first entry beneath it, by
    // path.
    var own: Ledger[Text, Int] = Ledger()
    var least: Ledger[Text, Int] = Ledger()

    indexed.each: pair =>
      val scope: Text = pair(0)(1)

      pair(0)(0) match
        case Line.SuiteLine(ref) =>
          val key = qualify(scope, ref.path)
          if own(key).absent then own = own.define(key, pair(1).n0)

        case Line.EntryLine(entry) =>
          List.range(1, entry.ref.path.size).each: n =>
            val ancestor = qualify(scope, entry.ref.path.keep(n))
            if least(ancestor).absent then least = least.define(ancestor, pair(1).n0)

    def rank(line: Line, scope: Text, index: Ordinal): Int = line match
      case Line.SuiteLine(ref) => least(qualify(scope, ref.path)).or(index.n0)
      case _                   => index.n0

    // A line's parent is the LONGEST proper prefix of its path which is a suite line (a test
    // scheduled before its suite is announced hangs from the nearest ancestor present), or
    // the empty key at the root.
    def parentOf(scope: Text, path: List[Text]): Text =
      List.range(1, path.size).reverse.seek { n => own(qualify(scope, path.keep(n))).present }
      . lay(t"") { n => qualify(scope, path.keep(n)) }

    val children: Map[Text, List[((Line, Text, Text), Ordinal)]] =
      indexed.group { (pair, _) => parentOf(pair(1), pathOf(pair(0))) }

    def walk(parent: Text): List[Text] =
      children(parent).or(Nil).order { (pair, index) => rank(pair(0), pair(1), index) }
      . bind[List[Text], Text, List[Text]]: (pair, _) =>
          pair(0) match
            case Line.SuiteLine(ref) => pair(2) :: walk(qualify(pair(1), ref.path))
            case _                   => List(pair(2))

    walk(t"")

  // The line a token names now: a suite's current ref, or an entry's current state.
  private def resolve(token: Text): Optional[Line] =
    if token.starts(t"s:") then suites0(token.skip(2)).let(Line.SuiteLine(_))
    else entries0(token.skip(2)).let(Line.EntryLine(_))

  def state(): State = mutex:
    val tokens: List[Text] = ordered0.or:
      val computed: List[Text] =
        order:
          lines0.reverse.bind[List[(Line, Text, Text)], (Line, Text, Text), List[(Line, Text, Text)]]: (token, scope) =>
            resolve(token).lay(Nil: List[(Line, Text, Text)]) { line => List((line, scope, token)) }

      ordered0 = computed
      computed

    // Entries are snapshots already (an event replaces an entry wholesale), so the lines are
    // the current entries themselves: no copy per test per snapshot.
    val lines: List[Line] =
      tokens.bind[List[Line], Line, List[Line]] { token => resolve(token).lay(Nil: List[Line])(List(_)) }

    val details: List[(TestEvent.Ref, List[TestEvent])] =
      details0.to[List].map { (pair: (Text, (TestEvent.Ref, List[TestEvent]))) =>
        (pair(1)(0), pair(1)(1).reverse) }

    State(lines, details, active0.reverse, fatals0.reverse, completed0, nothing0, scheduled0)
