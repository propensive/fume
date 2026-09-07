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

import soundness.*

import backstops.silentBackstop
import executives.completionsExecutive
import interpreters.posixInterpreter
import logging.silentLogging
import systems.javaBaseSystem
import threading.platformThreading

// The Maven Central version of `fume-client`, mirrored in `build.mill`'s `settings.fumeVersion`.
val fumeVersion: Text = t"0.1.0"

// Fume's identity in configuration namespaces: every `Setting` below is read from its
// command-line flag first, then (through `Configurator.default`) a `fume.`-prefixed system
// property, then a `FUME_`-prefixed environment variable. A configuration-file `Configurator`
// can later be composed into the cascade with `++` without touching any read site.
given prefix: Configurator.Prefix = Configurator.Prefix(t"fume")

// The exit statuses fume can terminate with, declared as objects (a `Status` must be an
// `object`, not a `val` — soundness#1811) so that the precise union of an `execute` block's
// result type documents them: `Status.Admissible` reifies the union, and the manpage's EXIT
// STATUS section is generated from it. Codes 0, 1 and 2 deliberately mirror `probably.Suite`'s
// own exit protocol (0 = passed, 1 = test failures, 2 = the suite threw).
object TestsFailed extends Status(1, t"one or more tests failed")
object UsageError extends Status(2, t"the command line was not understood")
object NoClasspath extends Status(3, t"no --classpath was specified, or an entry was unreadable")
object NoSuites extends Status(4, t"no test suites were found on the classpath")
object InstallFailed extends Status(8, t"the tab-completions or manpage could not be installed")
object Unimplemented extends Status(10, t"this subcommand is not yet implemented")

// Fume's user interface, in one namespace: its subcommands, flags and settings. The object
// exists so each can carry its NATURAL name — `ui.Test`, `ui.Suite`, `ui.Classpath`,
// `ui.Install`, `ui.List` — without a package-level `val` shadowing the Soundness export of
// the same name for the whole `fume` package (which previously forced `TestF`-style suffixes
// and a fully-qualified `hellenism.Classpath`). Within this object's own body the members DO
// shadow those exports, so the declarations avoid the shadowed names: aliases are written
// `proscenium.List('c')` rather than `List('c')`.
object ui:
  val Run = Subcommand("run", "run the tests and benchmarks admitted by the selection")
  val List = Subcommand("list", "list the tests and benchmarks on the classpath")
  val Watch = Subcommand("watch", "watch the classpath jars and rerun tests on change")
  val Install = Subcommand("install", "install shell tab-completions and the fume manpage")

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
  // A `Setting`, not a `Flag`, so a project can fix its classpath once in `.fume/config.tel`
  // (one `classpath` entry per line — see `Workspace`) instead of repeating it on every
  // invocation; `-c`/`--classpath` and the `fume.classpath` property/`FUME_CLASSPATH` variable
  // override it, all as ':'-separated entries.
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

  val Fork = Flag[Unit]("fork", false, Nil, "run each suite in a separate JVM")
  val Force = Flag[Unit]("force", false, Nil, "overwrite existing files when installing")
  val Version = Flag[Unit]("version", false, Nil, "show fume's version")

  // Single-valued options are `Setting`s rather than `Flag`s, so each is also configurable
  // through the `Configurator.Prefix` cascade above; the camelCase name derives the
  // `--fail-fast` flag, the `fume.fail.fast` system property and the `FUME_FAIL_FAST`
  // environment variable. Like a flag, a setting must be read outside `execute` to register
  // for tab-completion.
  val FailFast = Setting[Boolean](t"failFast", t"stop after the first failing test")

  // The load gate: hold the run back until the system's 1-minute load average has fallen
  // below this value. A `Setting`, so a benchmarking workspace can fix a house threshold in
  // `.fume/config.tel` (`maxLoad 0.5`) and still override it per invocation; `--max-load`,
  // the `fume.max.load` property and `FUME_MAX_LOAD` all reach it.
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
  cli:
    // The full configuration cascade for every `Setting` read below: the command-line flag
    // always wins (handled structurally by `Setting`), then `fume.*` system properties, then
    // `FUME_*` environment variables, then the workspace's `.fume/config.tel` — resolved from
    // the INVOCATION's working directory (each daemon client has its own), never the daemon
    // process's. This local given takes precedence over `Configurator.default`, which it
    // extends by one source.
    given configurator: Configurator =
      Configurator.properties ++ Configurator.environment
      ++ Workspace.configurator(summon[Cli].workingDirectory.directory())

    // The run command's whole body, shared by `fume run …` and the BARE `fume …` (running is
    // the default when no subcommand is given).
    def runSelection(rest: List[Argument]) =
      val classpath: Optional[LocalClasspath] = classpathSetting()
      val suite: Prospective[Text] = suiteFlag(classpath)
      val kinds: List[Text] = selectedKinds
      val failFast: Boolean = ui.FailFast().or(false)
      val maxLoad: Optional[Text] = ui.MaxLoad()
      val durationScale: Optional[Text] = ui.DurationScale()
      val target: Optional[Text] = ui.Target()
      val fork: Boolean = ui.Fork().present
      val terms: List[Text] = selectionTerms(rest)

      // Selection terms tab-complete to the REAL tests on the classpath: hash ids, monikers
      // and `kind:` terms, discovered LAZILY — the update thunk runs only when the focused
      // word is a term, so ordinary invocations never pay for the listing.
      classpath.let: cp =>
        selectionArguments(rest).each: argument =>
          summon[Cli].suggest(argument, Suites.terms(cp, Unset), t"", t"")

      execute:
        given Stdio = summon[Invocation].stdio
        classpath match
          case classpath: LocalClasspath =>
            val suites: List[Text] = selectSuites(classpath, suite())

            if suites.nil then
              Render.announce(t"no test suites were found on the classpath")
              NoSuites
            else
              val kindTerms: List[Text] = kinds.map { (kind: Text) => t"kind:$kind" }
              val selectionArgs: List[Text] = kindTerms + terms

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

              // With a budget (and no explicit factor), the schedule pre-pass prices the
              // selection and the factor is derived. A budget that buys nothing — no timed
              // tests admitted, or no suite able to stream its schedule — changes nothing.
              val budgetTerm: Optional[Text] =
                if scaleTerm.present || target.absent then Unset else
                  target.let(Budget.parse(_)).lay(Unset: Optional[Text]): nanos =>
                    val priced: Long = Budget.expected(classpath, suites, selectionArgs)

                    if priced == 0L then
                      Render.announce(t"the selection has no timed measurements; ignoring --target")
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

              val args: List[Text] = scaleTerms + selectionArgs

              val width: Int = safely(Environment.columns.as[Int]).or(120)
              val terse: Boolean = fume.GithubActions.terse
              val tty: Boolean = summon[DaemonService[?]].cliInput == ethereal.Stdin.Terminal

              // Ctrl+C at the client arrives here as a trapped SIGINT: the current suite's
              // event consumption stops, its partial report renders, and no further suite
              // starts. (The suite's threads — and any measurement JVMs a staged benchmark
              // has spawned — are cancelled, not awaited.)
              val aborted: java.util.concurrent.atomic.AtomicBoolean =
                java.util.concurrent.atomic.AtomicBoolean(false)

              val winched: java.util.concurrent.atomic.AtomicBoolean =
                java.util.concurrent.atomic.AtomicBoolean(false)

              trap:
                case Interrupt.Int =>
                  aborted.set(true)
                  SignalResponse.Accept

                case Interrupt.Winch =>
                  winched.set(true)
                  SignalResponse.Accept

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
                    classpath(),
                    args,
                    suites )

              // Each suite renders its own report as its event stream ends; the verdict
              // comes from its exit status, and the totals — when the suite ran by the
              // event protocol — accumulate towards the whole-run banner.
              def recur
                 ( remaining: List[Text],
                   failures: Int,
                   ran: Int,
                   totals: Optional[Doc.Totals] )
              :   (Int, Int, Optional[Doc.Totals]) =

                remaining match
                  case _ if aborted.get =>
                    (failures, ran, totals)

                  case head :: tail =>
                    Render.announce(t"running $head")
                    Journal.began(journalId, head)
                    val suiteStarted: Long = java.lang.System.currentTimeMillis

                    val (exit, suiteTotals) =
                      runSuite(classpath, head, args, fork, width, terse, tty,
                          aborted, winched)
                    val passed = exit == Exit.Ok
                    Journal.record(journalId, head, passed, suiteTotals, suiteStarted)

                    if suiteTotals.absent then
                      Render.announce:
                        if passed then t"$head: passed" else t"$head: FAILED"

                    val failures2 = if passed then failures else failures + 1

                    val totals2: Optional[Doc.Totals] =
                      suiteTotals.lay(totals): suiteTotals =>
                        totals.lay(suiteTotals)(_ + suiteTotals)

                    if !passed && failFast then (failures2, ran + 1, totals2)
                    else recur(tail, failures2, ran + 1, totals2)

                  case _ =>
                    (failures, ran, totals)

              val (failures, ran, totals) = recur(suites, 0, 0, Unset)

              val outcome: Journal.Outcome =
                if aborted.get then Journal.Outcome.Aborted
                else if failures == 0 then Journal.Outcome.Passed
                else Journal.Outcome.Failed

              Journal.finish(journalId, outcome, totals)

              // The banner renders over the aggregate of every event-run suite; when every
              // suite ran legacy (each rendered its own banner already), only the summary
              // line prints.
              totals.let(Render.finale(_, terse))

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
      // `fume -<flag>…` — currently only `--version`. This case fires only when the first
      // token is a FLAG (`head` begins with `-`), which cannot be a subcommand.
      //
      // It is matched FIRST so that, when the word being completed is a flag, the subcommand
      // patterns below are never evaluated. Matching a `Subcommand` also SUGGESTS it, and a
      // suggestion at the cursor takes precedence over the flag list (`Completion`'s
      // `cursorSuggestions` wins over `flagSuggestions`) — so trying the subcommands first
      // would leave `fume --ver<TAB>` offering `run`/`list`/`watch`/`install` and no flags at
      // all. Ordering costs nothing at run time: a subcommand never begins with `-`, so no
      // invocation changes meaning.
      case Argument(head) :: _ if head.starts(t"-") =>
        // EVERY flag this case accepts is read here, unconditionally and before `execute`:
        // reading a flag is what registers it with Exoskeleton (`Flag#apply` calls
        // `cli.register`), and registration is the only way it reaches the completion output. A
        // read placed behind a condition — or inside `execute`, whose block does not run at all
        // in completion mode — would silently cost that flag its tab-completion.
        val version: Boolean = ui.Version().present

        if version then execute(showVersion()) else execute(usage())

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
      // line.
      case ui.List() :: rest =>
        val classpath: Optional[LocalClasspath] = classpathSetting()
        val suite: Prospective[Text] = suiteFlag(classpath)
        val kinds: List[Text] = selectedKinds
        val fork: Boolean = ui.Fork().present
        val terms: List[Text] = selectionTerms(rest)

        execute:
          given Stdio = summon[Invocation].stdio
          classpath match
            case classpath: LocalClasspath =>
              val suites: List[Text] = selectSuites(classpath, suite())

              if suites.nil then
                Render.announce(t"no test suites were found on the classpath")
                NoSuites
              else
                val kindTerms: List[Text] = kinds.map { (kind: Text) => t"kind:$kind" }
                val args: List[Text] = t"--list" :: kindTerms + terms

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

      // `fume install [--force]` — install shell tab-completions and the manpage.
      case ui.Install() :: _ =>
        val force: Boolean = ui.Force().present

        execute(install(force))

      // A bare `fume` runs: the workspace's `.fume/config.tel` supplies the classpath, so
      // the zero-argument invocation is the everyday one.
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

    LocalClasspath(entries.stdlib*)

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

// The raw Probably selection terms: every argument after the subcommand that is neither a flag
// nor the operand of a value-taking flag. This does NOT interpret the terms — they are
// forwarded verbatim to each suite — it only separates them from fume's own flags.
private def selectionArguments(rest: List[Argument]): List[Argument] =
  val valueFlags: List[Flag] =
    List(ui.Classpath.flag, ui.Suite, ui.FailFast.flag, ui.MaxLoad.flag, ui.DurationScale.flag,
         ui.Target.flag)

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

// Runs one suite by the EVENT PROTOCOL: `EventStream.stream` runs it in the isolating
// classloader; the events fold into a `Model` (live-painted by an Ultimatum board if the
// suite is still producing after one second), and the report renders from the model when
// the stream ends. Returns the suite's totals alongside its exit, for the whole-run banner.
//
// A suite whose Probably predates the event stream falls back to the legacy in-process run
// (it renders its own report), and one predating `Suite#invoke` falls back further to a
// forked JVM; an INCOMPATIBLE event schema (a different Soundness) falls back likewise,
// with a distinct notice.
private def runSuite
   ( classpath: LocalClasspath,
     suite: Text,
     args: List[Text],
     fork: Boolean,
     width: Int,
     terse: Boolean,
     tty: Boolean,
     aborted: java.util.concurrent.atomic.AtomicBoolean,
     winched: java.util.concurrent.atomic.AtomicBoolean )
   (using Stdio, WorkingDirectory, Monitor, Environment)
:   (Exit, Optional[Doc.Totals]) =

  import probates.cancelProbate

  def legacy(): Exit = invokeSuite(classpath, suite, args, fork = false)

  if fork then (forkSuite(classpath, suite, args), Unset)
  else
    val model = Model()

    // The live board is worthless where nobody watches: terse mode (CI, Claude Code) folds
    // events quietly, and a piped invocation (the launcher reports whether the client is on
    // a terminal) renders once at the end instead.
    //
    // On a terminal, the launcher holds the client tty in RAW mode: Ctrl+C arrives as the
    // byte 0x03 on stdin, never as a signal, so an input pump owns stdin for the run —
    // dispatching keypresses to the abort flag and size-probe replies to the board.
    val input: Optional[Live.Input] = if tty then Live.Input(aborted) else Unset

    val live: Optional[Live] =
      if terse || !tty then Unset
      else input.let { input => Live(model, width, winched, input) }

    // With a live board coming, a listing pre-pass seeds the schedule: the suite runs with
    // `--list` on the EVENT protocol, emitting one `TestScheduled` per admitted test — with
    // its real ref, so paths group correctly whatever characters the names contain — and
    // every scheduled test appears in its table immediately, blank, filling in as its
    // result arrives.
    if live.present then
      // The abort thunk is passed explicitly: the DEFAULT argument's root capability
      // cannot flow into `safely`'s enclosing function under capture checking.
      safely(EventStream.stream(classpath, suite, t"--list" :: args)(model.handle(_), () => false))
      . unit

    // The one-second trigger: if the suite is still producing when this fires, the board
    // starts painting; `activate` is a no-op once `finish` has run.
    //
    // `1*Second`, not `snooze(1000L)`: `snooze`'s `Long` overload counts NANOSECONDS, so the
    // bare literal made this (and the two loops below) fire immediately and spin.
    val timer = async:
      snooze(1*Second)
      live.let(_.activate())

    // The resize pulse, owned by the invocation so it outlives the timer: each beat lets
    // the board react to a forwarded SIGWINCH.
    val pulse = async:
      def loop(): Unit =
        snooze(0.2*Second)
        live.let(_.pulse())
        loop()

      loop()

    // The stdin pump: every byte the client's terminal sends is dispatched — Ctrl+C to the
    // abort flag, CSI replies to the size probe.
    val pump = async:
      def loop(): Unit =
        input.let: input =>
          val stdio = summon[Stdio]

          if stdio.in.available() > 0 then
            input.offer(stdio.in.read()) match
              case Live.Key.Up   => live.let(_.scroll(-1))
              case Live.Key.Down => live.let(_.scroll(1))
              case _             => ()
          else snooze(0.03*Second)

        if input.present then loop()

      loop()

    val outcome =
      EventStream.stream(classpath, suite, args)
        ( { event =>
              model.handle(event)
              live.let(_.tick()) },
          () => aborted.get )

    live.let(_.finish())
    timer.cancel()
    pulse.cancel()
    pump.cancel()

    outcome match
      case EventStream.Outcome.Completed(exit) if exit == EventStream.abortExit =>
        Render.announce(t"aborted; the partial report follows")
        val document = Documenting.document(model.state())
        Render.suite(document, live.let(_.columns).or(width), terse)
        (Exit.Fail(exit), document.totals)

      case EventStream.Outcome.Completed(exit) =>
        val document = Documenting.document(model.state())
        // The report replays onto the primary buffer at the terminal's REAL width — the
        // board probed it — rather than the COLUMNS guess.
        Render.suite(document, live.let(_.columns).or(width), terse)

        // A suite reports failure (exit 1) when NOTHING was admitted: right when it is
        // invoked alone, wrong when fume fans a kind filter (`--bench`) across every suite
        // on the classpath — a suite with no benchmarks is not a failing suite.
        val emptySelection: Boolean =
          exit == 1 && document.totals.total == 0 && document.fatal.absent

        (if exit == 0 || emptySelection then Exit.Ok else Exit.Fail(exit), document.totals)

      case EventStream.Outcome.Failed(error) =>
        // Written to a file first: the terminal may be mid-repaint, and a trace on stderr
        // inside the alternate buffer is lost when the board closes.
        val trace = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(trace))
        val path = java.nio.file.Path.of(java.lang.System.getProperty("java.io.tmpdir").nn, "fume-failure.log").nn
        java.nio.file.Files.writeString(path, trace.toString)
        Render.announce(t"the event consumer failed while $suite was running; the run was stopped")
        Render.announce(t"the stack trace is in ${path.toString.tt}, and follows:")
        trace.toString.tt.cut(t"\n").each { (line: Text) => Out.println(line) }
        (Exit.Fail(2), Unset)

      case EventStream.Outcome.Incompatible =>
        Render.announce(t"$suite was built against an incompatible Soundness; falling back")
        (legacy(), Unset)

      case _ =>
        Render.announce(t"$suite predates event streaming; using the legacy run")
        (legacy(), Unset)

private def showVersion()(using invocation: Invocation): Exit =
  given Stdio = invocation.stdio
  Out.println(t"fume $fumeVersion")
  Exit.Ok

private def usage()(using invocation: Invocation): UsageError.type =
  given Stdio = invocation.stdio
  Out.println(t"Usage: fume <command> [options] [terms...]")
  Out.println(t"")
  Out.println(t"Commands:")
  Out.println(t"  run      run the tests and benchmarks admitted by the selection")
  Out.println(t"  list     list the tests and benchmarks on the classpath")
  Out.println(t"  watch    watch the classpath jars and rerun tests on change")
  Out.println(t"  install  install shell tab-completions and the fume manpage")
  UsageError

// Installs fume's shell tab-completions and its manpage. `Completions.ensure` writes the
// zsh/bash/fish completion script (the same call the built-in `{admin} install` uses); it needs
// an `Entrypoint`, which the ambient Ethereal `DaemonService` supplies (it extends
// `Entrypoint`). The manpage's structure comes from `service.help()` — the same subcommand/flag
// tree the completions register, discovered by re-running the dispatch above in completion mode
// — so `man fume` can never disagree with the CLI, and the EXIT STATUS section is populated
// from the `Status` unions of the `execute` blocks. `force = true` for the completions installs
// even when `fume` is not yet on the `PATH`, so a freshly-built binary can set itself up before
// being installed as a command.
private def install(force: Boolean)
   (using invocation: Invocation, service: DaemonService[?])
   (using erased Effectful)
:   InstallFailed.type | Exit =

  import errorDiagnostics.stackTracesDiagnostics

  given Stdio = invocation.stdio

  // The `DaemonService` extends `Entrypoint`, and `Completions.ensure` accepts a TRACKED
  // `Entrypoint^`, so the service is passed on with its capture intact — no purity laundering.
  given entrypoint: Entrypoint = service

  given manual: Manual =
    Manual
      ( prose = t"Fume is the test runner for the Soundness ecosystem: it runs Probably tests "
              + t"and Sedentary benchmarks from a prebuilt classpath, discovering suites "
              + t"through the META-INF/services/probably.Suite index." )

  recover:
    // `exoskeleton.Install`, qualified: the bare name `Install` is this file's subcommand.
    case error: exoskeleton.Install.Error =>
      Out.println(t"Could not install the tab-completions or manpage")
      InstallFailed

  . protect:
      Completions.ensure(force = true).each(Out.println(_))

      Manpages.install(service.help().roff, force) match
        case Manpages.InstallResult.Installed(path) =>
          Out.println(t"Installed the manpage to $path")

        case Manpages.InstallResult.AlreadyInstalled(path) =>
          Out.println(t"A manpage is already installed at $path; use --force to overwrite it")

        case Manpages.InstallResult.NoWritableLocation =>
          Out.println(t"No writable location was found for the manpage")

      Exit.Ok
