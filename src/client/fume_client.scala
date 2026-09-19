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

import probably.TestEvent

// Explicit, so that it outranks the `Tool` the `soundness.*` wildcard exports (anthology's);
// `standard` is a package-level extension on it.
import pyrocosm.Tool

import backstops.silentBackstop
import executives.completionsExecutive
import interpreters.posixInterpreter
import logging.silentLogging
import systems.javaBaseSystem
import threading.platformThreading

// Fume as a Pyrocosm tool: `about`, `install`, `quit` and `--version` come from `Tool`, as
// does its configuration — every `Setting` below is read from its command-line flag first,
// then a `fume.`-prefixed system property, a `FUME_`-prefixed environment variable, the
// project's `.pyrocosm/fume/config.tel` and the user's `~/.config/fume/config.tel` — and the
// dashboard the daemon serves when a config says `serve`.
val Fume: Tool =
  Tool
    ( t"fume",
      prose = t"Fume is the test runner for the Soundness ecosystem: it runs Probably tests "
            + t"and Sedentary benchmarks from a prebuilt classpath, discovering suites "
            + t"through the META-INF/services/probably.Suite index.",
      web   = fume.Dashboard.web )

// The exit statuses fume can terminate with, declared as objects (a `Status` must be an
// `object`, not a `val` — soundness#1811) so that the precise union of an `execute` block's
// result type documents them: `Status.Admissible` reifies the union, and the manpage's EXIT
// STATUS section is generated from it. Codes 0, 1 and 2 deliberately mirror `probably.Suite`'s
// own exit protocol (0 = passed, 1 = test failures, 2 = the suite threw).
object TestsFailed extends Status(1, t"one or more tests failed")
object UsageError extends Status(2, t"the command line was not understood")
object NoClasspath extends Status(3, t"no --classpath was specified, or an entry was unreadable")
object NoSuites extends Status(4, t"no test suites were found on the classpath")
object Unimplemented extends Status(10, t"this subcommand is not yet implemented")

// Fume's user interface, in one namespace: its subcommands, flags and settings. The object
// exists so each can carry its NATURAL name — `ui.Test`, `ui.Suite`, `ui.Classpath`,
// `ui.List` — without a package-level `val` shadowing the Soundness export of
// the same name for the whole `fume` package (which previously forced `TestF`-style suffixes
// and a fully-qualified `hellenism.Classpath`). Within this object's own body the members DO
// shadow those exports, so the declarations avoid the shadowed names: aliases are written
// `proscenium.List('c')` rather than `List('c')`.
object ui:
  val Run = Subcommand("run", "run the tests and benchmarks admitted by the selection")
  val List = Subcommand("list", "list the tests and benchmarks on the classpath")
  val Watch = Subcommand("watch", "watch the classpath jars and rerun tests on change")
  val Serve = Subcommand("serve", "serve the dashboard of runs on the web until Ctrl+C")

  // The classpath holding compiled test suites. Suites on it are discovered ONLY through the
  // `META-INF/services/probably.Suite` index which the beneficence compiler plugin writes;
  // fume never scans for classes. Entries are loaded in a child-FIRST `URLClassLoader` whose
  // parent is the PLATFORM classloader, so the user's jars may contain a different version of
  // the very Soundness classes fume itself is built from without either side seeing the
  // other's: fume's own classes live in the Burdock/Ethereal launcher's sibling loader (or the
  // system loader in a pre-release build), which the platform parent cannot reach. For the
  // same reason, fume must always discover its OWN classpath through the thread-context
  // classloader, never `java.class.path` or the system classloader, which under Burdock see
  // only the slim pre-repackage jar.
  //
  // A `Setting`, not a `Flag`, so a project can fix its classpath once in
  // `.pyrocosm/fume/config.tel` (one `classpath` entry per line — see `pyrocosm.Tool`) instead of
  // repeating it on every invocation; `-c`/`--classpath` and the `fume.classpath`
  // property/`FUME_CLASSPATH` variable override it, all as ':'-separated entries.
  val Classpath =
    Setting[LocalClasspath]
      ( t"classpath",
        t"a jar or ':'-separated classpath holding compiled test suites",
        aliases = proscenium.List('c') )

  val Suite =
    Flag[Text]
      ( "suite",
        true,
        proscenium.List('s'),
        "run only the named suite, a class from META-INF/services/probably.Suite; every " +
          "discovered suite runs when unspecified" )

  // The kind restriction, one switch per kind. Each present flag adds its kind as a `kind:`
  // selection term for `probably.Selection.parse`, so combinations union (`--test --bench`
  // runs both), exactly as Probably's own `kind:` terms do; none present runs every kind.
  val Test = Flag[Unit]("test", false, Nil, "run ordinary unit tests; all kinds run when none is given")
  val Bench = Flag[Unit]("bench", false, Nil, "run benchmarks; all kinds run when none is given")
  val Stress = Flag[Unit]("stress", false, Nil, "run stress tests; all kinds run when none is given")
  val Profile = Flag[Unit]("profile", false, Nil, "run profiles; all kinds run when none is given")

  // The flag spellings of the selection grammar. Each lowers (`Selection.lower`) to the
  // positional wire term the suite parses — `--kind bench,stress` to `kind:bench kind:stress`,
  // `--tag slow` to `tag:slow`, `--axis N=4,8` to `N=4,8`, `--exclude tag:slow` to
  // `not:tag:slow` — so suites see one grammar however a selection is spelt, while each flag
  // gives tab-completion a clear anchor: its operand completes to the kinds, the tags on the
  // classpath, or the axes and values of the tests the other arguments identify.
  val Kind =
    Setting[Text]
      ( t"kind",
        t"run only these kinds, comma-separated: test, bench, stress, profile",
        aliases = proscenium.List('k') )

  val Tag =
    Flag[Repeated]
      ( "tag",
        true,
        proscenium.List('t'),
        "run only tests carrying any of these comma-separated tags; a repeated --tag must " +
          "also match" )

  val Axis =
    Flag[Repeated]
      ( "axis",
        true,
        proscenium.List('a'),
        "run only the cells satisfying an axis constraint such as N=4,8, N=4..64 or N=4..; " +
          "repeatable" )

  val Exclude =
    Flag[Repeated]
      ( "exclude",
        true,
        proscenium.List('x'),
        "exclude whatever this selection term would admit: a test, kind:bench, tag:slow or " +
          "N=4; repeatable" )

  val Axes = Flag[Unit]("axes", false, Nil, "with list: show each test's tags and axes")

  val Tags =
    Flag[Unit]("tags", false, Nil, "with list: show the distinct tags and how many tests carry each")

  val Fork = Flag[Unit]("fork", false, Nil, "run each suite in a separate JVM")

  // Single-valued options are `Setting`s rather than `Flag`s, so each is also configurable
  // through the `Configurator` cascade `Fume.standard` provides; the camelCase name derives the
  // `--fail-fast` flag, the `fume.fail.fast` system property and the `FUME_FAIL_FAST`
  // environment variable. Like a flag, a setting must be read outside `execute` to register
  // for tab-completion.
  val FailFast = Setting[Boolean](t"failFast", t"stop after the first failing test")

  // The port `fume serve` listens on; `--port`, the `fume.port` property, `FUME_PORT` and
  // `port` in either config file all reach it — and `Tool` reads the same keyword for the
  // dashboard it serves from the daemon when a config says `serve`.
  val Port = Setting[Text](t"port", t"the port on which `fume serve` serves the dashboard")

  // The load gate: hold the run back until the system's 1-minute load average has fallen
  // below this value. A `Setting`, so a benchmarking workspace can fix a house threshold in
  // `.pyrocosm/fume/config.tel` (`maxLoad 0.5`) and still override it per invocation;
  // `--max-load`, the `fume.max.load` property and `FUME_MAX_LOAD` all reach it.
  //
  // `Setting[Text]`, decoded where it is read: distillate's `Double is Decodable in Text`
  // demands an ambient `Tactic`, and declaring a fume-local given for a type as common as
  // `Double` would compete with it at every other decode site in the package.
  val MaxLoad =
    Setting[Text]
      ( t"maxLoad",
        t"wait until the system load average falls below this value before running" )

  // The run-length multiplier, forwarded to each suite as probably's `--scale=<factor>`: every
  // declared benchmark, stress-test and profile target duration is multiplied by it, so 2
  // runs each measurement twice as long and 0.25 a quarter as long. Geometric, so the
  // durations keep their proportions to one another; the suite's own source is untouched.
  //
  // It reaches the suite through the selection arguments, which is fume's only channel to a
  // suite. A suite built against a Soundness that predates `--scale` reads it as an axis
  // constraint on an axis named `--scale`, which no test has, and a constraint on an absent
  // axis admits everything — so an old suite silently ignores the flag and runs normally,
  // rather than losing its selection. Sent only when asked for, all the same.
  val DurationScale =
    Setting[Text]
      ( t"durationScale",
        t"multiply every benchmark, stress and profile duration by this factor" )

  // The run-length budget, from which the multiplier above is DERIVED rather than stated: a
  // schedule pre-pass sums the expected measuring time of every admitted benchmark, stress
  // test and profile, and the factor that fits that sum into the budget is forwarded as
  // `--scale=`. Seconds when bare (`90`), or suffixed (`90s`, `10m`, `2h`). Only measurement
  // time is budgeted — staging, compilation and unit tests are on top — so the run completes
  // in APPROXIMATELY the asked-for time, not exactly. Mutually exclusive with
  // `--duration-scale`, which wins: a stated factor is deterministic where a derived one
  // depends on what the selection admits.
  val Target =
    Setting[Text]
      ( t"target",
        t"scale benchmark, stress and profile durations to fit this total running time" )

// There is no `Boolean is Decodable in Text` given in distillate, so `Setting[Boolean]` reads
// (from a flag operand, a system property or an environment variable) decode here: `true`,
// `yes` and `on` — case-insensitively — are true, and anything else is false.
given decodable: (Boolean is Decodable in Text) = value =>
  value.lower == t"true" || value.lower == t"yes" || value.lower == t"on"


// Reads all four kind switches — unconditionally, so that every one of them registers for
// tab-completion (see the note in `runClient`) — and yields the `kind:` terms they select.
private def selectedKinds(using Cli, Interpreter): List[Text] =
  val test: Boolean = ui.Test().present
  val bench: Boolean = ui.Bench().present
  val stress: Boolean = ui.Stress().present
  val profile: Boolean = ui.Profile().present

  val pairs: List[(Boolean, Text)] =
    List((test, t"test"), (bench, t"bench"), (stress, t"stress"), (profile, t"profile"))

  pairs.filter(_(0)).map(_(1))

// The client's command dispatch. The `@main` entry point and burdock's `externalize` wrapper
// live alone in the `launcher` module (`src/launcher/fume_launcher.scala`), which depends on
// this module as a PUBLISHED Maven artifact — so `externalize` records its Central jar hash and
// the repackager turns it into an on-demand `Burdock-Require` download instead of inlining it.
def runClient(): Unit =
  // `Fume.standard` handles the standard subcommands (`about`, `install`, `quit`) and
  // `--version` first, and provides the full configuration cascade every `Setting` read below
  // resolves through: the command-line flag always wins (handled structurally by `Setting`),
  // then `fume.*` system properties, `FUME_*` environment variables, the project's
  // `.pyrocosm/fume/config.tel` — resolved from the INVOCATION's working directory (each
  // daemon client has its own), never the daemon process's — and the user's own.
  cli:
    Fume.standard:
      // The run command's whole body, shared by `fume run …` and the BARE `fume …` (running is
      // the default when no subcommand is given).
      def runSelection(rest: List[Argument]) =
        val classpath: Optional[LocalClasspath] = classpathSetting()
        val suite: Prospective[Text] = suiteFlag(classpath)
        val kinds: List[Text] = selectedKinds + kindSetting().lay(Nil: List[Text])(List(_))
        val words: List[Text] = Selection.words(rest.map { (argument: Argument) => argument() })
        val tags: Prospective[Repeated] = tagFlag(classpath)
        val axes: Prospective[Repeated] = axisFlag(classpath, words)
        val excludes: Prospective[Repeated] = excludeFlag(classpath, words)
        val failFast: Boolean = ui.FailFast().or(false)
        val maxLoad: Optional[Text] = ui.MaxLoad()
        val durationScale: Optional[Text] = ui.DurationScale()
        val target: Optional[Text] = ui.Target()
        val fork: Boolean = ui.Fork().present
        val terms: List[Text] = selectionTerms(rest)

        completeTerms(classpath, rest)

        execute:
          given Stdio = summon[Invocation].stdio
          classpath match
            case classpath: LocalClasspath =>
              val suites: List[Text] = selectSuites(classpath, suite())

              if suites.nil then
                Render.announce(t"no test suites were found on the classpath")
                NoSuites
              else
                val selectionArgs: List[Text] =
                  Selection.lower
                    ( kinds,
                      tags().lay(Nil: List[Text])(_.values),
                      axes().lay(Nil: List[Text])(_.values),
                      excludes().lay(Nil: List[Text])(_.values),
                      terms )

                // A positive factor becomes probably's `--scale=<factor>`; anything else is
                // reported and dropped, like `--max-load` above, so that a mistyped multiplier
                // costs a warning rather than a run. The factor is checked here but forwarded
                // VERBATIM — the text the user wrote is what the suite parses, so no rounding
                // or reformatting can creep in on the way.
                val scaleTerm: Optional[Text] =
                  durationScale.lay(Unset: Optional[Text]): text =>
                    if safely(text.as[Double]).lay(false)(_ > 0.0) then t"--scale=$text" else Unset

                if durationScale.present && scaleTerm.absent
                then Render.announce(t"--duration-scale must be a positive number; ignoring it")

                if durationScale.present && target.present
                then Render.announce
                       (t"--target and --duration-scale are mutually exclusive; using --duration-scale")

                val width: Int = terminalWidth()
                val terse: Boolean = fume.GithubActions.terse
                val tty: Boolean = summon[DaemonService[?]].cliInput == ethereal.Stdin.Terminal

                import probates.cancelProbate
                import denominative.dysasymptotics.linearSize

                // The board's frontend drives the terminal through the invocation's console, a
                // tracked capability sealed here for the run, as flame does for its commands.
                given Console = scala.caps.unsafe.unsafeAssumePure(summon[Cli])

                // Ctrl+C at the client arrives here as a trapped SIGINT: the current suite's
                // event consumption stops, the partial report renders, and no further suite
                // starts. (The suite's threads — and any measurement JVMs a staged benchmark
                // has spawned — are cancelled, not awaited.)
                val aborted: java.util.concurrent.atomic.AtomicBoolean =
                  java.util.concurrent.atomic.AtomicBoolean(false)

                trap:
                  case Interrupt.Int =>
                    aborted.set(true)
                    SignalResponse.Accept

                // ONE model and ONE board for the whole run: every suite's events fold into the
                // same model, so the progress gauge counts every scheduled test of the run and
                // the report renders once, at the end, grouped by suite. (`--fork` runs suites
                // as separate processes whose events never reach fume, so it has no board.)
                //
                // The live board, shown in the terminal by Pyrocosm's frontend, is skipped in
                // terse mode (CI, Claude Code), where events fold quietly, and for a piped
                // invocation (the launcher reports whether the client is on a terminal), which
                // renders once at the end instead. It is built whenever someone can see it: on
                // this terminal, or through the dashboard `fume serve` is serving.
                val model = Model()
                val shown: Boolean = !terse && tty && !fork

                val title: Text = suites match
                  case List(only) => only
                  case _          => t"${suites.size} suites"

                // The run's progress, forecast from the last run of this classpath where it can
                // be: the board's status line, ticking as the suites go by.
                val progress: Progress = Progress(suites, Forecasts.load(classpath()))

                val board: Optional[fume.Board] =
                  if (shown || Server.serving) && !fork then fume.Board(model, title, progress) else Unset

                // The listing pre-pass over every suite: each runs with `--list` on the event
                // protocol, emitting one `TestScheduled` per admitted test — with its real ref,
                // so paths group correctly whatever characters the names contain. Listing still
                // RUNS every suite body (only the assertions are skipped), so it is done only
                // when a `--target` budget has to be priced from the schedule — never merely to
                // seed the board, whose rows appear as the suites stream. The schedule also seeds
                // the model, and decides — once, for the whole classpath — whether the suites
                // can stream at all: `false` sends the run down the legacy path without a board.
                //
                // The suites stream through ONE classloader, here and in the run below, when
                // their Probably allows it (`EventStream.reentrant`): a suite memoizing its
                // runner on first use — and a suite that INVOKES another top-level suite as a
                // nested one (as proscenium's does) memoizing that suite's runner too, so a later
                // invocation in the same loader would report nothing — is what a fresh loader per
                // suite guarded against, and older suites still get one.
                val wantsBudget: Boolean = target.present && scaleTerm.absent

                val loader: Classloader = classpath.classloader()

                val shared: Optional[Classloader] =
                  if EventStream.reentrant(loader) then loader else Unset

                val schedule: scala.collection.mutable.ListBuffer[TestEvent.TestScheduled] =
                  scala.collection.mutable.ListBuffer()

                val listed: Optional[Boolean] =
                  if fork || !wantsBudget then Unset else
                    Render.announce(t"pricing the budget: listing ${suites.size} suites")

                    def collect(event: TestEvent): Unit =
                      model.handle(event)

                      event match
                        case scheduled: TestEvent.TestScheduled =>
                          schedule.append(scheduled)
                          model.listed()

                        case _ =>
                          ()

                    // The abort thunk is passed explicitly: the DEFAULT argument's root
                    // capability cannot flow into `safely`'s enclosing function under capture
                    // checking.
                    def recur(remaining: List[Text]): Boolean = remaining match
                      case head :: tail =>
                        Render.announce(t"  listing $head")
                        model.enter(head)

                        val outcome: Optional[EventStream.Outcome] =
                          safely:
                            EventStream.stream(classpath, head, t"--list" :: selectionArgs, shared)
                              (collect(_), () => false)

                        outcome match
                          case EventStream.Outcome.Completed(_) => recur(tail)
                          case _                                => false

                      case _ =>
                        true

                    recur(suites)

                // With a budget (and no explicit factor), the schedule prices the selection and
                // the factor is derived. A budget that buys nothing — no timed tests admitted,
                // or no suite able to stream its schedule — changes nothing.
                val budgetTerm: Optional[Text] =
                  if !wantsBudget then Unset else
                    target.let(Budget.parse(_)).lay(Unset: Optional[Text]): nanos =>
                      val priced: Long = Budget.expected(schedule.toList.to(List))

                      if priced == 0L then
                        Render.announce
                          (t"the selection has no timed measurements; ignoring --target")
                        Unset
                      else
                        val factor: Text = Budget.factor(nanos, priced)
                        Render.announce:
                          t"the selection expects ${Budget.show(priced)} of measurement; scaling by $factor to fit ${Budget.show(nanos)}"
                        t"--scale=$factor"

                if target.present && scaleTerm.absent && budgetTerm.absent && target.let(Budget.parse(_)).absent
                then Render.announce(t"--target must be a positive duration such as 90, 45s or 10m; ignoring it")

                val scaleTerms: List[Text] =
                  scaleTerm.or(budgetTerm).lay(Nil: List[Text])(List(_))

                // One worker behind the traversal, when the suites' Probably queues (see
                // `EventStream.queued`): each suite's code between tests runs once, its rows
                // appear as it is traversed, and declaration order is kept.
                val workerTerms: List[Text] =
                  if !fork && EventStream.queued(loader) then List(t"--workers=1") else Nil

                val args: List[Text] = scaleTerms + workerTerms + selectionArgs

                // The load gate, if `--max-load` asked for one. It is entered AFTER the signal
                // trap above, so Ctrl+C during the wait sets `aborted` and the run below then
                // finishes immediately without starting a suite. A value that does not parse
                // (or is not positive) is reported and ignored rather than failing the run: the
                // gate is an optimisation for measurement quality, never a precondition.
                val threshold: Optional[Double] =
                  maxLoad.lay(Unset: Optional[Double]): text =>
                    safely(text.as[Double]).lay(Unset: Optional[Double]): value =>
                      if value > 0.0 then value else Unset

                if maxLoad.present && threshold.absent
                then Render.announce(t"--max-load must be a positive number; not waiting")

                threshold.let(Load.settle(_, width, tty, aborted)).unit

                // The run is entered in the daemon's journal for its whole duration: it moves
                // to the completed list at the end, whichever way it ends.
                val journalId: Int =
                  Journal.start
                    ( summon[DaemonService[?]].pid.value.show,
                      Invoker.detect,
                      classpath(),
                      args,
                      suites )

                board.let { board => Server.attach(journalId, board) }

                // The frontend holds the run's monitor and its terminal-error tactic, which
                // outlive it; it is vouched pure so it can be held and stopped from here. The
                // board opens a moment into the run, so a run that finishes at once never
                // flashes the alternate screen, and stays up until the LAST suite has streamed
                // (`model.finish()` below). Leaving it (Escape, Ctrl+C or Ctrl+D) before then
                // aborts the run.
                val frontend: Optional[pyrocosm.TerminalFrontend] = if !shown then Unset else board.let: _ =>
                  import strategies.throwUnsafely
                  import fume.Figures.measurable
                  import tableStyles.thickTableStyle
                  import palettes.solarizedDarkGaugePalette
                  scala.caps.unsafe.unsafeAssumePure
                    (pyrocosm.TerminalFrontend(Occupancy.Fullscreen))

                // Fulfilled when the board has closed and the terminal is restored, so the
                // report below prints onto the ordinary screen.
                val closed: Promise[Unit] = Promise()

                // The board's own repaint task: ten times a second at most, whenever events have
                // marked it, until the run is over. The event consumer only marks.
                board.let: board =>
                  async:
                    var tick: Int = 0
                    while !model.finished do
                      board.repaint(force = tick%10 == 0)
                      tick += 1
                      snooze(0.1*Second)

                  ()

                frontend.let: frontend =>
                  async:
                    try
                      snooze(0.3*Second)
                      board.let: board =>
                        if !model.finished then
                          frontend.run(board.interface):
                            case pyrocosm.Event.Closed => if !model.finished then aborted.set(true)
                            case _                     => ()
                    finally closed.offer(())

                // A handler failure (the model or the board threw) per suite, reported once the
                // board has left the alternate screen so the trace is not lost with it.
                val consumerFailures: scala.collection.mutable.ListBuffer[(Text, Throwable)] =
                  scala.collection.mutable.ListBuffer()

                // The LEGACY loop: each suite runs through `Suite#invoke` in-process (or, with
                // `--fork`, in its own JVM) and renders its own report, so only the verdict —
                // its exit status — reaches fume. Also the tail of an event run whose classpath
                // turned out unable to stream.
                def legacyRun(remaining: List[Text], failures: Int, ran: Int): (Int, Int) =
                  remaining match
                    case _ if aborted.get =>
                      (failures, ran)

                    case head :: tail =>
                      Render.announce(t"running $head")
                      Journal.began(journalId, head)
                      progress.begin(head)
                      val suiteStarted: Long = java.lang.System.currentTimeMillis
                      val passed: Boolean = invokeSuite(classpath, head, args, fork) == Exit.Ok
                      Journal.record(journalId, head, passed, Unset, suiteStarted)
                      progress.end(head)
                      Render.announce(if passed then t"$head: passed" else t"$head: FAILED")
                      val failures2 = if passed then failures else failures + 1

                      if !passed && failFast then (failures2, ran + 1)
                      else legacyRun(tail, failures2, ran + 1)

                    case _ =>
                      (failures, ran)

                // The EVENT loop: each suite streams, from its own classloader, into the one
                // model. A suite's own totals — for the journal, and for the empty-selection
                // rule — are the difference between the document before and after it ran.
                // Yields the suites left unrun when the classpath proves unable to stream (only
                // ever on the first suite, when no listing pass decided it earlier), for the
                // legacy loop.
                def eventRun(remaining: List[Text], failures: Int, ran: Int)
                :   (Int, Int, List[Text]) =

                  remaining match
                    case _ if aborted.get =>
                      (failures, ran, Nil: List[Text])

                    case head :: tail =>
                      // The board owns the screen while it is up; a line printed beneath it
                      // would be lost when the screen is restored.
                      if board.absent then Render.announce(t"running $head")
                      Journal.began(journalId, head)
                      progress.begin(head)
                      val suiteStarted: Long = java.lang.System.currentTimeMillis
                      val before: Model.State = model.state()
                      val beforeTotals: Doc.Totals = Documenting.totals(before)
                      model.enter(head)

                      val outcome: Optional[EventStream.Outcome] =
                        EventStream.stream(classpath, head, args, shared)
                          ( { event =>
                                model.handle(event)
                                board.let(_.refresh()) },
                            () => aborted.get )

                      def next(passed: Boolean, totals: Optional[Doc.Totals]): (Int, Int, List[Text]) =
                        Journal.record(journalId, head, passed, totals, suiteStarted)
                        progress.end(head)
                        val failures2 = if passed then failures else failures + 1

                        if !passed && failFast then (failures2, ran + 1, Nil: List[Text])
                        else eventRun(tail, failures2, ran + 1)

                      outcome match
                        case EventStream.Outcome.Completed(exit) =>
                          val after: Model.State = model.state()
                          val suiteTotals: Doc.Totals = Documenting.totals(after) - beforeTotals
                          val suiteFatal: Boolean = after.fatals.size > before.fatals.size

                          // A suite reports failure (exit 1) when NOTHING was admitted: right
                          // when it is invoked alone, wrong when fume fans a kind filter
                          // (`--bench`) across every suite on the classpath — a suite with no
                          // benchmarks is not a failing suite.
                          val emptySelection: Boolean =
                            exit == 1 && suiteTotals.total == 0 && !suiteFatal

                          next(exit == 0 || emptySelection, suiteTotals)

                        case EventStream.Outcome.Failed(error) =>
                          consumerFailures.append((head, error))
                          next(false, Unset)

                        case EventStream.Outcome.Incompatible(theirs, ours) =>
                          Render.announce
                            (t"the classpath was built against an incompatible Soundness; using the legacy run")
                          Render.announce(t"  the suites' event schema is $theirs")
                          Render.announce(t"  fume's is                   $ours")
                          (failures, ran, remaining)

                        case _ =>
                          Render.announce(t"the classpath predates event streaming; using the legacy run")
                          (failures, ran, remaining)

                    case _ =>
                      (failures, ran, Nil: List[Text])

                // The streaming decision is made ONCE per classpath: by the listing pass when it
                // ran, otherwise by the first suite of the run. Whichever way, the event suites'
                // report renders first and the tail runs legacy.
                val result: (Int, Int, Optional[Doc.Totals]) =
                  if fork then
                    val (failures, ran) = legacyRun(suites, 0, 0)
                    (failures, ran, Unset)
                  else if listed == false then
                    Render.announce
                      (t"the classpath cannot stream test events; using the legacy run")
                    val (failures, ran) = legacyRun(suites, 0, 0)
                    (failures, ran, Unset)
                  else
                    val (failures, ran, rest) = eventRun(suites, 0, 0)
                    model.finish()

                    frontend.let: frontend =>
                      frontend.stop()
                      safely(closed.attend())

                    val document = Documenting.document(model.state())

                    // The dashboard keeps a finished run's report; a run nobody is serving has
                    // no need of one more full paint of the board.
                    board.let: board =>
                      if Server.serving then
                        board.refresh(force = true)
                        Server.detach(journalId, title, Blocks.document(document, board.figures))
                      else Server.detach(journalId, title, Nil)

                    consumerFailures.each: (suite, error) =>
                      // Written to a file first: the terminal may be mid-repaint, and a trace
                      // on stderr inside the alternate buffer is lost when the board closes.
                      val trace = java.io.StringWriter()
                      error.printStackTrace(java.io.PrintWriter(trace))
                      val path = java.nio.file.Path.of(java.lang.System.getProperty("java.io.tmpdir").nn, "fume-failure.log").nn
                      java.nio.file.Files.writeString(path, trace.toString)
                      Render.announce(t"the event consumer failed while $suite was running; the suite was stopped")
                      Render.announce(t"the stack trace is in ${path.toString.tt}, and follows:")
                      trace.toString.tt.cut(t"\n").each { (line: Text) => Out.println(line) }

                    val totals: Optional[Doc.Totals] =
                      if ran == 0 then Unset else
                        if aborted.get then Render.announce(t"aborted; the partial report follows")
                        Render.suite(document, width, terse)
                        document.totals

                    val (failures2, ran2) =
                      if rest.nil then (failures, ran) else legacyRun(rest, failures, ran)

                    (failures2, ran2, totals)

                val (failures, ran, totals) = result

                val outcome: Journal.Outcome =
                  if aborted.get then Journal.Outcome.Aborted
                  else if failures == 0 then Journal.Outcome.Passed
                  else Journal.Outcome.Failed

                Journal.finish(journalId, outcome, totals)

                // What this run taught about its suites, for the next run's forecast.
                Journal.completed.seek(_.id == journalId).let { run => Forecasts.save(classpath(), run.suites) }

                // The banner renders over the aggregate of every event-run suite; when every
                // suite ran legacy (each rendered its own report already), only the summary
                // line prints.
                totals.let(Render.finale(_, width, terse))

                // `0 of 0 suites passed` would read like a clean run; nothing ran at all. This
                // is the shape of an abort — Ctrl+C at the load gate, or before the first suite
                // began — and of a selection that admitted no suite.
                Render.announce:
                  if ran == 0 then t"no tests were run"
                  else t"${ran - failures} of $ran suites passed"
                if failures == 0 then Exit.Ok else TestsFailed

            case _ =>
              Render.announce(t"at least one --classpath must be specified")
              NoClasspath

      arguments match
        // `fume -<flag>…` — a leading flag that `Fume.standard` did not consume (`--version` is
        // handled there, before this dispatch is reached). This case fires only when the first
        // token is a FLAG (`head` begins with `-`), which cannot be a subcommand.
        //
        // It is matched FIRST so that, when the word being completed is a flag, the subcommand
        // patterns below are never evaluated. Matching a `Subcommand` also SUGGESTS it, and a
        // suggestion at the cursor takes precedence over the flag list (`Completion`'s
        // `cursorSuggestions` wins over `flagSuggestions`) — so trying the subcommands first
        // would leave `fume --ver<TAB>` offering `run`/`list`/`watch`/`serve` and no flags at
        // all. Ordering costs nothing at run time: a subcommand never begins with `-`, so no
        // invocation changes meaning.
        case Argument(head) :: _ if head.starts(t"-") =>
          execute(usage())

        // `fume run [-c CLASSPATH] [-s SUITE] [--test|--bench|--stress|--profile] [--fail-fast]
        // [TERMS…]` — run every test, benchmark, stress test and profile admitted by the
        // selection. The non-flag TERMS are raw Probably selection terms (6-hex-digit ids,
        // monikers, name and path globs, `kind:` terms, and axis constraints such as
        // `parser=jacinta`, `N=4..64` or `'N<32'` — the `<`/`>` forms need shell quoting),
        // forwarded verbatim to each suite with no re-parsing; fume itself only prepends the
        // `kind:` terms derived from the kind switches.
        case ui.Run() :: rest =>
          runSelection(rest)

        // `fume list [-c CLASSPATH] [-s SUITE] [--test|--bench|--stress|--profile] [TERMS…]` —
        // enumerate, without running anything, the tests admitted by the selection, in
        // `probably.Suite`'s `--list` format: `<6-hex-id>  <kind>  <slash/joined/path>`, one per
        // line. With `--axes`, two more columns follow — the tags, and the axes with the values
        // (or bounds) the selection admits — from each suite's streamed schedule; with `--tags`,
        // the distinct tags across the selection with how many tests carry each.
        case ui.List() :: rest =>
          val classpath: Optional[LocalClasspath] = classpathSetting()
          val suite: Prospective[Text] = suiteFlag(classpath)
          val kinds: List[Text] = selectedKinds + kindSetting().lay(Nil: List[Text])(List(_))
          val words: List[Text] = Selection.words(rest.map { (argument: Argument) => argument() })
          val tags: Prospective[Repeated] = tagFlag(classpath)
          val axes: Prospective[Repeated] = axisFlag(classpath, words)
          val excludes: Prospective[Repeated] = excludeFlag(classpath, words)
          val showAxes: Boolean = ui.Axes().present
          val showTags: Boolean = ui.Tags().present
          val fork: Boolean = ui.Fork().present
          val terms: List[Text] = selectionTerms(rest)

          completeTerms(classpath, rest)

          execute:
            given Stdio = summon[Invocation].stdio
            classpath match
              case classpath: LocalClasspath =>
                val suites: List[Text] = selectSuites(classpath, suite())

                if suites.nil then
                  Render.announce(t"no test suites were found on the classpath")
                  NoSuites
                else
                  val selectionArgs: List[Text] =
                    Selection.lower
                      ( kinds,
                        tags().lay(Nil: List[Text])(_.values),
                        axes().lay(Nil: List[Text])(_.values),
                        excludes().lay(Nil: List[Text])(_.values),
                        terms )

                  if showAxes || showTags then
                    // A suite that cannot stream its schedule lists as text: ids and paths,
                    // with no tags or axes to show.
                    val schedule: List[Suites.Scheduled] =
                      suites.bind[List[Suites.Scheduled], Suites.Scheduled, List[Suites.Scheduled]]:
                        suite => Suites.fetch(classpath, suite, selectionArgs)

                    if showTags then
                      schedule.flatMap(_.tags).distinct.each: tag =>
                        Out.println(t"$tag  ${schedule.count(_.tags.has(tag))}")

                    if showAxes then
                      schedule.each: test =>
                        val kind: Text = if test.kind == t"check" then t"test" else test.kind
                        val tags: Text = if test.tags.nil then t"-" else test.tags.join(t",")
                        val path: Text = test.ref.path.join(t"/")
                        Out.println(t"${test.ref.id}  $kind  $path  $tags  ${Suggest.axesText(test.axes)}")

                    Exit.Ok
                  else
                    val args: List[Text] = t"--list" :: selectionArgs

                    def recur(remaining: List[Text], failed: Boolean): Boolean = remaining match
                      case head :: tail =>
                        recur(tail, invokeSuite(classpath, head, args, fork) != Exit.Ok || failed)

                      case _ =>
                        failed

                    if recur(suites, false) then TestsFailed else Exit.Ok

              case _ =>
                Render.announce(t"at least one --classpath must be specified")
                NoClasspath

        // `fume watch …` — as `run`, but watch the OUTPUT jars named by `--classpath` (something
        // else does the compiling) and rerun the selection whenever one changes.
        // `fume serve [--port]` — serve the dashboard: every run the daemon has journalled, the
        // ones in flight live. Runs started from any shell while it serves register their boards,
        // so a browser and a terminal watch the same cells.
        case ui.Serve() :: _ =>
          val port: Int = ui.Port() match
            case text: Text => safely(text.as[Int]).or(fume.Dashboard.web.port)
            case _          => fume.Dashboard.web.port

          execute:
            given Stdio = summon[Invocation].stdio
            import probates.cancelProbate
            import webserverErrorPages.minimalErrorPage

            val stdio: Stdio = summon[Stdio]
            val tty: Boolean = summon[DaemonService[?]].cliInput == ethereal.Stdin.Terminal
            val aborted: java.util.concurrent.atomic.AtomicBoolean =
              java.util.concurrent.atomic.AtomicBoolean(false)

            trap:
              case Interrupt.Int =>
                aborted.set(true)
                SignalResponse.Accept

            val dashboard = fume.Dashboard()
            val served: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)

            // The frontend holds the monitor and the error page, which outlive it; vouched pure so
            // it can be stopped from here.
            val frontend: pyrocosm.WebFrontend =
              scala.caps.unsafe.unsafeAssumePure(pyrocosm.WebFrontend(port, fallback = Assets.serve))

            Server.serving = true

            async:
              try frontend.run(dashboard.interface)(dashboard.handle)
              finally served.set(true)

            Render.announce
              (t"serving the fume dashboard at http://localhost:$port/ (Ctrl+C to stop)")

            // On a terminal the launcher forwards Ctrl+C as a byte rather than a signal, so the
            // input is read for it here, as the load gate does.
            val input: Live.Input = Live.Input(aborted)

            def loop(): Unit =
              if tty then while stdio.in.available() > 0 do input.offer(stdio.in.read())
              dashboard.refresh()

              if !aborted.get && !served.get then
                snooze(0.25*Second)
                loop()

            loop()
            Server.serving = false
            frontend.stop()
            Render.announce(t"the dashboard has stopped")
            Exit.Ok

        case ui.Watch() :: _ =>
          val classpath: Optional[LocalClasspath] = classpathSetting()
          val suite: Prospective[Text] = suiteFlag(classpath)
          val kinds: List[Text] = selectedKinds
          val failFast: Boolean = ui.FailFast().or(false)

          execute:
            given Stdio = summon[Invocation].stdio
            classpath match
              case classpath: LocalClasspath =>
                Render.announce(t"'watch' is not yet implemented")
                Unimplemented

              case _ =>
                Render.announce(t"at least one --classpath must be specified")
                NoClasspath

        // A bare `fume` runs: the workspace's `.pyrocosm/fume/config.tel` supplies the
        // classpath, so the zero-argument invocation is the everyday one.
        case Nil =>
          runSelection(List())

        case _ =>
          execute(usage())

// The command bodies below take the ambient `Invocation` (a tracked capability). Its
// `WorkingDirectory` still resolves THROUGH it automatically, via the `WorkingDirectory.Provider`
// given in that type's companion, which extracts the invocation's pure member.
//
// `Stdio` no longer does: `Invocation` now extends `Stdio` directly (and `Stdio.Provider` moved
// up to `Cli`), so the ambient invocation itself is the nearest `Stdio` candidate — and being a
// tracked capability, it cannot flow into the PURE `Stdio` that `Out.println` demands under
// capture checking. Every printing scope therefore binds `given Stdio = invocation.stdio`
// explicitly: `stdio` is the invocation's pure member, so this is an ordinary projection, not
// purity laundering (no `unsafeAssumePure` anywhere).

// Reads `--classpath`, with its operand completing as a PATHNAME: `Discoverable` now receives
// the partially-typed operand text (soundness#1859), so completion delegates to
// `Pathname.complete` — tilde expansion, resolution against the invocation's working
// directory, and descent into a directory as it is typed, exactly as a positional path
// argument completes.
private def classpathSetting()
   (using cli: Cli, interpreter: Interpreter, configurator: Configurator)
:   Optional[LocalClasspath] =

  // Only the (pure) directory name is captured by the lambdas below, not the `Cli` capability
  // itself, which a pure `Discoverable` may not close over.
  val workingDirectory: Text = cli.workingDirectory.directory()

  // Decodes ':'-separated entries to `LocalClasspath`, expanding `*` globs against the
  // invocation's working directory (`Suites.expand`), so flags, environment variables and
  // configuration values all accept e.g. `out/*/test/assembly.dest/out.jar`. Hellenism's own
  // decodable is not used: it captures a `Tactic[Property.Error]` (reading the platform path
  // separator as a system property), which the pure `Setting` read cannot supply — and it
  // knows nothing of globs. As upstream, an entry ending `.jar` is a jar, anything else a
  // directory.
  given decodable: (LocalClasspath is Decodable in Text) = text =>
    val expanded: List[Text] =
      text.cut(t":").filter(_ != t"").bind[List[Text], Text, List[Text]]: (entry: Text) =>
        Suites.expand(workingDirectory, entry)

    val entries: List[Classpath.Entry.Directory | Classpath.Entry.Jar] =
      expanded.map: (entry: Text) =>
        if entry.ends(t".jar") then Classpath.Entry.Jar(entry)
        else Classpath.Entry.Directory(entry)

    LocalClasspath(entries*)

  given wd: WorkingDirectory = () => workingDirectory

  given discoverable: (LocalClasspath is Discoverable) = (operand, tab) =>
    Pathname.complete(operand, tab)

  ui.Classpath()

// Reads `--suite` as a `Prospective` handle (its value resolves now, in the pure section, but
// is only USED inside `execute`), offering the suites discovered on the (already-read)
// classpath as its tab-completions: `fume run -c out.jar -s <TAB>` completes real suite class
// names. Discovery reads the services index as text and loads no classes, so it is safe (and
// fast enough) to run in completion mode; an absent or unbuilt classpath simply suggests
// nothing.
private def suiteFlag(classpath: Optional[LocalClasspath])(using Cli, Interpreter)
:   Prospective[Text] =
  given discoverable: (Text is Discoverable) = (_, _) =>
    classpath.lay(List()) { cp => Suites.discover(cp).map(Suggestion(_)) }

  ui.Suite()

// Reads `--kind`, its operand completing to the kind names, comma-continued.
private def kindSetting()(using Cli, Interpreter, Configurator): Optional[Text] =
  given discoverable: (Text is Discoverable) = (operand, _) => Suggest.kinds(operand)
  ui.Kind()

// Reads `--tag`, its operand completing to the tags on the classpath, comma-continued. Like
// every completion below, the schedule is consulted LAZILY — `Discoverable#discover` runs only
// when this operand is the word being completed — and served from the daemon's cache.
private def tagFlag(classpath: Optional[LocalClasspath])(using Cli, Interpreter)
:   Prospective[Repeated] =

  given discoverable: (Repeated is Discoverable) = (operand, _) =>
    classpath.lay(List()) { cp => Suggest.tags(operand, Suites.cached(cp)) }

  ui.Tag()

// Reads `--axis`, its operand completing to the axes of the tests the other arguments
// (`words`, already lowered) identify, then to the values of the axis once its `=` is typed.
private def axisFlag(classpath: Optional[LocalClasspath], words: List[Text])
   (using Cli, Interpreter)
:   Prospective[Repeated] =

  given discoverable: (Repeated is Discoverable) = (operand, _) =>
    classpath.lay(List()) { cp => Suggest.axes(operand, words, Suites.cached(cp)) }

  ui.Axis()

// Reads `--exclude`, whose operand is any selection term and completes as a positional one.
private def excludeFlag(classpath: Optional[LocalClasspath], words: List[Text])
   (using Cli, Interpreter)
:   Prospective[Repeated] =

  given discoverable: (Repeated is Discoverable) = (operand, _) =>
    classpath.lay(List()) { cp => Suggest(operand, words, Suites.cached(cp)) }

  ui.Exclude()

// Registers the completion of every positional selection term: the focused word completes to
// the REAL tests on the classpath — ids, monikers, kinds, tags, and the axes and values of the
// tests the OTHER words identify — from the daemon's cached schedule. Discovery is lazy: the
// update thunk runs only when the focused word is a term, so ordinary invocations never pay
// for the listing.
private def completeTerms(classpath: Optional[LocalClasspath], rest: List[Argument])(using Cli)
:   Unit =

  classpath.let: cp =>
    selectionArguments(rest).each: argument =>
      val others: List[Text] =
        Selection.words:
          rest.filter(_.position != argument.position).map { (argument: Argument) => argument() }

      summon[Cli].suggest(argument, Suggest(argument(), others, Suites.cached(cp)), t"", t"")

// The raw Probably selection terms: every argument after the subcommand that is neither a flag
// nor the operand of a value-taking flag. This does NOT interpret the terms — they are
// forwarded verbatim to each suite — it only separates them from fume's own flags.
private def selectionArguments(rest: List[Argument]): List[Argument] =
  val valueFlags: List[Flag] =
    List(ui.Classpath.flag, ui.Suite, ui.Kind.flag, ui.Tag, ui.Axis, ui.Exclude, ui.FailFast.flag,
         ui.MaxLoad.flag, ui.DurationScale.flag, ui.Target.flag)

  def recur(args: List[Argument], terms: List[Argument]): List[Argument] = args match
    case head :: tail =>
      if head().starts(t"-") then
        // A value-taking flag consumes the argument after it (unless written `--flag=value`,
        // which is a single argument); every other flag consumes nothing.
        val operand =
          valueFlags.exists(_.matches(head)) && !head().contains(t"=")

        recur(if operand then tail match { case _ :: tail2 => tail2; case _ => tail } else tail,
              terms)
      else recur(tail, head :: terms)

    case _ =>
      terms.reverse

  recur(rest, List())

private def selectionTerms(rest: List[Argument]): List[Text] =
  selectionArguments(rest).map { (argument: Argument) => argument() }

// The suites the selection admits: everything discovered on the classpath, narrowed to
// `--suite` when given. Empty means nothing to run (reported by the caller as `NoSuites`).
private def selectSuites(classpath: LocalClasspath, suite: Optional[Text]): List[Text] =
  val all: List[Text] = Suites.discover(classpath)
  suite.lay(all) { chosen => all.filter(_ == chosen) }

// Runs one suite in a fresh JVM — `java -cp <classpath> <suite> <terms…>` — forwarding its
// output and yielding its exit status. The `java` executable is the daemon's own
// (`java.home`), so no PATH lookup is involved.
private def forkSuite(classpath: LocalClasspath, suite: Text, args: List[Text])
   (using Stdio, WorkingDirectory, Environment)
:   Exit =

  val java: Text =
    safely(System.properties.java.home[Text]()).lay(t"java") { home => t"$home/bin/java" }

  val command = sh"$java -cp ${classpath()} $suite $args"

  safely:
    val job = command.fork[Text]()
    val output: Text = job.await()
    Out.print(output)
    job.exitStatus()

  . or:
      Render.announce(t"could not invoke $suite")
      Exit.Fail(2)

// Runs one suite the LEGACY way: in-process through `Suite#invoke` (the suite renders its
// own report), falling back to a forked JVM when even that is unavailable. This remains the
// whole story for `--fork` and for `--list`, whose selection the event protocol deliberately
// rejects (the stable text output belongs to the legacy path).
private def invokeSuite(classpath: LocalClasspath, suite: Text, args: List[Text], fork: Boolean)
   (using Stdio, WorkingDirectory, Monitor, Environment)
:   Exit =

  if fork then forkSuite(classpath, suite, args)
  else
    Suites.invoke(classpath, suite, args).or:
      Render.announce(t"$suite could not be run in-process; running it in a separate JVM")
      forkSuite(classpath, suite, args)

// The terminal's width from the invocation's `COLUMNS`, or 120. A method of its own, outside
// the inlined dispatch: read there, the `safely` region's tactic and the inlined `Environment`
// parameter's capture sets do not reconcile.
private def terminalWidth()(using Environment): Int = safely(Environment.columns.as[Int]).or(120)

private def usage()(using invocation: Invocation): UsageError.type =
  given Stdio = invocation.stdio
  Out.println(t"Usage: fume <command> [options] [terms...]")
  Out.println(t"")
  Out.println(t"Commands:")
  Out.println(t"  run      run the tests and benchmarks admitted by the selection")
  Out.println(t"  list     list the tests and benchmarks on the classpath")
  Out.println(t"  watch    watch the classpath jars and rerun tests on change")
  Out.println(t"  serve    serve the dashboard of runs on the web until Ctrl+C")
  Out.println(t"  about    show fume's version and daemon")
  Out.println(t"  install  install shell tab-completions and the fume manpage")
  Out.println(t"  quit     stop the background daemon")
  UsageError
