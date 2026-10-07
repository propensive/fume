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

// The daemon's record of test runs: every `run` invocation is entered in the journal when it
// starts and moves to the completed list when it finishes. The journal lives in the DAEMON,
// which outlives each client, so it accumulates the history of every run the daemon has
// served — several may be in flight at once (one per client), so every operation is
// mutex-guarded and keyed by run id.
//
// Every run is a `RunRecord`, persisted under the runs directory (`Runs`) as it changes: the
// journal is a cache over that directory, loaded from it when the daemon first needs its
// history, so a run survives the daemon that made it, and ids are stable across daemons.
//
// Named `Journal`, not `Ledger`: `Ledger` is a Soundness type (fume's own `Model` keys its
// entries with one), and a package-level `fume.Ledger` would shadow it throughout `fume`.
object Journal:
  // How a run ended. A run still in flight has no outcome.
  enum Outcome:
    case Passed, Failed, Aborted

    def show: Text = this match
      case Passed  => RunRecord.passed
      case Failed  => RunRecord.failed
      case Aborted => RunRecord.aborted

  // A run as the journal holds it: its record, and — for a run in flight — the suite it is
  // executing, which is the daemon's knowledge alone and not persisted.
  case class Run(record: RunRecord, current: Optional[Text]):
    def id: Text = record.id
    def running: Boolean = record.running

  // The completed runs kept in memory. Old enough runs fall off the end: the daemon is
  // long-lived, and an unbounded history would grow without limit; the directory keeps the
  // rest, up to the retention setting.
  private val history: Int = 64

  private val mutex: Mutex = Mutex()

  // Both lists are newest-first.
  @scala.caps.unsafe.untrackedCaptures
  private var active0: List[Run] = Nil
  @scala.caps.unsafe.untrackedCaptures
  private var completed0: Optional[List[Run]] = Unset

  private def restore(record: RunRecord): Run =
    if record.running then
      val corrected: RunRecord = record.copy(finished = record.started, outcome = RunRecord.aborted)
      Runs.write(corrected)
      Run(corrected, Unset)
    else Run(record, Unset)

  // The history, loaded from the runs directory on first use. A run this daemon has in flight
  // is already on disk, and is not history; a run the directory holds with no end — its
  // daemon died mid-run — is ended as aborted, and the record corrected.
  private def completedRuns(): List[Run] = completed0.or:
    def inFlight(record: RunRecord): Boolean = active0.exists(_.id == record.id)
    val loaded: List[Run] = Runs.load(history).filter(!inFlight(_)).map(restore)
    completed0 = loaded
    loaded

  private def amend(id: Text)(lambda: Run => Run): Unit =
    active0 = active0.map { run => if run.id == id then lambda(run) else run }

  private def persist(id: Text): Unit = active0.seek(_.id == id) match
    case run: Run => Runs.write(run.record)
    case _        => ()

  private def totals(totals: Optional[Doc.Totals]): Optional[RunRecord.Totals] = totals match
    case totals: Doc.Totals =>
      RunRecord.Totals(totals.passed, totals.failed, totals.aspirePassed, totals.aspireFailed)

    case _ =>
      Unset

  // Enters a starting run, returning the id by which it is later amended and completed.
  def start
    ( workspace: Text,
      client:    Text,
      invoker:   Invoker,
      classpath: List[Text],
      selection: List[Text],
      scheduled: List[Text],
      machine:   Text )
    ( using Environment )
  :   Text =

    mutex:
      Runs.locate()
      val started: Instant over Unix = now()

      val id: Text = Runs.id(started)

      val record: RunRecord =
        RunRecord
          ( id, Fume.version, workspace, invoker.word, client, machine, started, Unset, Unset,
            classpath, selection, scheduled, Nil, Unset )

      active0 = Run(record, Unset) :: active0
      Runs.write(record)
      record.id

  // Marks the suite a run is currently executing.
  def began(id: Text, suite: Text): Unit = mutex:
    amend(id) { run => run.copy(current = suite) }

  def record
    ( id:       Text,
      suite:    Text,
      passed:   Boolean,
      totals:   Optional[Doc.Totals],
      started:  Instant over Unix,
      events:   Optional[Text],
      captured: Boolean )
  :   Unit =

    mutex:
      amend(id): run =>
        val entry = RunRecord.Suite(suite, passed, started, now(), this.totals(totals), events, captured)
        run.copy(record = run.record.copy(suites = run.record.suites + List(entry)), current = Unset)

      persist(id)

  // Completes a run, moving it out of the active list.
  def finish(id: Text, outcome: Outcome, totals: Optional[Doc.Totals]): Unit = mutex:
    val found: Optional[Run] = active0.seek(_.id == id)

    found match
      case run: Run =>
        val finished: Optional[Instant over Unix] = now()
        val word: Optional[Text] = outcome.show
        val counts: Optional[RunRecord.Totals] = this.totals(totals)
        val record: RunRecord = run.record.copy(finished = finished, outcome = word, totals = counts)
        val done: Run = Run(record, Unset)

        active0 = active0.filter(_.id != id)
        Runs.write(record)
        // Typed first: the cons result's element type is otherwise still being inferred when
        // `keep` is resolved. The history may have been loaded from disk while this run was in
        // flight, and so hold its record already.
        val extended: List[Run] = done :: completedRuns().filter(_.id != id)
        completed0 = extended.keep(history)

      case _ =>
        ()

  def active: List[Run] = mutex(active0)
  def completed: List[Run] = mutex(completedRuns())

  // A run by id — `last` for the newest — from memory, or from the directory beyond the
  // history the journal keeps.
  def find(id: Text): Optional[Run] = mutex:
    val known: List[Run] = active0 + completedRuns()

    if id == t"last" then known.prim else
      val found: Optional[Run] = known.seek(_.id == id)

      found match
        case run: Run => run
        case _        => Runs.read(id) match
          case record: RunRecord => Run(record, Unset)
          case _                 => Unset

  // Every run, newest first, the active ones first: the journal's, then the directory's.
  def all(limit: Int): List[Run] = mutex:
    val known: List[Run] = active0 + completedRuns()

    if known.size >= limit then known.keep(limit) else
      val ids: Set[Text] = known.map(_.id).to[Set]
      val more: List[Run] = Runs.load(limit).filter { record => !ids.has(record.id) }.map(Run(_, Unset))
      (known + more).keep(limit)
