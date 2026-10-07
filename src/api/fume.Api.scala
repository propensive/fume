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

import calendars.gregorianCalendar
import jacinta.memo

// The JSON an agent sees: the types the MCP server's tools and resources answer with, each
// field described by its `@memo`, from which the JSON Schema the server publishes is derived
// — so the documentation an agent reads and the shape it receives are one and the same. Every
// type is flat data (texts, numbers, instants, lists, optionals), and every vocabulary is a
// text whose words the memo states, as Probably's own wire events spell them.
object Api:
  // Instants travel as ISO 8601 text in UTC, which an agent reads unaided; a schema calls them
  // date-time strings.
  given instantEncodable: (Instant over Unix) is Json.Encodable =
    Json.Encodable(() => Morphology.Str): instant => (instant in tz"UTC").show.in[Json]

  given instantSchematic: (Instant over Unix) is Schematic over JsonSchema =
    () => JsonSchema.String(format = JsonSchema.Format.DateTime)

  // What a tool reports when it cannot answer: an unknown run or suite, or results it does
  // not have. The message is the whole explanation.
  case class Error(message: Text) extends Exception(message.s)

  case class Totals
    ( @memo(t"tests which passed, including measurements which completed") passed: Int,
      @memo(t"tests which failed, threw, or had a failing check") failed: Int,
      @memo(t"aspirational tests which passed") aspirePassed: Int,
      @memo(t"aspirational tests which failed, which does not fail the run") aspireFailed: Int )

  case class RunSummary
    ( @memo(t"the run's id, stable across daemon restarts; `last` names the newest run in any tool") id: Text,
      @memo(t"the working directory `fume` was invoked from") workspace: Text,
      @memo(t"who launched the run: `human`, `claude`, `codex`, or `remote` for another fume") invoker: Text,
      @memo(t"the machine the suites ran on") machine: Text,
      @memo(t"when the run started, ISO 8601 in UTC") started: Instant over Unix,
      @memo(t"when the run finished; absent while it is in flight") finished: Optional[Instant over Unix],
      @memo(t"`passed`, `failed` or `aborted`; absent while the run is in flight") outcome: Optional[Text],
      @memo(t"the run's length in milliseconds, once finished") duration: Optional[Long],
      @memo(t"the selection terms forwarded to each suite") selection: List[Text],
      @memo(t"how many suites the run scheduled") suites: Int,
      @memo(t"the run's totals, once finished") totals: Optional[Totals] )

  case class SuiteSummary
    ( @memo(t"the suite's class name") suite: Text,
      @memo(t"whether the suite passed") passed: Boolean,
      @memo(t"when the suite started") started: Instant over Unix,
      @memo(t"when the suite finished") finished: Instant over Unix,
      @memo(t"the suite's totals, when it streamed its events") totals: Optional[Totals],
      @memo(t"whether the suite printed anything outside its report; see the `captured` tool") captured: Boolean )

  case class RunDetail
    ( @memo(t"the run in summary") summary: RunSummary,
      @memo(t"the classpath the suites were loaded from") classpath: List[Text],
      @memo(t"the suites the run scheduled, in order") scheduled: List[Text],
      @memo(t"each suite that has run so far") suites: List[SuiteSummary],
      @memo(t"the suite running now, if any") current: Optional[Text],
      @memo(t"whether per-test results can be served: `available`, `partial` (some suites could not stream), `incompatible` (the suites' event schema differs from this fume's), or `none`") results: Text )

  case class TestRef
    ( @memo(t"the test's 6-hex id, a selection term for `fume run`; empty for a suite") id: Text,
      @memo(t"the test's name") name: Text,
      @memo(t"the test's moniker, a selection term, when it declares one") moniker: Optional[Text],
      @memo(t"the path of names or monikers from the root suite to the test") path: List[Text],
      @memo(t"the suite class the test was run under") suite: Text,
      @memo(t"the source file declaring the test") file: Text,
      @memo(t"the line declaring the test") line: Int )

  case class Coordinate
    ( @memo(t"the axis's label") axis: Text,
      @memo(t"the axis's value for this cell, as text") value: Text )

  case class Completion
    ( @memo(t"the cell's coordinates; empty for a test without axes") coordinates: List[Coordinate],
      @memo(t"`pass`, `fail`, `throws`, `check-throws`, `aspire-pass` or `aspire-fail`") outcome: Text,
      @memo(t"how long the cell took, in nanoseconds") nanoseconds: Long )

  case class Benchmark
    ( @memo(t"the cell's coordinates") coordinates: List[Coordinate],
      @memo(t"the time measured, in nanoseconds") nanoseconds: Long,
      @memo(t"operations timed") iterations: Long,
      @memo(t"measurement runs") runs: Int,
      @memo(t"mean time per operation, in nanoseconds") mean: Double,
      @memo(t"fastest run, in nanoseconds per operation") min: Double,
      @memo(t"slowest run, in nanoseconds per operation") max: Double,
      @memo(t"standard deviation, in nanoseconds") sd: Double,
      @memo(t"confidence, as a percentage") confidence: Int,
      @memo(t"the size of one operation, when declared") operationSize: Optional[Text],
      @memo(t"the operation rate, when derived") operationRate: Optional[Text],
      @memo(t"bytes allocated per operation, when measured") allocation: Optional[Long] )

  case class Strain
    ( @memo(t"the cell's coordinates") coordinates: List[Coordinate],
      @memo(t"concurrent workers") concurrency: Int,
      @memo(t"operations completed") operations: Long,
      @memo(t"the time measured, in nanoseconds") nanoseconds: Long,
      @memo(t"bytes allocated") allocation: Long,
      @memo(t"peak heap, in bytes") peakHeap: Long,
      @memo(t"bytes retained after the measurement") retained: Long,
      @memo(t"garbage collections during the measurement") gcCount: Long,
      @memo(t"time spent in garbage collection, in nanoseconds") gcTime: Long,
      @memo(t"median latency, in nanoseconds") p50: Optional[Long],
      @memo(t"90th percentile latency, in nanoseconds") p90: Optional[Long],
      @memo(t"99th percentile latency, in nanoseconds") p99: Optional[Long],
      @memo(t"99.9th percentile latency, in nanoseconds") p999: Optional[Long],
      @memo(t"the fraction of operations within the latency target, when one was declared") compliance: Optional[Double],
      @memo(t"whether the throughput was sustained at this concurrency") sustained: Boolean )

  case class Hotspot
    ( @memo(t"the class of the hot method") className: Text,
      @memo(t"the hot method") method: Text,
      @memo(t"execution samples landing in it") samples: Long )

  case class TestResult
    ( @memo(t"the test") ref: TestRef,
      @memo(t"`check`, `bench`, `stress` or `profile`") kind: Text,
      @memo(t"the test's collective status: `pass`, `fail`, `throws`, `check-throws`, `aspire-pass`, `aspire-fail`, `mixed` (its cells disagree), or `bench`, `stress`, `profile` for a measurement") status: Text,
      @memo(t"each cell's outcome, for a `check`") completions: List[Completion],
      @memo(t"each cell's benchmark record, for a `bench`") benchmarks: List[Benchmark],
      @memo(t"each concurrency level's record, for a `stress`") strains: List[Strain],
      @memo(t"the hot methods, for a `profile`") hotspots: List[Hotspot],
      @memo(t"the command which reruns exactly this test") rerun: Text )

  case class Frame
    ( @memo(t"the frame's class") className: Text,
      @memo(t"the frame's method") method: Text,
      @memo(t"the frame's source file") file: Text,
      @memo(t"the frame's line, when known") line: Optional[Int] )

  case class TraceComponent
    ( @memo(t"the exception's class") className: Text,
      @memo(t"the exception's message") message: Text,
      @memo(t"the stack frames, outermost first") frames: List[Frame] )

  case class Trace
    ( @memo(t"the chain of causes, the thrown exception first") components: List[TraceComponent] )

  case class Capture
    ( @memo(t"the captured value's label") label: Text,
      @memo(t"the captured value, rendered") value: Text )

  case class Comparison
    ( @memo(t"the row's depth in the structural comparison") depth: Int,
      @memo(t"the field or element compared; empty at the root") label: Text,
      @memo(t"`same`, `different`, or `collation` for a structure whose parts follow") kind: Text,
      @memo(t"the expected side") left: Text,
      @memo(t"the found side") right: Text,
      @memo(t"how they differ, or the structure's type name for a collation") difference: Optional[Text] )

  case class Failure
    ( @memo(t"the failing test") ref: TestRef,
      @memo(t"`fail`, `throws`, `check-throws`, `mixed` or `aspire-fail`") status: Text,
      @memo(t"the failure's message, when the test gave one") message: Optional[Text],
      @memo(t"the stack trace, when the test threw") trace: Optional[Trace],
      @memo(t"values the test captured for its report") captures: List[Capture],
      @memo(t"the expected value, rendered, when the test compared") expected: Optional[Text],
      @memo(t"the found value, rendered, when the test compared") found: Optional[Text],
      @memo(t"the structural comparison, row by row, when the test compared") comparison: List[Comparison],
      @memo(t"the command which reruns exactly this test") rerun: Text )

  case class Fatal
    ( @memo(t"the error which ended a suite") trace: Trace,
      @memo(t"the tests that were running when it did") affected: List[TestRef] )

  case class Failures
    ( @memo(t"the run's id") run: Text,
      @memo(t"every failing test, with its diagnostics") failures: List[Failure],
      @memo(t"errors which ended a suite outright") fatals: List[Fatal],
      @memo(t"whether the selection admitted no test at all") nothingMatched: Boolean )

  case class IndexedTest
    ( @memo(t"the test's 6-hex id; absent when its name is only known at runtime") id: Optional[Text],
      @memo(t"the path of names or monikers from the root suite to the test") path: List[Text],
      @memo(t"the test's name, with `*` for each part computed at runtime") name: Text,
      @memo(t"the test's moniker, when it declares one") moniker: Optional[Text],
      @memo(t"`check`, `bench`, `stress` or `profile`") kind: Text,
      @memo(t"the test's tags") tags: List[Text],
      @memo(t"the suite class declaring the test") suite: Text,
      @memo(t"the source file") file: Text,
      @memo(t"the line") line: Int,
      @memo(t"whether the test is declared over axes, whose cells the index does not know") spread: Boolean,
      @memo(t"whether part of the test's name is computed at runtime") dynamic: Boolean )

  case class IndexedSuite
    ( @memo(t"the suite's class name, as `fume run --suite` takes it") suite: Text,
      @memo(t"whether the classpath's static index covers the suite; a suite it does not is listed only by running it") indexed: Boolean,
      @memo(t"the tests the index declares for the suite") tests: Int )

  case class Service
    ( @memo(t"`dashboard`, `listener` or `mcp`") name: Text,
      @memo(t"the port it serves on") port: Int )

  case class ActiveTest
    ( @memo(t"the test or suite in flight") ref: TestRef )

  case class ActiveRun
    ( @memo(t"the run in summary") summary: RunSummary,
      @memo(t"the suite running now") current: Optional[Text],
      @memo(t"suites finished so far") suitesDone: Int,
      @memo(t"suites scheduled") suitesTotal: Int,
      @memo(t"tests and suites in flight") active: List[ActiveTest],
      @memo(t"the pid of the suite's own JVM, under `--fork`") forked: Optional[Long] )

  case class WorkerSession
    ( @memo(t"the hostname of the controller whose run this daemon is working") controller: Text,
      @memo(t"the suite running now") current: Optional[Text] )

  case class Daemon
    ( @memo(t"the daemon's pid") pid: Long,
      @memo(t"fume's version") version: Text,
      @memo(t"when the daemon started") started: Instant over Unix,
      @memo(t"where runs are persisted") runsDirectory: Text )

  case class Processes
    ( @memo(t"this daemon") daemon: Daemon,
      @memo(t"the services this daemon is serving") services: List[Service],
      @memo(t"runs in flight") runs: List[ActiveRun],
      @memo(t"the run this daemon is working for another machine, if any") worker: Optional[WorkerSession] )

  // The codecs and schemas, derived here — outside capture checking — once, so the server's
  // specification summons instances rather than deriving its own.
  given totalsEncodable: Totals is Json.Encodable = Json.EncodableDerivation.derived
  given runSummaryEncodable: RunSummary is Json.Encodable = Json.EncodableDerivation.derived
  given suiteSummaryEncodable: SuiteSummary is Json.Encodable = Json.EncodableDerivation.derived
  given runDetailEncodable: RunDetail is Json.Encodable = Json.EncodableDerivation.derived
  given testRefEncodable: TestRef is Json.Encodable = Json.EncodableDerivation.derived
  given coordinateEncodable: Coordinate is Json.Encodable = Json.EncodableDerivation.derived
  given completionEncodable: Completion is Json.Encodable = Json.EncodableDerivation.derived
  given benchmarkEncodable: Benchmark is Json.Encodable = Json.EncodableDerivation.derived
  given strainEncodable: Strain is Json.Encodable = Json.EncodableDerivation.derived
  given hotspotEncodable: Hotspot is Json.Encodable = Json.EncodableDerivation.derived
  given testResultEncodable: TestResult is Json.Encodable = Json.EncodableDerivation.derived
  given frameEncodable: Frame is Json.Encodable = Json.EncodableDerivation.derived
  given traceComponentEncodable: TraceComponent is Json.Encodable = Json.EncodableDerivation.derived
  given traceEncodable: Trace is Json.Encodable = Json.EncodableDerivation.derived
  given captureEncodable: Capture is Json.Encodable = Json.EncodableDerivation.derived
  given comparisonEncodable: Comparison is Json.Encodable = Json.EncodableDerivation.derived
  given failureEncodable: Failure is Json.Encodable = Json.EncodableDerivation.derived
  given fatalEncodable: Fatal is Json.Encodable = Json.EncodableDerivation.derived
  given failuresEncodable: Failures is Json.Encodable = Json.EncodableDerivation.derived
  given indexedTestEncodable: IndexedTest is Json.Encodable = Json.EncodableDerivation.derived
  given indexedSuiteEncodable: IndexedSuite is Json.Encodable = Json.EncodableDerivation.derived
  given serviceEncodable: Service is Json.Encodable = Json.EncodableDerivation.derived
  given activeTestEncodable: ActiveTest is Json.Encodable = Json.EncodableDerivation.derived
  given activeRunEncodable: ActiveRun is Json.Encodable = Json.EncodableDerivation.derived
  given workerSessionEncodable: WorkerSession is Json.Encodable = Json.EncodableDerivation.derived
  given daemonEncodable: Daemon is Json.Encodable = Json.EncodableDerivation.derived
  given processesEncodable: Processes is Json.Encodable = Json.EncodableDerivation.derived

  given totalsSchematic: Totals is Schematic over JsonSchema = JsonSchema.derived
  given runSummarySchematic: RunSummary is Schematic over JsonSchema = JsonSchema.derived
  given suiteSummarySchematic: SuiteSummary is Schematic over JsonSchema = JsonSchema.derived
  given runDetailSchematic: RunDetail is Schematic over JsonSchema = JsonSchema.derived
  given testRefSchematic: TestRef is Schematic over JsonSchema = JsonSchema.derived
  given coordinateSchematic: Coordinate is Schematic over JsonSchema = JsonSchema.derived
  given completionSchematic: Completion is Schematic over JsonSchema = JsonSchema.derived
  given benchmarkSchematic: Benchmark is Schematic over JsonSchema = JsonSchema.derived
  given strainSchematic: Strain is Schematic over JsonSchema = JsonSchema.derived
  given hotspotSchematic: Hotspot is Schematic over JsonSchema = JsonSchema.derived
  given testResultSchematic: TestResult is Schematic over JsonSchema = JsonSchema.derived
  given frameSchematic: Frame is Schematic over JsonSchema = JsonSchema.derived
  given traceComponentSchematic: TraceComponent is Schematic over JsonSchema = JsonSchema.derived
  given traceSchematic: Trace is Schematic over JsonSchema = JsonSchema.derived
  given captureSchematic: Capture is Schematic over JsonSchema = JsonSchema.derived
  given comparisonSchematic: Comparison is Schematic over JsonSchema = JsonSchema.derived
  given failureSchematic: Failure is Schematic over JsonSchema = JsonSchema.derived
  given fatalSchematic: Fatal is Schematic over JsonSchema = JsonSchema.derived
  given failuresSchematic: Failures is Schematic over JsonSchema = JsonSchema.derived
  given indexedTestSchematic: IndexedTest is Schematic over JsonSchema = JsonSchema.derived
  given indexedSuiteSchematic: IndexedSuite is Schematic over JsonSchema = JsonSchema.derived
  given serviceSchematic: Service is Schematic over JsonSchema = JsonSchema.derived
  given activeTestSchematic: ActiveTest is Schematic over JsonSchema = JsonSchema.derived
  given activeRunSchematic: ActiveRun is Schematic over JsonSchema = JsonSchema.derived
  given workerSessionSchematic: WorkerSession is Schematic over JsonSchema = JsonSchema.derived
  given daemonSchematic: Daemon is Schematic over JsonSchema = JsonSchema.derived
  given processesSchematic: Processes is Schematic over JsonSchema = JsonSchema.derived

  // The JSON Schema of every type above, one property per type: what `fume://schema` serves,
  // derived from the types themselves, so it cannot disagree with what the tools answer.
  def schema(version: Text): JsonSchema =
    inline def of[value: Schematic over JsonSchema]: JsonSchema = value.schema()

    val types: List[(Text, JsonSchema)] =
      List
        ( t"Totals"        -> of[Totals],
          t"RunSummary"    -> of[RunSummary],
          t"SuiteSummary"  -> of[SuiteSummary],
          t"RunDetail"     -> of[RunDetail],
          t"TestRef"       -> of[TestRef],
          t"Coordinate"    -> of[Coordinate],
          t"Completion"    -> of[Completion],
          t"Benchmark"     -> of[Benchmark],
          t"Strain"        -> of[Strain],
          t"Hotspot"       -> of[Hotspot],
          t"TestResult"    -> of[TestResult],
          t"Frame"         -> of[Frame],
          t"TraceComponent" -> of[TraceComponent],
          t"Trace"         -> of[Trace],
          t"Capture"       -> of[Capture],
          t"Comparison"    -> of[Comparison],
          t"Failure"       -> of[Failure],
          t"Fatal"         -> of[Fatal],
          t"Failures"      -> of[Failures],
          t"IndexedTest"   -> of[IndexedTest],
          t"IndexedSuite"  -> of[IndexedSuite],
          t"Service"       -> of[Service],
          t"ActiveTest"    -> of[ActiveTest],
          t"ActiveRun"     -> of[ActiveRun],
          t"WorkerSession" -> of[WorkerSession],
          t"Daemon"        -> of[Daemon],
          t"Processes"     -> of[Processes] )

    JsonSchema.Object
      ( description = t"The types fume $version answers MCP tool calls with, by name",
        properties  = types.to[Map] )
