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

// The API's view of what the daemon holds: journal runs and model states — live, for a run in
// flight here, or replayed from its stored frames — as the `Api` types the MCP server answers
// with. Pure conversions; what to answer is the server's business.
object Views:
  private def totals(totals: RunRecord.Totals): Api.Totals =
    Api.Totals(totals.passed, totals.failed, totals.aspirePassed, totals.aspireFailed)

  private def millis(duration: Duration): Long = (duration.value*1000.0).round

  def summary(run: Journal.Run): Api.RunSummary =
    val record: RunRecord = run.record

    Api.RunSummary
      ( record.id, record.workspace, record.invoker, record.machine, record.started,
        record.finished, record.outcome, record.duration.let(millis), record.selection,
        record.scheduled.size, record.totals.let(totals) )

  private def suite(suite: RunRecord.Suite): Api.SuiteSummary =
    Api.SuiteSummary
      ( suite.suite, suite.passed, suite.started, suite.finished, suite.totals.let(totals),
        suite.captured )

  // The run's state: the live model's, for a run in flight on this daemon, and otherwise the
  // replay of its stored frames.
  def state(run: Journal.Run): Model.State =
    Server.model(run.id).let(_.state()).or(Runs.replay(run.record).state)

  def detail(run: Journal.Run): Api.RunDetail =
    val record: RunRecord = run.record

    val availability: Text =
      if Server.model(run.id).present then t"available" else Runs.replay(record).availability

    Api.RunDetail
      ( summary(run), record.classpath, record.scheduled, record.suites.map(suite), run.current,
        availability )

  def ref(ref: TestEvent.Ref, scope: Text): Api.TestRef =
    Api.TestRef(ref.id, ref.name, ref.moniker, ref.path, scope, ref.file, ref.line)

  private def coordinate(coordinate: TestEvent.Coordinate): Api.Coordinate =
    val value: Text =
      coordinate.discrete
      . or(coordinate.integral.let(_.toString.tt))
      . or(coordinate.decimal.let(_.toString.tt))
      . or(t"")

    Api.Coordinate(coordinate.axis, value)

  private def trace(trace: TestEvent.Trace): Api.Trace =
    Api.Trace:
      trace.components.map: component =>
        Api.TraceComponent
          ( component.className,
            component.message,
            component.frames.map { frame => Api.Frame(frame.className, frame.method, frame.file, frame.line) } )

  // The command which reruns one test: its suite alone, and its id, which is unique within
  // the suite.
  private def rerun(record: RunRecord, scope: Text, ref: TestEvent.Ref): Text =
    val term: Text = if ref.id == t"" then ref.path.join(t"/") else ref.id
    val classpath: Text = record.classpath.join(t":")
    t"fume run -c $classpath -s $scope $term"

  private def bench(bench: TestEvent.BenchmarkRecorded): Api.Benchmark =
    Api.Benchmark
      ( bench.coordinates.map(coordinate), bench.nanoseconds, bench.iterations, bench.runs,
        bench.mean, bench.min, bench.max, bench.sd, bench.confidence, bench.operationSize,
        bench.operationRate, bench.allocation )

  private def strain(strain: TestEvent.StrainRecorded): Api.Strain =
    Api.Strain
      ( strain.coordinates.map(coordinate), strain.concurrency, strain.operations,
        strain.nanoseconds, strain.allocation, strain.peakHeap, strain.retained, strain.gcCount,
        strain.gcTime, strain.p50, strain.p90, strain.p99, strain.p999, strain.compliance,
        strain.sustained )

  // The lambda-bearing arguments are bound first: passed inline to the constructor, the
  // compiler fails an assertion (a proscala bug) rather than typing them.
  private def result(record: RunRecord, entry: Model.Entry): Api.TestResult =
    val completions: List[Api.Completion] =
      entry.completions.map: completion =>
        Api.Completion(completion(0).map(coordinate), completion(1).outcome, completion(1).duration)

    val hotspots: List[Api.Hotspot] =
      entry.hotspots.lay(Nil: List[Api.Hotspot]): hotspots =>
        hotspots.frames.map { frame => Api.Hotspot(frame.className, frame.method, frame.samples) }

    Api.TestResult
      ( ref(entry.ref, entry.scope),
        entry.kind.or(t"check"),
        Documenting.entryStatus(entry).word,
        completions,
        entry.benches.map(bench),
        entry.strains.map(strain),
        hotspots,
        rerun(record, entry.scope, entry.ref) )

  private def entries(state: Model.State): List[Model.Entry] =
    state.lines.bind[List[Model.Entry], Model.Entry, List[Model.Entry]]:
      case Model.Line.EntryLine(entry) => List(entry)
      case _                           => Nil

  def results(run: Journal.Run, state: Model.State): List[Api.TestResult] =
    entries(state).map(result(run.record, _))

  // A test's details, by its ref: the model's details are keyed by the qualified ref, but a
  // snapshot carries the ref alone, so a test is matched by its id and path.
  private def details(state: Model.State, ref: TestEvent.Ref): List[TestEvent] =
    state.details.seek { (other, _) => other.id == ref.id && other.path == ref.path }.lay(Nil: List[TestEvent])(_(1))

  def failures(run: Journal.Run, state: Model.State): Api.Failures =
    val failing: List[Model.Entry] = entries(state).filter { entry => Documenting.entryStatus(entry).failed }

    val failures: List[Api.Failure] = failing.map: entry =>
      val events: List[TestEvent] = details(state, entry.ref)

      val message: Optional[Text] =
        events.sweep { case TestEvent.DetailMessage(_, message) => message }.prim

      val thrown: Optional[TestEvent.Trace] =
        events.sweep { case TestEvent.DetailThrows(_, _, stack) => stack }.prim

      val completed: List[TestEvent.Trace] =
        entry.completions.bind[List[TestEvent.Trace], TestEvent.Trace, List[TestEvent.Trace]]:
          completion => completion(1).stack.lay(Nil: List[TestEvent.Trace])(List(_))

      val captures: List[Api.Capture] =
        events.bind[List[Api.Capture], Api.Capture, List[Api.Capture]]:
          case TestEvent.DetailCaptures(_, values) =>
            values.to[List].map { (label, value) => Api.Capture(label, value) }

          case _ =>
            Nil

      val compare: Optional[TestEvent.DetailCompare] =
        events.sweep { case compare: TestEvent.DetailCompare => compare }.prim

      def comparison(row: TestEvent.CompareRow): Api.Comparison =
        Api.Comparison(row.depth, row.label, row.kind, row.left, row.right, row.difference)

      val rows: List[Api.Comparison] = compare match
        case compare: TestEvent.DetailCompare => compare.rows.map(comparison)
        case _                                => Nil

      val stack: Optional[TestEvent.Trace] = thrown match
        case stack: TestEvent.Trace => stack
        case _                      => completed.prim

      val traced: Optional[Api.Trace] = stack match
        case stack: TestEvent.Trace => trace(stack)
        case _                      => Unset

      val expected: Optional[Text] = compare match
        case compare: TestEvent.DetailCompare => compare.expected
        case _                                => Unset

      val found: Optional[Text] = compare match
        case compare: TestEvent.DetailCompare => compare.found
        case _                                => Unset

      Api.Failure
        ( ref(entry.ref, entry.scope),
          Documenting.entryStatus(entry).word,
          message,
          traced,
          captures,
          expected,
          found,
          rows,
          rerun(run.record, entry.scope, entry.ref) )

    def fatal(error: TestEvent.Trace, active: List[TestEvent.Ref]): Api.Fatal =
      def anonymous(ref: TestEvent.Ref): Api.TestRef = this.ref(ref, t"")
      Api.Fatal(trace(error), active.map(anonymous))

    val fatals: List[Api.Fatal] = state.fatals.map(fatal)

    Api.Failures(run.id, failures, fatals, state.nothingMatched > 0 && entries(state).nil)

  def indexedSuites(classpath: LocalClasspath): List[Api.IndexedSuite] =
    val index: Index = Suites.index(classpath)

    Suites.discover(classpath).map: suite =>
      Api.IndexedSuite(suite, index.knows(suite), index.tests(suite).size)

  def indexedTests(classpath: LocalClasspath, terms: List[Text]): List[Api.IndexedTest] =
    val index: Index = Suites.index(classpath)

    Suites.discover(classpath).bind[List[Api.IndexedTest], Api.IndexedTest, List[Api.IndexedTest]]: suite =>
      index.tests(suite).filter(Index.admits(_, terms)).map: test =>
        val leaf: Optional[Index.Link] = test.links.last

        Api.IndexedTest
          ( if test.id == t"" then Unset else test.id,
            test.path,
            leaf.let(_.name).or(t""),
            leaf.let(_.moniker),
            test.kind,
            test.tags,
            suite,
            test.file,
            test.line,
            test.spread,
            test.dynamic )

  def processes(version: Text): Api.Processes =
    val handle = ProcessHandle.current.nn

    val started: Instant over Unix =
      Optional(handle.info.nn.startInstant.nn.orElse(null)).let: instant =>
        Instant.of[Unix](instant.toEpochMilli)
      . or(now())

    val daemon: Api.Daemon =
      Api.Daemon(handle.pid, version, started, Runs.directory.let(_.encode).or(t""))

    def service(entry: (Text, Int)): Api.Service = Api.Service(entry(0), entry(1))

    // The MCP server's own service records its port where it lives, outside this module.
    val mcp: List[Api.Service] = McpServer.serving.lay(Nil: List[Api.Service]) { port => List(Api.Service(t"mcp", port)) }
    val services: List[Api.Service] = Server.services.map(service) + mcp

    val runs: List[Api.ActiveRun] = Journal.active.map: run =>
      val active: List[Api.ActiveTest] =
        Server.model(run.id).lay(Nil: List[Api.ActiveTest]): model =>
          model.state().active.map { ref => Api.ActiveTest(this.ref(ref, run.current.or(t""))) }

      Api.ActiveRun
        ( summary(run), run.current, run.record.suites.size, run.record.scheduled.size, active,
          Server.forked(run.id) )

    val worker: Optional[Api.WorkerSession] =
      Journal.active.seek(_.record.invoker == RunRecord.remote).let: run =>
        Api.WorkerSession(run.record.client, run.current)

    Api.Processes(daemon, services, runs, worker)
