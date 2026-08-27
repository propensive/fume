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
import executives.completions
import interpreters.posixInterpreter
import logging.silentLogging
import systems.javaSystem
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
        val classpath: Optional[LocalClasspath] = classpathSetting()
        val suite: Prospective[Text] = suiteFlag(classpath)
        val kinds: List[Text] = selectedKinds
        val failFast: Boolean = ui.FailFast().or(false)
        val fork: Boolean = ui.Fork().present
        val terms: List[Text] = selectionTerms(rest)

        execute:
          classpath match
            case classpath: LocalClasspath =>
              val suites: List[Text] = selectSuites(classpath, suite())

              if suites.nil then
                Out.println(t"fume: no test suites were found on the classpath")
                NoSuites
              else
                val kindTerms: List[Text] = kinds.map { (kind: Text) => t"kind:$kind" }
                val args: List[Text] = kindTerms + terms

                val width: Int = safely(Environment.columns.as[Int]).or(120)
                val terse: Boolean = fume.GithubActions.terse

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
                    case head :: tail =>
                      Out.println(t"fume: running $head")
                      val (exit, suiteTotals) = runSuite(classpath, head, args, fork, width, terse)
                      val passed = exit == Exit.Ok

                      if suiteTotals.absent then
                        Out.println:
                          if passed then t"fume: $head: passed" else t"fume: $head: FAILED"

                      val failures2 = if passed then failures else failures + 1

                      val totals2: Optional[Doc.Totals] =
                        suiteTotals.lay(totals): suiteTotals =>
                          totals.lay(suiteTotals)(_ + suiteTotals)

                      if !passed && failFast then (failures2, ran + 1, totals2)
                      else recur(tail, failures2, ran + 1, totals2)

                    case _ =>
                      (failures, ran, totals)

                val (failures, ran, totals) = recur(suites, 0, 0, Unset)

                // The banner renders over the aggregate of every event-run suite; when every
                // suite ran legacy (each rendered its own banner already), only the summary
                // line prints.
                totals.let(Render.finale(_, terse))

                Out.println(t"fume: ${ran - failures} of $ran suites passed")
                if failures == 0 then Exit.Ok else TestsFailed

            case _ =>
              Out.println(t"fume: at least one --classpath must be specified")
              NoClasspath

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
          classpath match
            case classpath: LocalClasspath =>
              val suites: List[Text] = selectSuites(classpath, suite())

              if suites.nil then
                Out.println(t"fume: no test suites were found on the classpath")
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
              Out.println(t"fume: at least one --classpath must be specified")
              NoClasspath

      // `fume watch …` — as `run`, but watch the OUTPUT jars named by `--classpath` (something
      // else does the compiling) and rerun the selection whenever one changes.
      case ui.Watch() :: _ =>
        val classpath: Optional[LocalClasspath] = classpathSetting()
        val suite: Prospective[Text] = suiteFlag(classpath)
        val kinds: List[Text] = selectedKinds
        val failFast: Boolean = ui.FailFast().or(false)

        execute:
          classpath match
            case classpath: LocalClasspath =>
              Out.println(t"fume: 'watch' is not yet implemented")
              Unimplemented

            case _ =>
              Out.println(t"fume: at least one --classpath must be specified")
              NoClasspath

      // `fume install [--force]` — install shell tab-completions and the manpage.
      case ui.Install() :: _ =>
        val force: Boolean = ui.Force().present

        execute(install(force))

      case Nil =>
        execute(usage())

      case _ =>
        execute(usage())

// The command bodies below take the ambient `Invocation` (a tracked capability), and their
// `Stdio` and `WorkingDirectory` resolve THROUGH it automatically: `Stdio.Provider` and
// `WorkingDirectory.Provider` givens in those types' companions extract the invocation's pure
// members, so no `given Stdio = …` bindings (and certainly no `unsafeAssumePure`) appear at
// use sites. `Stdio.Provider` is implemented by `Invocation` alone — deliberately not by `Cli`
// — so the pure prelude section still cannot print, which is what keeps completion mode clean.

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

// The raw Probably selection terms: every argument after the subcommand that is neither a flag
// nor the operand of a value-taking flag. This does NOT interpret the terms — they are
// forwarded verbatim to each suite — it only separates them from fume's own flags.
private def selectionTerms(rest: List[Argument]): List[Text] =
  val valueFlags: List[Flag] = List(ui.Classpath.flag, ui.Suite, ui.FailFast.flag)

  def recur(args: List[Argument], terms: List[Text]): List[Text] = args match
    case head :: tail =>
      if head().starts(t"-") then
        // A value-taking flag consumes the argument after it (unless written `--flag=value`,
        // which is a single argument); every other flag consumes nothing.
        val operand =
          valueFlags.exists(_.matches(head)) && !head().contains(t"=")

        recur(if operand then tail match { case _ :: tail2 => tail2; case _ => tail } else tail,
              terms)
      else recur(tail, head() :: terms)

    case _ =>
      terms.reverse

  recur(rest, List())

// The suites the selection admits: everything discovered on the classpath, narrowed to
// `--suite` when given. Empty means nothing to run (reported by the caller as `NoSuites`).
private def selectSuites(classpath: LocalClasspath, suite: Optional[Text]): List[Text] =
  val all: List[Text] = Suites.discover(classpath)
  suite.lay(all) { chosen => all.filter(_ == chosen) }

// Runs one suite in a fresh JVM — `java -cp <classpath> <suite> <terms…>` — forwarding its
// output and yielding its exit status. The `java` executable is the daemon's own
// (`java.home`), so no PATH lookup is involved.
private def forkSuite(classpath: LocalClasspath, suite: Text, args: List[Text])
   (using Stdio, WorkingDirectory)
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
      Out.println(t"fume: could not invoke $suite")
      Exit.Fail(2)

// Runs one suite the LEGACY way: in-process through `Suite#invoke` (the suite renders its
// own report), falling back to a forked JVM when even that is unavailable. This remains the
// whole story for `--fork` and for `--list`, whose selection the event protocol deliberately
// rejects (the stable text output belongs to the legacy path).
private def invokeSuite(classpath: LocalClasspath, suite: Text, args: List[Text], fork: Boolean)
   (using Stdio, WorkingDirectory, Monitor)
:   Exit =

  if fork then forkSuite(classpath, suite, args)
  else
    Suites.invoke(classpath, suite, args).or:
      Out.println(t"fume: $suite could not be run in-process; running it in a separate JVM")
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
     terse: Boolean )
   (using Stdio, WorkingDirectory, Monitor, Environment)
:   (Exit, Optional[Doc.Totals]) =

  import abstractables.durationAbstractable
  import probates.cancelProbate

  def legacy(): Exit = invokeSuite(classpath, suite, args, fork = false)

  if fork then (forkSuite(classpath, suite, args), Unset)
  else
    val model = Model()

    // The live board is worthless where nobody watches: terse mode (CI, Claude Code) folds
    // events quietly, and without the terminal's real geometry (no COLUMNS in the client's
    // environment) live painting would be guesswork; both render once at the end instead.
    val knownColumns: Boolean = safely(Environment.columns.as[Int]).present

    val live: Optional[Live] = if terse || !knownColumns then Unset else Live(model, width)

    // The one-second trigger: if the suite is still producing when this fires, the board
    // starts painting; `activate` is a no-op once `finish` has run.
    val timer = async:
      snooze(1000L)
      live.let(_.activate())

    val outcome =
      EventStream.stream(classpath, suite, args): event =>
        model.handle(event)
        live.let(_.tick())

    live.let(_.finish())
    timer.cancel()

    outcome match
      case EventStream.Outcome.Completed(exit) =>
        val document = Documenting.document(model.state())
        Render.suite(document, width, terse)

        // A suite reports failure (exit 1) when NOTHING was admitted: right when it is
        // invoked alone, wrong when fume fans a kind filter (`--bench`) across every suite
        // on the classpath — a suite with no benchmarks is not a failing suite.
        val emptySelection: Boolean =
          exit == 1 && document.totals.total == 0 && document.fatal.absent

        (if exit == 0 || emptySelection then Exit.Ok else Exit.Fail(exit), document.totals)

      case EventStream.Outcome.Incompatible =>
        Out.println(t"fume: $suite was built against an incompatible Soundness; falling back")
        (legacy(), Unset)

      case _ =>
        Out.println(t"fume: $suite predates event streaming; using the legacy run")
        (legacy(), Unset)

private def showVersion()(using Invocation): Exit =
  Out.println(t"fume $fumeVersion")
  Exit.Ok

private def usage()(using Invocation): UsageError.type =
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

  // The `DaemonService` extends `Entrypoint`, and `Completions.ensure` accepts a TRACKED
  // `Entrypoint^`, so the service is passed on with its capture intact — no purity laundering.
  given entrypoint: (Entrypoint^{service}) = service

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
