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

import scala.unsafeExceptions.canThrowAny

import soundness.*

import denominative.dysasymptotics.linearSize

// What the daemon tells the MCP server: the implementation of its `Answers`, from the journal,
// the runs directory and the live models, through `Views`. Registered with the server when
// `Fume` is first touched, which every invocation does, so a request never finds it absent.
object Answering extends McpServer.Answers:
  def version: Text = Fume.version

  private def lookup(id: Text): Journal.Run =
    val unknown: Text = t"no run is known as $id; `runs` lists them, and `last` names the newest"
    Journal.find(id).or(throw Api.Error(unknown))

  private def summaries(runs: List[Journal.Run]): List[Api.RunSummary] =
    def recur(todo: List[Journal.Run], done: List[Api.RunSummary]): List[Api.RunSummary] = todo match
      case run :: tail => recur(tail, Views.summary(run) :: done)
      case _           => done.reverse

    recur(runs, Nil)

  private def allResults(id: Text): List[Api.TestResult] =
    val run: Journal.Run = lookup(id)
    Views.results(run, Views.state(run))

  // A classpath as `--classpath` takes it, `:`-separated with globs expanded; entries are
  // absolute, since no invocation's directory applies here.
  private def classpath(text: Text): LocalClasspath =
    val expanded: List[Text] =
      text.cut(t":").filter(_ != t"").bind[List[Text], Text, List[Text]] { entry => Suites.expand(t"/", entry) }

    val entries: List[Classpath.Entry.Directory | Classpath.Entry.Jar] =
      expanded.map: entry =>
        if entry.ends(t".jar") then Classpath.Entry.Jar(entry) else Classpath.Entry.Directory(entry)

    if entries.nil then throw Api.Error(t"the classpath names no entries") else LocalClasspath(entries*)

  def runs(limit: Int): List[Api.RunSummary] = summaries(Journal.all(limit.max(1)))

  def runsIn(workspace: Text, limit: Int): List[Api.RunSummary] =
    def within(run: Journal.Run): Boolean = run.record.workspace.starts(workspace)
    summaries(Journal.all(1000).filter(within).keep(limit.max(1)))

  def run(id: Text): Api.RunDetail = Views.detail(lookup(id))

  def results(run: Text): List[Api.TestResult] = allResults(run)

  def suiteResults(run: Text, suite: Text): List[Api.TestResult] =
    def within(result: Api.TestResult): Boolean = result.ref.suite == suite
    allResults(run).filter(within)

  def failures(run: Text): Api.Failures =
    val found: Journal.Run = lookup(run)
    Views.failures(found, Views.state(found))

  def test(run: Text, test: Text): List[Api.TestResult] =
    def named(result: Api.TestResult): Boolean =
      val path: Text = result.ref.path.join(t"/")
      val qualified: Text = result.ref.suite + t"/" + path
      result.ref.id == test || result.ref.moniker == test || path == test || qualified == test

    allResults(run).filter(named)

  def benchmarks(run: Text): List[Api.TestResult] =
    def measurement(result: Api.TestResult): Boolean = result.kind != t"check"
    allResults(run).filter(measurement)

  def captured(run: Text, suite: Text): Text =
    val found: Journal.Run = lookup(run)
    val id: Text = found.id
    val unknown: Text = t"$suite is not a suite of run $id"

    val number: Int =
      found.record.scheduled.indexed.seek(_(0) == suite).let(_(1).n0 + 1)
      . or(throw Api.Error(unknown))

    Runs.captured(found.id, number).or(t"")

  def processes(): Api.Processes = Views.processes(Fume.version)

  def suites(classpath: Text): List[Api.IndexedSuite] = Views.indexedSuites(this.classpath(classpath))

  def tests(classpath: Text, terms: Text): List[Api.IndexedTest] =
    Views.indexedTests(this.classpath(classpath), terms.cut(t" ").filter(_ != t""))

  def latest: Optional[Api.RunDetail] = Journal.find(t"last") match
    case run: Journal.Run => Views.detail(run)
    case _                => Unset

  // The dashboard's fallback: its assets, then `/mcp`. A method, as `Assets.serve` is, so it
  // holds no capability.
  def fallback(request: Http.Request): Optional[Http.Response] =
    Assets.serve(request) match
      case response: Http.Response => response
      case _                       => McpServer.answer(request)
