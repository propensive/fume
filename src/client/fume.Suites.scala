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

import probably.TestEvent

// Discovery of Probably test suites on a user-supplied classpath. Suites are found ONLY through
// the `META-INF/services/probably.Suite` index which the beneficence compiler plugin writes into
// each module (and which assemblies concatenate); no classes are scanned, and — crucially — no
// classes are LOADED: discovery reads the index as text straight out of each jar (or directory),
// so it is safe to run in completion mode, and the user's classes (which may contain a different
// version of Soundness than fume's own) stay untouched until a suite is actually invoked, in a
// separate JVM.
object Suites:
  private val index: Text = t"META-INF/services/probably.Suite"

  // Expands one classpath entry containing glob wildcards against the filesystem, via
  // galilei's `Path#glob` — kaleidoscope's full grammar (`*`, `?`, `[a-z]`, `[!a-z]`, and a
  // whole-`**` segment spanning any number of directories), where a `*` never crosses a `/` —
  // with relative entries resolved against `base`. A glob matching nothing contributes
  // nothing; an entry without a wildcard passes through verbatim, existent or not. Matches are
  // sorted, so the resulting suite order is stable across invocations.
  private def wildcard(entry: Text): Boolean =
    entry.contains(t"*") || entry.contains(t"?") || entry.contains(t"[")

  def expand(base: Text, entry: Text): List[Text] =
    import filesystemBackends.javaBaseFilesystem
    import filesystemOptions.dereferenceSymlinks
    // Sorting now names its algorithm (soundness 0.64.0); timsort is the old default.
    import sortingAlgorithms.timsort
    // Not yet re-exported through the `soundness` umbrella.
    import galilei.glob

    if !wildcard(entry) then List(entry) else
      safely:
        val absolute: Boolean = entry.starts(t"/")
        val root: Path on Linux = (if absolute then t"/" else base).as[Path on Linux]
        val pattern: Glob = Glob.parse(if absolute then entry.skip(1) else entry)

        root.glob(pattern).map(_.encode).order { (path: Text) => path.s }

      . or(List())

  // The suite classes on the classpath, in first-appearance order, deduplicated. Unreadable
  // entries contribute nothing rather than failing: completion must never break because a
  // configured jar has not been built yet.
  def discover(classpath: LocalClasspath): List[Text] =
    val suites: List[Text] =
      classpath.entries.bind[List[Text], Text, List[Text]] { entry => services(entry) }

    suites.distinct

  // The index entries of one classpath element, read as a RESOURCE through a classloader over
  // that element alone (child-first, platform parent — nothing of fume's is visible, and no
  // class is ever loaded by a resource lookup). The JDK's zip handling is what makes this the
  // right route: assembly jars often carry a prepended launcher preamble (Mill's
  // `prependShellScript`), which a strict zip parse rejects but a `URLClassLoader` tolerates —
  // and a directory entry works identically. Comment lines (the beneficence plugin writes
  // `# source: …`) and blanks are skipped.
  private def services(entry: Classpath.Entry): List[Text] =
    // The resource bytes are read through the underlying JDK classloader rather than
    // hellenism's `Classloader#apply`: the latter's `logs`-sugared signature leaves a fresh
    // root capability on its `Data` result, which capture checking cannot admit into the
    // enclosing `safely` block (a hellenism/CC interop wart worth fixing upstream).
    val content: Optional[Text] = entry match
      case entry: (Classpath.Entry.Directory | Classpath.Entry.Jar) =>
        safely(LocalClasspath(List(entry)*).classloader().java).let: loader =>
          Optional(loader.getResourceAsStream(index.s)).let: stream =>
            String(stream.readAllBytes(), "UTF-8").tt

      case _ =>
        Unset

    content.lay(List()): content =>
      content.cut(t"\n").map(_.trim).filter: line =>
        line != t"" && !line.starts(t"#")

  // Invokes one suite IN-PROCESS: the classpath is loaded in a fresh child-first classloader
  // whose parent is the PLATFORM loader, so the suite's classes (including whatever version of
  // Soundness they were built against) are fully isolated from fume's own, and each invocation
  // gets fresh suite state. The suite object's non-exiting `Suite#invoke` is called through
  // the STRUCTURAL type below: fume cannot name `probably.Suite` (the suite's world is
  // deliberately not fume's), but it can describe the one member it needs, and
  // `reflectiveSelectable` compiles the call into the reflective lookup — erased to
  // `invoke(String[]): int`, so only JDK types cross the boundary. Structural selection
  // dispatches on an INSTANCE, so the module instance is fetched from the `<suite>$` class's
  // `MODULE$` field rather than going through the static forwarder.
  //
  // A suite writes its report through the JVM's own `System.out`/`System.err`, which under the
  // Ethereal daemon belong to the daemon process, not the client; both are pointed at the
  // invocation's stdio for the duration (suite runs are serial, so the global swap is safe).
  //
  // Returns `Unset` when the suite predates `Suite#invoke` (no such method), so the caller can
  // fall back to a forked JVM.
  //
  // The structural member matches `Suite`'s single-`Text` entry point, whose arguments are
  // newline-separated in one value (a selection term can never contain a newline): `Text`
  // erases to `String`, so the compiled lookup is `invoke(String): int`. The array-taking form
  // cannot be described structurally today: the compiler fails to pickle the `classOf` of an
  // array type it synthesizes for the call, under capture checking (a proscala bug).
  private type Invocable = { def invoke(arguments: Text): Int }

  def invoke(classpath: LocalClasspath, suite: Text, args: List[Text])(using stdio: Stdio)
  :   Optional[Exit] =

    import scala.reflect.Selectable.reflectiveSelectable

    val loader: Classloader = classpath.classloader()
    val arguments: Text = args.join(t"\n")

    // The streams are swapped BEFORE the suite class is touched: loading the module class
    // runs the object initializer, which constructs the suite's default `Runner` — and that
    // captures `System.out` at construction, so it must already be the invocation's.
    val out = jl.System.out.nn
    val err = jl.System.err.nn
    jl.System.setOut(stdio.out)
    jl.System.setErr(stdio.err)

    // `safely` absorbs every `Exception` the reflective machinery can produce — a missing
    // module class or `MODULE$` field, a `NoSuchMethodException` from a Probably too old to
    // have `Suite#invoke`, an `InvocationTargetException` — into `Unset`, and the caller's
    // response to all of them is the same: fall back to a forked JVM. The `try`/`finally`
    // remains (with no `catch`): it is resource restoration, not error handling.
    try
      safely:
        loader.on(t"$suite$$").let: moduleClass =>
          val instance: Any = moduleClass.getField("MODULE$").nn.get(null).nn

          loader.use:
            val code = instance.asInstanceOf[Invocable].invoke(arguments)
            if code == 0 then Exit.Ok else Exit.Fail(code)

    finally
      // The suite writes through non-auto-flushing `PrintStream`s; flush before the
      // originals are restored, or a suite's final lines can be lost in the buffer when
      // the invocation ends.
      stdio.out.flush()
      stdio.err.flush()
      jl.System.setOut(out)
      jl.System.setErr(err)

  // One test of a suite's SCHEDULE as tab-completion and `fume list --axes` see it: its wire
  // ref, kind (`check`, `bench`, `stress` or `profile`), tags, and axes with the values (or
  // declared bounds) a `--list` selection admits.
  case class Scheduled
     ( ref: TestEvent.Ref, kind: Text, tags: List[Text], axes: List[TestEvent.AxisSchedule] )

  // The schedule of one suite by the EVENT protocol (`--list` streamed as `TestScheduled`s), or
  // `Unset` for a suite whose Probably cannot stream it (predating the event stream, or built
  // against a Soundness with an incompatible event layout), which the caller lists as text.
  def schedule(classpath: LocalClasspath, suite: Text, args: List[Text])(using Stdio, Monitor)
  :   Optional[List[Scheduled]] =

    val rows: scala.collection.mutable.ListBuffer[Scheduled] =
      scala.collection.mutable.ListBuffer()

    // The abort thunk is passed explicitly: the DEFAULT argument's root capability cannot flow
    // into `safely`'s enclosing function under capture checking (as in `runSuite`).
    val outcome: Optional[EventStream.Outcome] =
      safely:
        EventStream.stream(classpath, suite, t"--list" :: args)(
          { case TestEvent.TestScheduled(ref, kind, _, tags, axes) =>
              rows.append(Scheduled(ref, kind, tags, axes))
            case _ => () },
          () => false)

    outcome match
      case EventStream.Outcome.Completed(_) =>
        val scheduled: List[Scheduled] = rows.toList.to(List)
        scheduled

      case _ =>
        Unset

  // The schedule of one suite from its TEXT listing (`<id>  <kind>  <path>`, the legacy
  // protocol every Probably since `Suite#invoke` speaks): ids and paths only, with the last
  // path segment taken as the moniker when it is a plain identifier (a name with spaces
  // cannot complete unquoted); no tags, no axes. The suite's output is captured, not printed.
  def listing(classpath: LocalClasspath, suite: Text, args: List[Text])(using Stdio)
  :   List[Scheduled] =

    val buffer = java.io.ByteArrayOutputStream()
    val print = java.io.PrintStream(buffer, true, "UTF-8")
    val capture: Stdio = Stdio(print, print, null, termcapDefinitions.basicTermcap)

    safely(invoke(classpath, suite, t"--list" :: args)(using capture))
    print.flush()

    Text(buffer.toString("UTF-8").nn).cut(t"\n").bind[List[Scheduled], Scheduled, List[Scheduled]]:
      line =>
        if line.length <= 8 then Nil else
          val id: Text = line.keep(6)

          line.skip(8).cut(t"  ") match
            case kind :: path =>
              val segments: List[Text] = path.join(t"  ").cut(t"/")
              val leaf: Text = segments.stdlib.lastOption.getOrElse(t"")

              val moniker: Optional[Text] =
                if leaf != t"" && !leaf.contains(t" ") && leaf != id then leaf else Unset

              val kindName: Text = if kind == t"test" then t"check" else kind
              List(Scheduled(TestEvent.Ref(id, leaf, moniker, segments, t"", 0), kindName, Nil, Nil))

            case _ =>
              Nil

  // The schedule of one suite by whichever protocol it speaks, with its output captured rather
  // than printed: the suite body runs, tests do not. Supervised here, since the caller (a
  // completion thunk, or `fume list`) need not hold a `Monitor`.
  def fetch(classpath: LocalClasspath, suite: Text, args: List[Text]): List[Scheduled] =
    import threading.platformThreading

    val buffer = java.io.ByteArrayOutputStream()
    val print = java.io.PrintStream(buffer, true, "UTF-8")
    given capture: Stdio = Stdio(print, print, null, termcapDefinitions.basicTermcap)

    val streamed: Optional[List[Scheduled]] =
      safely(supervise(schedule(classpath, suite, args)))

    streamed.or(listing(classpath, suite, args))

  // Every test on the classpath, from every suite's FULL schedule (no terms), cached in the
  // daemon so completion is instant after the first keystroke. Keyed by the classpath entries'
  // paths, modification times and sizes, as `Workspace` caches configurations: a rebuilt jar
  // is rescheduled on the next keystroke, and an unbuilt one costs an empty schedule until it
  // appears.
  private val cache: scala.collection.concurrent.TrieMap[Text, List[Scheduled]] =
    scala.collection.concurrent.TrieMap()

  private def fingerprint(classpath: LocalClasspath): Text =
    classpath.entries.map: entry =>
      // A `LocalClasspath` decoded from fume's settings holds only jars and directories; the
      // platform's own runtime image has no file to stat, and never carries a suite.
      val path: Text = entry match
        case Classpath.Entry.Jar(path)       => path
        case Classpath.Entry.Directory(path) => path
        case _                               => t"jrt"

      val file = java.io.File(path.s)
      t"$path@${file.lastModified}:${file.length}"
    . join(t"\n")

  def cached(classpath: LocalClasspath): List[Scheduled] =
    val key: Text = fingerprint(classpath)

    cache.get(key) match
      case Some(schedule) =>
        schedule

      case _ =>
        val schedule: List[Scheduled] =
          discover(classpath).bind[List[Scheduled], Scheduled, List[Scheduled]]: suite =>
            fetch(classpath, suite, Nil)

        cache(key) = schedule
        schedule
