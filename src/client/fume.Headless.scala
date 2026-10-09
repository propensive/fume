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

import pyrocosm.Machine

import denominative.dysasymptotics.linearSize
import environments.javaBaseEnvironment
import probates.cancelProbate
import threading.platformThreading

// A run launched from the daemon itself, by its MCP server, outside any invocation: no
// terminal, no board, no working directory but the classpath's own. The suites stream through
// the event protocol into a model of their own, journalled and persisted exactly as a run from
// a shell is, so the dashboard shows it live and the server answers for it; the launch returns
// at once with the run's id, and `cancel` aborts it as Ctrl+C would. Modelled on the worker's
// run for another machine, which is the same thing with a controller watching.
object Headless:
  private val aborts: scala.collection.concurrent.TrieMap[Text, Atomic[Boolean]] =
    scala.collection.concurrent.TrieMap()

  // Starts the run, or `Unset` when the classpath has no suites. The suites are those the
  // terms reach, as for a run from a shell, so the run record lists what actually runs.
  def launch(classpath: LocalClasspath, terms: List[Text], workspace: Text): Optional[Text] =
    val suites: List[Text] = Suites.reached(classpath, terms)

    if suites.nil then Unset else
      val aborted: Atomic[Boolean] = Atomic(false)

      val id: Text =
        Journal.start
          ( workspace, t"mcp", Invoker.Mcp, classpath.entries.map(entryPath), terms, suites,
            Machine.Identity.local.hostname )

      aborts(id) = aborted

      // On a thread of its own, supervised there: the request that launched it has returned.
      val runnable: Runnable = new Runnable:
        def run(): Unit = safely(supervise(execute(id, classpath, suites, terms, aborted))).unit

      val thread = new Thread(runnable, s"fume-run-${id.s}")
      thread.setDaemon(true)
      thread.start()
      id

  // Aborts a run launched here; `false` for one that was not, or has finished.
  def cancel(id: Text): Boolean =
    val found: Optional[Atomic[Boolean]] = aborts.getOrElse(id, Unset)

    found match
      case aborted: Atomic[Boolean] =>
        aborted() = true
        true

      case _ =>
        false

  private def entryPath(entry: Classpath.Entry): Text = entry match
    case Classpath.Entry.Jar(path)       => path
    case Classpath.Entry.Directory(path) => path
    case _                               => t""

  private def execute
    ( id: Text, classpath: LocalClasspath, suites: List[Text], terms: List[Text], aborted: Atomic[Boolean] )
    ( using Monitor )
  :   Unit =

    val model = Model()
    Server.open(id, model)

    val loader: Classloader = classpath.classloader(Classloader.Delegation.Preferential)
    val shared: Optional[Classloader] = if EventStream.reentrant(loader) then loader else Unset
    val workerTerms: List[Text] = if EventStream.queued(loader) then List(t"--workers=1") else Nil
    val args: List[Text] = workerTerms + terms

    val numbers: Map[Text, Int] =
      suites.indexed.map { (suite, ordinal) => suite -> (ordinal.n0 + 1) }.to[Map]

    def number(suite: Text): Int = numbers(suite).or(0)

    def runSuite(suite: Text): Boolean =
      Journal.began(id, suite)
      val started: Instant over Unix = now()
      val before: Model.State = model.state()
      val beforeTotals: Doc.Totals = Documenting.totals(before)
      model.enter(suite)

      val writer: Optional[Runs.Writer] = Runs.writer(id, number(suite))
      val sink: EventStream.Sink = Runs.tee(writer, EventStream.Sink.decoding(model.handle(_)))
      val captured: Atomic[Optional[Captures.Captured]] = Atomic.Ref.vacant[Captures.Captured]

      val outcome: Optional[EventStream.Outcome] =
        try
          EventStream.frames(classpath, suite, args, shared)
            ( sink,
              () => aborted(),
              (out, err) => captured() = Captures.Captured(suite, out, err) )
        finally writer.let(_.close())

      val after: Model.State = model.state()
      val totals: Doc.Totals = Documenting.totals(after) - beforeTotals

      val passed: Boolean = outcome match
        case EventStream.Outcome.Completed(exit) =>
          // As a run from a shell: a suite admitting nothing is not a failing suite.
          val emptySelection: Boolean = exit == 1 && totals.total == 0 && after.fatals.size == before.fatals.size
          exit == 0 || emptySelection

        case _ =>
          false

      val stored: Boolean = captured() match
        case captured: Captures.Captured => Runs.capture(id, number(suite), captured)
        case _                           => false

      val events: Optional[Text] = writer match
        case _: Runs.Writer => Runs.eventsFile(number(suite))
        case _              => Unset

      Journal.record(id, suite, passed, totals, started, events, stored)
      passed

    def recur(remaining: List[Text], failures: Int): Int = remaining match
      case _ if aborted()  => failures
      case suite :: tail   => recur(tail, if runSuite(suite) then failures else failures + 1)
      case _               => failures

    try
      val failures: Int = recur(suites, 0)
      model.finish()

      val outcome: Journal.Outcome =
        if aborted() then Journal.Outcome.Aborted
        else if failures == 0 then Journal.Outcome.Passed
        else Journal.Outcome.Failed

      Journal.finish(id, outcome, Documenting.totals(model.state()))
    catch case error: Throwable =>
      Journal.finish(id, Journal.Outcome.Aborted, Unset)
    finally
      Server.close(id)
      aborts.remove(id)
