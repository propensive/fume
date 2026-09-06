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

import java.lang as jl

import soundness.*

// The daemon's record of test runs: every `run` invocation is entered in the journal when it
// starts and moves to the completed list when it finishes. The journal lives in the DAEMON,
// which outlives each client, so it accumulates the history of every run the daemon has
// served — several may be in flight at once (one per client), so every operation is
// mutex-guarded and keyed by run id.
//
// Named `Journal`, not `Ledger`: `Ledger` is a Soundness type (fume's own `Model` keys its
// entries with one), and a package-level `fume.Ledger` would shadow it throughout `fume`.
//
// Everything stored here is pure data — no capabilities, nothing holding a client's stdio —
// so a snapshot can be rendered long after the run that produced it has gone.
object Journal:
  // How a run ended. A run still in flight has no outcome.
  enum Outcome:
    case Passed, Failed, Aborted

    def show: Text = this match
      case Passed  => t"passed"
      case Failed  => t"failed"
      case Aborted => t"aborted"

  // One suite's contribution to a run: whether it passed, and its totals when it ran by the
  // event protocol (a legacy or forked suite reports only its exit status).
  case class SuiteRun
    ( suite:    Text,
      passed:   Boolean,
      totals:   Optional[Doc.Totals],
      started:  Long,
      finished: Long ):

    def duration: Long = finished - started

  case class Run
    ( id:        Int,
      client:    Text,
      started:   Long,
      classpath: Text,
      selection: List[Text],
      scheduled: List[Text],
      suites:    List[SuiteRun],
      current:   Optional[Text],
      finished:  Optional[Long],
      outcome:   Optional[Outcome],
      totals:    Optional[Doc.Totals] ):

    def running: Boolean = finished.absent
    def duration: Optional[Long] = finished.let(_ - started)
    def failures: Int = suites.stdlib.count(!_.passed)

  // The completed runs kept in memory. Old enough runs fall off the end: the daemon is
  // long-lived, and an unbounded history would grow without limit.
  private val history: Int = 64

  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var next: Int = 0
  // Both lists are newest-first.
  @scala.caps.unsafe.untrackedCaptures
  private var active0: List[Run] = Nil
  @scala.caps.unsafe.untrackedCaptures
  private var completed0: List[Run] = Nil

  private def amend(id: Int)(lambda: Run => Run): Unit =
    active0 = active0.map { run => if run.id == id then lambda(run) else run }

  // Enters a starting run, returning the id by which it is later amended and completed.
  def start(client: Text, classpath: Text, selection: List[Text], scheduled: List[Text]): Int =
    mutex:
      next += 1

      val run =
        Run
          ( next, client, jl.System.currentTimeMillis, classpath, selection, scheduled, Nil,
            Unset, Unset, Unset, Unset )

      active0 = run :: active0
      next

  // Marks the suite a run is currently executing.
  def began(id: Int, suite: Text): Unit = mutex:
    amend(id) { run => run.copy(current = suite) }

  def record(id: Int, suite: Text, passed: Boolean, totals: Optional[Doc.Totals], started: Long)
  :   Unit =

    mutex:
      amend(id): run =>
        val entry = SuiteRun(suite, passed, totals, started, jl.System.currentTimeMillis)
        run.copy(suites = run.suites + List(entry), current = Unset)

  // Completes a run, moving it out of the active list.
  def finish(id: Int, outcome: Outcome, totals: Optional[Doc.Totals]): Unit = mutex:
    active0.seek(_.id == id).let: run =>
      val done =
        run.copy
          ( current = Unset,
            finished = jl.System.currentTimeMillis,
            outcome = outcome,
            totals = totals )

      active0 = active0.filter(_.id != id)
      // Typed before the bridge: the cons result's element type is otherwise still being
      // inferred when `stdlib` is resolved.
      val extended: List[Run] = done :: completed0
      completed0 = extended.stdlib.take(history).to(List)

  def active: List[Run] = mutex(active0)
  def completed: List[Run] = mutex(completed0)
