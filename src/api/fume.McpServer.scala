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

import java.util.concurrent as juc

import scala.unsafeExceptions.canThrowAny

import soundness.*

import pyrocosm.Tool

import denominative.dysasymptotics.linearAccess

import codepages.utf8Codepage
import formatting.indentedJsonFormatting
import internetAccess.online
import logging.silentLogging
import webserverErrorPages.minimalErrorPage

// Fume as an MCP server: the tools an agent calls to ask about runs — the journal's and the
// persisted ones — their results and failures, the daemon's processes, and the tests a
// classpath declares; and the resources describing them. Served over MCP's streamable HTTP
// transport at `/mcp`: on a port of its own by the `mcp` service (`fume mcp`, or `mcp` in a
// configuration file), and on the dashboard's port whenever the dashboard serves.
//
// The server lives here, outside capture checking, because its specification is derived by
// synesthesia's macro from the annotated methods below, and the generated code is not
// capture-clean; the daemon's knowledge lives in the client, which registers its `Answers`
// before a request can arrive, and every tool delegates to it.
//
// No tool takes an optional parameter: synesthesia lists every parameter as required, so a
// tool with a sensible default is a tool of its own (`runs` and `runsIn`), and `last` names
// the newest run wherever a run id is taken. Every answer is a JSON value of an `Api` type,
// whose schema `fume://schema` serves.
object McpServer extends Mcp.Server():
  class Session() extends Mcp.Session
  def initialize(): Session = new Session()

  def name: Text = t"fume"
  def description: Text = t"Test and benchmark runs, their results, and the processes of the fume daemon"
  def prompts: List[Mcp.Prompt] = Nil

  // A development build's version is a tree hash, which is no semver; such a build serves as
  // 0.0.0.
  def version: Semver =
    safely(answering.version.as[Semver]).or(unsafely(t"0.0.0".as[Semver]))

  // What the daemon knows, as the client supplies it.
  trait Answers:
    def version: Text
    def runs(limit: Int): List[Api.RunSummary]
    def runsIn(workspace: Text, limit: Int): List[Api.RunSummary]
    def run(id: Text): Api.RunDetail
    def results(run: Text): List[Api.TestResult]
    def suiteResults(run: Text, suite: Text): List[Api.TestResult]
    def failures(run: Text): Api.Failures
    def test(run: Text, test: Text): List[Api.TestResult]
    def benchmarks(run: Text): List[Api.TestResult]
    def captured(run: Text, suite: Text): Text
    def processes(): Api.Processes
    def suites(classpath: Text): List[Api.IndexedSuite]
    def tests(classpath: Text, terms: Text): List[Api.IndexedTest]
    def latest: Optional[Api.RunDetail]

  @volatile
  private var answers: Optional[Answers] = Unset

  def register(answers: Answers): Unit = this.answers = answers

  private def answering: Answers =
    answers.or(throw Api.Error(t"the fume daemon has not registered what it knows"))

  // The specification the macro derives encodes a list literal of two texts — the visibility
  // of an `@ui` resource, which no tool here declares, so the code never runs — whose type is
  // a POPULATED list, which jacinta's list encoder (matched on the list constructor alone)
  // does not cover, and whose fallback encoder asks, at this site, for a text encoding. This
  // instance answers it, so the specification derives. (Upstream, synesthesia should ascribe
  // the literal's type: propensive/soundness#2187.)
  given populatedTexts: (List[Text] & Populated) is Encodable in Text = _.join(t",")

  @tool
  @about("The most recent runs of tests and benchmarks, newest first, runs in flight first; `limit` caps how many")
  def runs(limit: Int): List[Api.RunSummary] = answering.runs(limit)

  @tool
  @about("The most recent runs invoked from a working directory under `workspace`, newest first")
  def runsIn(workspace: Text, limit: Int): List[Api.RunSummary] = answering.runsIn(workspace, limit)

  @tool
  @about("One run in detail: its classpath, selection, each suite's totals, and whether results are available; `id` may be `last`")
  def run(id: Text): Api.RunDetail = answering.run(id)

  @tool
  @about("Every test of a run with its status, and its measurements for a benchmark, stress test or profile; `run` may be `last`")
  def results(run: Text): List[Api.TestResult] = answering.results(run)

  @tool
  @about("The tests of one suite of a run, by the suite's class name; `run` may be `last`")
  def suiteResults(run: Text, suite: Text): List[Api.TestResult] = answering.suiteResults(run, suite)

  @tool
  @about("The failing tests of a run with their diagnostics — message, stack trace, captured values, expected and found — and any error that ended a suite; `run` may be `last`")
  def failures(run: Text): Api.Failures = answering.failures(run)

  @tool
  @about("One test of a run by its 6-hex id, moniker, or slash-joined path; `run` may be `last`")
  def test(run: Text, test: Text): List[Api.TestResult] = answering.test(run, test)

  @tool
  @about("The benchmarks, stress tests and profiles of a run with their records; `run` may be `last`")
  def benchmarks(run: Text): List[Api.TestResult] = answering.benchmarks(run)

  @tool
  @about("What a suite of a run printed outside its report, on stdout and stderr; `run` may be `last`")
  def captured(run: Text, suite: Text): Text = answering.captured(run, suite)

  @tool
  @about("The fume daemon's processes: the daemon itself, the services it serves, the runs in flight with the tests they are executing, and the run it is working for another machine")
  def processes(): Api.Processes = answering.processes()

  @tool
  @about("The test suites on a classpath, from its META-INF/services/probably.Suite index, without running anything; the classpath is `:`-separated absolute jars and directories")
  def suites(classpath: Text): List[Api.IndexedSuite] = answering.suites(classpath)

  @tool
  @about("The tests a classpath declares, from its static index, without running anything, narrowed by space-separated selection terms as `fume list` takes them; empty terms list every test")
  def tests(classpath: Text, terms: Text): List[Api.IndexedTest] = answering.tests(classpath, terms)

  @resource("fume://schema")
  @about("The JSON Schema of every type the tools answer with, by name")
  def schema: Text = Api.schema(answering.version).in[Json].show

  @resource("fume://docs")
  @about("How to use this server: its tools, resources, vocabularies and the run directory")
  def docs: Text = McpServer.documentation

  @resource("fume://runs/latest")
  @about("The newest run in detail, as `run` answers for `last`")
  def latest: Text = answering.latest.let(_.in[Json].show).or(t"{}")

  // The documentation, bundled as `res/fume/mcp.md` beside the dashboard's icons, read through
  // the thread's context classloader as the dashboard's assets are.
  lazy val documentation: Text =
    val loader: ClassLoader =
      Optional(Thread.currentThread.nn.getContextClassLoader).or(classOf[Api.type].getClassLoader.nn)

    Optional(loader.getResourceAsStream("fume/mcp.md")).let: stream =>
      String(stream.readAllBytes(), "UTF-8").tt
    . or(t"The documentation was not bundled with this build of fume.")

  // Answers a request for `/mcp`, and nothing else: the dashboard chains this after its own
  // assets, and the `mcp` service serves it alone.
  def respond(request: Http.Request)(using Monitor, Probate): Optional[Http.Response] =
    val path: Text = request.target.cut(t"?").at(Prim).or(t"/")

    if path != t"/mcp" then Unset else
      given Http.Request = request
      // An unknown tool or resource raises `Mcp.Error`, which the protocol answers as a
      // JSON-RPC error; the tactic that lets it propagate is all that is needed.
      unsafely(serve)

  // As `respond`, for a host holding no monitor: each request is supervised on its own, which
  // serves the tool calls and listings an agent makes by POST; the server-to-client stream a
  // GET opens, which no tool here uses, lives only as long as its request on such a host.
  def answer(request: Http.Request): Optional[Http.Response] =
    import threading.platformThreading
    import probates.cancelProbate
    unsafely(supervise(respond(request)))

  val service: Tool.Service = new Tool.Service:
    def keyword: Text = t"mcp"
    def portKeyword: Text = t"mcpPort"
    def port: Int = 8092

    @volatile
    private var latch: juc.CountDownLatch = juc.CountDownLatch(0)

    def serve(port: Int, settings: Text => Optional[Text])(using monitor: Monitor, probate: Probate)
    :   Unit =

      val waiting: juc.CountDownLatch = juc.CountDownLatch(1)
      latch = waiting

      safely:
        val running =
          SocketServer(port).handle:
            val request: Http.Request = summon[Http.Request]
            respond(request).or(Http.Response(Http.NotFound)(t"Not found"))

        McpServer.serving = port

        try waiting.await()
        finally
          McpServer.serving = Unset
          running.cancel()

      . unit

    def stop(): Unit = latch.countDown()

  // The port the `mcp` service is serving on, for `processes`.
  @volatile
  var serving: Optional[Int] = Unset
