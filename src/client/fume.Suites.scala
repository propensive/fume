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

import systems.javaSystem

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
    import filesystemBackends.virtualMachineFilesystem
    import filesystemOptions.dereferenceSymlinks.enabled
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

  // Tab-completion SUGGESTIONS for selection terms: each suite the selection admits is run
  // with `--list` (its output captured, not printed — the suite body runs, tests do not),
  // and every test contributes its stable 6-hex id — and its moniker, when it has one that
  // a shell can complete unquoted — described by its kind and full path. Fast enough per
  // keystroke because listing skips all execution, and evaluated LAZILY: the caller wraps
  // this in the completion's update thunk, so it runs only when a term is being completed.
  def terms(classpath: LocalClasspath, suite: Optional[Text]): List[Suggestion] =
    val kindNames: List[Text] = List(t"test", t"bench", t"stress", t"profile")

    val kinds: List[Suggestion] =
      kindNames.map: (kind: Text) =>
        Suggestion(t"kind:$kind", t"run only ${kind}s")

    val suites: List[Text] = suite.lay(discover(classpath)) { chosen => List(chosen) }

    val tests: List[Suggestion] =
      suites.bind[List[Suggestion], Suggestion, List[Suggestion]]: suiteName =>
        val buffer = java.io.ByteArrayOutputStream()
        val print = java.io.PrintStream(buffer, true, "UTF-8")
        val capture: Stdio = Stdio(print, print, null, termcapDefinitions.basicTermcap)

        safely(invoke(classpath, suiteName, List(t"--list"))(using capture))
        print.flush()

        Text(buffer.toString("UTF-8").nn).cut(t"\n")
        . bind[List[Suggestion], Suggestion, List[Suggestion]]: line =>
            if line.length <= 8 then Nil else
              val id: Text = line.keep(6)
              val rest: Text = line.skip(8)

              rest.cut(t"  ") match
                case kind :: path =>
                  val joined: Text = path.join(t"  ")
                  val hash = Suggestion(id, t"$kind  $joined")

                  // The last path segment is the moniker when one was declared (a plain
                  // identifier); a name with spaces cannot complete unquoted.
                  val leaf: Text = joined.cut(t"/").stdlib.lastOption.getOrElse(t"")

                  if leaf != t"" && !leaf.contains(t" ") && leaf != id
                  then List(hash, Suggestion(leaf, t"$kind  $joined"))
                  else List(hash)

                case _ =>
                  Nil

    kinds + (tests.distinct: List[Suggestion])
