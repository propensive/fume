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

// `Relay` is fume's own message enum, not turbulence's `Relay`.
import soundness.{Relay as _, *}

// A wildcard import brings no givens: the `n"…"` tag literal's plane inference needs the
// moniker and tag planes' `Nominative`s in lexical scope, by name. The compiler reports both
// as unused — the literal's macro resolves them — but the tagged spread below does not
// compile without them.
import soundness.{nominative, taggingNominative}

import denominative.dysasymptotics.linearSize

import probably.TestEvent

import pyrocosm.Block

import charEncoders.utf8Encoder
import filesystemBackends.javaBaseFilesystem
import logging.silentLogging
import strategies.throwUnsafely
import systems.javaBaseSystem
import temporaryDirectories.systemTemporaryDirectory

object Tests extends Suite(m"Fume tests"):
  private def ref(id: Text, moniker: Optional[Text], path: List[Text]): TestEvent.Ref =
    TestEvent.Ref(id, path.last.or(t""), moniker, path, t"", 0)

  private def axis
     ( label: Text, domain: Text, values: List[Text], emergent: Boolean = false,
       least: Optional[Double] = Unset, most: Optional[Double] = Unset )
  :   TestEvent.AxisSchedule =

    TestEvent.AxisSchedule(label, domain, emergent, values, least, most)

  // A classpath's schedule as `Suites.cached` would yield it: a biaxial benchmark, a plain
  // check without a moniker, a stress sweep with a bounded emergent axis, and a decimal spread.
  private val schedule: List[Suites.Scheduled] =
    List
      ( Suites.Scheduled
          ( ref(t"a1b2c3", t"parseJson", List(t"jacinta", t"parseJson")),
            t"bench",
            List(t"slow"),
            List(axis(t"N", t"integral", List(t"4", t"8", t"64")),
                 axis(t"parser", t"discrete", List(t"jacinta", t"circe"))) ),
        Suites.Scheduled(ref(t"d4e5f6", Unset, List(t"jacinta", t"a plain test")), t"check", Nil, Nil),
        Suites.Scheduled
          ( ref(t"0a0b0c", t"sweep", List(t"turbulence", t"sweep")),
            t"stress",
            List(t"slow", t"heavy"),
            List(axis(t"N", t"integral", Nil, emergent = true, least = 2.0, most = 16.0)) ),
        Suites.Scheduled
          ( ref(t"fff000", t"ratio", List(t"maths", t"ratio")),
            t"check",
            Nil,
            List(axis(t"x", t"decimal", List(t"0.5", t"1.5"))) ) )

  // A small tree of suites and tests, as `Model` sees it: a root suite with a test of its own
  // declared before two sub-suites of one test each.
  private val root   = ref(t"ffffff", Unset, List(t"root"))
  private val suiteA = ref(t"aaaaaa", Unset, List(t"root", t"A"))
  private val suiteB = ref(t"bbbbbb", Unset, List(t"root", t"B"))
  private val testX  = ref(t"000001", Unset, List(t"root", t"x"))
  private val testA1 = ref(t"000002", Unset, List(t"root", t"A", t"a1"))
  private val testB1 = ref(t"000003", Unset, List(t"root", t"B", t"b1"))
  private val passed = TestEvent.Outcome(t"pass", 1L, Unset)

  private def paths(state: Model.State): List[Text] =
    state.lines.map:
      case Model.Line.SuiteLine(ref)   => ref.path.join(t"/")
      case Model.Line.EntryLine(entry) => entry.ref.path.join(t"/")

  private def cores(suggestions: List[Suggestion]): List[Text] = suggestions.map(_.core)
  private def texts(suggestions: List[Suggestion]): List[Text] = suggestions.map(_.text)

  // A fresh project directory whose `.pyrocosm/fume/config.tel` holds `content`, with a nested
  // `sub/dir` to invoke from, so the upward search is exercised. Rooted in a unique temporary
  // directory per call.
  // A fresh, empty directory under the system's temporary directory.
  private def scratch(): Path on Linux =
    val directory: Path on Linux = temporaryDirectory[Path on Linux] / Uuid().show
    directory.create[Directory]()

  private def project(content: Text): Path on Linux =
    val root = scratch()
    val fumeDir = root / ".pyrocosm" / "fume"
    fumeDir.create[Directory](CreateFlag.Parents)
    val nested = root / "sub" / "dir"
    nested.create[Directory](CreateFlag.Parents)
    (fumeDir / "config.tel").write(content)
    nested

  // An instant by its epoch milliseconds, for the tests that fix their own clock.
  private def at(millis: Long): Instant over Unix = Instant.of[Unix](millis)

  // The user's config plays no part here: an empty environment has no `XDG_CONFIG_HOME` and
  // no `HOME`, so only the project's `.pyrocosm/fume/config.tel` is read.
  private def read(directory: Path on Linux, name: Text): Optional[Text] =
    given Environment = _ => Unset
    Fume.configurator(directory.encode).read(name)

  def run(): Unit =
    // The build writes `META-INF/pyrocosm/fume/version` into the client's resources: a
    // release's `X.Y.Z`, a snapshot's `X.Y.Z-<hex>`, or a development build's tree hash,
    // `-dirty` if uncommitted.
    test(m"the version is set"):
      Fume.version
    . assert(_.s.matches("^\\d+\\.\\d+\\.\\d+(-[0-9a-f]{12}(-dirty)?)?$"))

    test(m"a config file is located in an ancestor directory"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"classpath")
    . assert(_ == t"out/tests.jar")

    test(m"an absent keyword reads as Unset"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"failFast")
    . assert(_ == Unset)

    test(m"a directory with no config reads as Unset"):
      read(scratch(), t"classpath")
    . assert(_ == Unset)

    test(m"repeated classpath entries join with ':'"):
      read(project(t"tel 1.0\n\nclasspath out/a.jar\nclasspath out/b.jar\n"), t"classpath")
    . assert(_ == t"out/a.jar:out/b.jar")

    test(m"a bare keyword reads as true"):
      read(project(t"tel 1.0\n\nfail-fast\n"), t"failFast")
    . assert(_ == t"true")

    test(m"suites are discovered from a directory-form classpath entry"):
      val root = scratch()
      val services = root / "META-INF" / "services"
      services.create[Directory](CreateFlag.Parents)

      (services / "probably.Suite")
      . write(t"# source: one.scala\nexample.Tests\n\n# source: two.scala\nother.Tests\n")

      Suites.discover(LocalClasspath(List(Classpath.Entry.Directory(root.encode))*))
    . assert(_ == List(t"example.Tests", t"other.Tests"))

    test(m"glob classpath entries expand, sorted, one segment at a time"):
      val root = scratch()
      (root / "b" / "test").create[Directory](CreateFlag.Parents)
      (root / "a" / "test").create[Directory](CreateFlag.Parents)
      (root / "a" / "other").create[Directory](CreateFlag.Parents)
      (root / "b" / "test" / "out.jar").write(t"x")
      (root / "a" / "test" / "out.jar").write(t"x")
      val base = root.encode

      Suites.expand(base, t"$base/*/test/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/a/test/out.jar", t"/b/test/out.jar"))

    test(m"glob classpath entries support ? and character ranges"):
      val root = scratch()
      (root / "m1.jar").write(t"x")
      (root / "m2.jar").write(t"x")
      (root / "n1.jar").write(t"x")
      val base = root.encode

      Suites.expand(base, t"$base/m?.jar").map(_.skip(base.length))
        + Suites.expand(base, t"$base/[n]1.jar").map(_.skip(base.length))
    . assert(_ == List(t"/m1.jar", t"/m2.jar", t"/n1.jar"))

    test(m"a whole-segment ** spans directories"):
      val root = scratch()
      (root / "x" / "deep" / "test").create[Directory](CreateFlag.Parents)
      (root / "y").create[Directory](CreateFlag.Parents)
      (root / "x" / "deep" / "test" / "out.jar").write(t"x")
      (root / "y" / "out.jar").write(t"x")
      val base = root.encode

      Suites.expand(base, t"$base/**/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/x/deep/test/out.jar", t"/y/out.jar"))

    test(m"an edited config file is reparsed"):
      val directory = project(t"tel 1.0\n\nclasspath out/old.jar\n")
      val first = read(directory, t"classpath")
      // The project root is two levels above `sub/dir`, where the config was read from.
      val file: Optional[Path on Linux] =
        directory.parent.let(_.parent).let(_ / ".pyrocosm" / "fume" / "config.tel")

      file.let(_.write(t"tel 1.0\n\nclasspath out/renewed.jar\n"))
      (first, read(directory, t"classpath"))
    . assert(_ == (t"out/old.jar", t"out/renewed.jar"))

    test(m"a started run is entered in the active ledger"):
      val id = Journal.start(t"1", Invoker.Human, t"out.jar", List(t"kind:bench"), List(t"a.Tests"), t"here")
      Journal.active.seek(_.id == id).let { run => (run.running, run.scheduled) }
    . assert(_ == (true, List(t"a.Tests")))

    test(m"a finished run moves to the completed ledger"):
      val id = Journal.start(t"1", Invoker.Human, t"out.jar", List(), List(t"b.Tests"), t"here")
      Journal.finish(id, Journal.Outcome.Passed, Unset)

      ( Journal.active.exists(_.id == id),
        Journal.completed.seek(_.id == id).let(_.outcome) )

    . assert(_ == (false, Journal.Outcome.Passed))

    test(m"each suite's verdict is recorded against its run"):
      val id = Journal.start(t"1", Invoker.Human, t"out.jar", List(), List(t"c.Tests", t"d.Tests"), t"here")
      Journal.record(id, t"c.Tests", true, Unset, at(0L))
      Journal.record(id, t"d.Tests", false, Unset, at(0L))
      Journal.finish(id, Journal.Outcome.Failed, Unset)

      Journal.completed.seek(_.id == id).let: run =>
        (run.suites.map(_.suite), run.failures)

    . assert(_ == (List(t"c.Tests", t"d.Tests"), 1))

    test(m"a run in flight names the suite it is running"):
      val id = Journal.start(t"1", Invoker.Human, t"out.jar", List(), List(t"e.Tests"), t"here")
      Journal.began(id, t"e.Tests")
      val during = Journal.active.seek(_.id == id).let(_.current)
      Journal.record(id, t"e.Tests", true, Unset, at(0L))
      (during, Journal.active.seek(_.id == id).let(_.current))
    . assert(_ == (t"e.Tests", Unset))

    test(m"a run under Codex is detected from CODEX_SANDBOX"):
      given Environment = name => if name == t"CODEX_SANDBOX" then t"1" else Unset
      Invoker.detect
    . assert(_ == Invoker.Codex)

    test(m"a run under Claude Code is detected from CLAUDECODE"):
      given Environment = name => if name == t"CLAUDECODE" then t"1" else Unset
      Invoker.detect
    . assert(_ == Invoker.Claude)

    test(m"a run is a human's unless a variable is set to 1"):
      given Environment = name => if name == t"CLAUDECODE" then t"0" else Unset
      Invoker.detect
    . assert(_ == Invoker.Human)

    // The build puts `res/` on the client's classpath, so each agent's icon is a resource.
    test(m"Claude's icon is served from the classpath"):
      Assets.asset(Assets.location(t"claude")).let(_.starts(t"<svg"))
    . assert(_ == true)

    test(m"Codex's icon is served from the classpath"):
      Assets.asset(Assets.location(t"codex")).let(_.starts(t"<svg"))
    . assert(_ == true)

    test(m"no other resource is served as an asset"):
      Assets.asset(t"/META-INF/pyrocosm/fume/version")
    . assert(_ == Unset)

    // The load gate's scale is fixed explicitly in these tests rather than derived from the
    // target: `Load.Scale.apply` sizes the top of the scale from the core count, which
    // differs between machines.
    test(m"the log scale places each octave at an equal fraction"):
      val scale = Load.Scale(1.0, 16.0, 40)
      List(1.0, 2.0, 4.0, 8.0, 16.0).map(scale.column(_))
    . assert(_ == List(0, 10, 20, 30, 40))

    test(m"a load below the bottom of the scale clamps to the left edge"):
      Load.Scale(1.0, 16.0, 40).column(0.1)
    . assert(_ == 0)

    test(m"an idle machine has no logarithm and clamps to the left edge"):
      Load.Scale(1.0, 16.0, 40).column(0.0)
    . assert(_ == 0)

    test(m"a load above the top of the scale clamps to the right edge"):
      Load.Scale(1.0, 16.0, 40).column(64.0)
    . assert(_ == 40)

    test(m"the ruler marks each power of two in subscript digits"):
      Load.ruler(Load.Scale(1.0, 16.0, 40), 46)
    . assert(_ == t"╷₁        ╷₂        ╷₄        ╷₈        ╷₁₆")

    test(m"a ruler with no room for the topmost label drops it"):
      Load.ruler(Load.Scale(1.0, 16.0, 40), 40)
    . assert(_ == t"╷₁        ╷₂        ╷₄        ╷₈")

    test(m"a narrow ruler crowds its marks rather than garbling them"):
      Load.ruler(Load.Scale(1.0, 16.0, 8), 8)
    . assert(_ == t"╷₁╷₂╷₄╷₈")

    test(m"the bar measures the load in eighths of a cell"):
      Load.Scale(1.0, 16.0, 40).eighths(4.0)
    . assert(_ == 160L)

    test(m"a wholly filled or wholly empty cell is a space on its own colour"):
      List(Load.glyph(0), Load.glyph(8))
    . assert(_ == List(t" ", t" "))

    test(m"a cell the fill boundary falls inside takes an eighth-block glyph"):
      List(Load.glyph(1), Load.glyph(3), Load.glyph(7))
    . assert(_ == List(t"▏", t"▍", t"▉"))

    test(m"the bar spans exactly the width it is given"):
      Load.bar(4.0, 8.0, Load.Scale(1.0, 16.0, 40)).plain
    . assert(_ == t" "*40)

    test(m"a cell below the target is coloured 'pass'"):
      Load.colour(8, 29, 30)
    . assert(_ == Palette.pass)

    test(m"a cell at or past the target is coloured 'warning'"):
      Load.colour(8, 30, 30)
    . assert(_ == Palette.warning)

    test(m"an unfilled cell is track, whichever side of the target it falls"):
      List(Load.colour(0, 29, 30), Load.colour(0, 31, 30))
    . assert(_ == List(Palette.track, Palette.track))

    test(m"a load average renders to two decimal places"):
      List(Load.show(0.5), Load.show(12.0), Load.show(1.05))
    . assert(_ == List(t"0.50", t"12.00", t"1.05"))

    test(m"a bare target number is seconds"):
      Budget.parse(t"90")
    . assert(_ == 90_000_000_000L)

    test(m"target suffixes select seconds, minutes and hours"):
      List(Budget.parse(t"45s"), Budget.parse(t"10m"), Budget.parse(t"2h"), Budget.parse(t"1.5m"))
    . assert(_ == List(45_000_000_000L, 600_000_000_000L, 7_200_000_000_000L, 90_000_000_000L))

    test(m"a target must be a positive duration"):
      List(Budget.parse(t"0"), Budget.parse(t"-5"), Budget.parse(t"soon"), Budget.parse(t""))
    . assert(_ == List(Unset, Unset, Unset, Unset))

    test(m"the derived factor is the budget over the expectation"):
      List(Budget.factor(60_000_000_000L, 120_000_000_000L),
           Budget.factor(600_000_000_000L, 150_000_000_000L))
    . assert(_ == List(t"0.500000000", t"4.000000000"))

    test(m"a colossal expectation cannot round the factor to zero"):
      Budget.factor(1L, 100_000_000_000L)
    . assert(_ == t"0.000000001")

    test(m"budgets render in seconds below a minute and minutes above"):
      List(Budget.show(12_300_000_000L), Budget.show(246_000_000_000L))
    . assert(_ == List(t"12.3s", t"4m06s"))

    test(m"flags lower to wire terms: kinds, tags, axes, exclusions, then terms"):
      Selection.lower
        ( List(t"bench,stress", t"test"),
          List(t"slow,network", t"nightly"),
          List(t"N=4,8"),
          List(t"tag:flaky"),
          List(t"parseJson") )
    . assert:
        _ == List(t"kind:bench", t"kind:stress", t"kind:test", t"tag:slow,network", t"tag:nightly",
                  t"N=4,8", t"not:tag:flaky", t"parseJson")

    test(m"raw words lower the same way, skipping fume's other operands"):
      Selection.words:
        List(t"--bench", t"-t", t"slow", t"--axis=N=4", t"-x", t"tag:flaky", t"-c", t"out.jar",
             t"--suite", t"x.Tests", t"parseJson", t"--fail-fast", t"--target=90", t"jacinta/**")
    . assert:
        _ == List(t"kind:bench", t"tag:slow", t"N=4", t"not:tag:flaky", t"parseJson", t"jacinta/**")

    test(m"a bare word offers kinds, tags, ids and monikers, but no axes unidentified"):
      cores(Suggest(t"", Nil, schedule))
    . assert: cores =>
        cores.has(t"kind:bench") && cores.has(t"tag:slow") && cores.has(t"tag:heavy")
        && cores.has(t"a1b2c3") && cores.has(t"parseJson") && cores.has(t"d4e5f6")
        && !cores.has(t"a plain test") && !cores.exists(_.ends(t"="))

    test(m"a tag suggestion counts the tests carrying it"):
      Suggest(t"", Nil, schedule).seek(_.core == t"tag:slow").let(_.description)
    . assert(_ == t"2 tagged slow")

    test(m"once a test is identified, its axes are offered as incomplete stubs"):
      Suggest(t"", List(t"parseJson"), schedule).filter(_.core.ends(t"=")).map: suggestion =>
        (suggestion.core, suggestion.incomplete)
    . assert(_ == List((t"N=", true), (t"parser=", true)))

    test(m"an integral axis offers its values and range templates"):
      texts(Suggest(t"N=", List(t"parseJson"), schedule))
    . assert(_ == List(t"N=4", t"N=8", t"N=64", t"N=4..64", t"N=4..", t"N=..64"))

    test(m"after a comma the remaining values follow behind the typed prefix"):
      Suggest(t"N=4,", List(t"parseJson"), schedule).map { s => (s.prefix, s.core) }
    . assert(_ == List((t"N=4,", t"8"), (t"N=4,", t"64")))

    test(m"an axis word offers nothing when no test is identified"):
      Suggest(t"N=", List(t"kind:bench", t"tag:slow"), schedule)
    . assert(_ == Nil)

    test(m"a discrete axis offers its labels and no templates"):
      texts(Suggest(t"parser=", List(t"a1b2c3"), schedule))
    . assert(_ == List(t"parser=jacinta", t"parser=circe"))

    test(m"an emergent axis offers range templates from its declared bounds"):
      texts(Suggest(t"N=", List(t"sweep"), schedule))
    . assert(_ == List(t"N=2..16", t"N=2..", t"N=..16"))

    test(m"a decimal axis renders its extremes as written"):
      texts(Suggest(t"x=", List(t"ratio"), schedule))
    . assert(_ == List(t"x=0.5", t"x=1.5", t"x=0.5..1.5", t"x=0.5..", t"x=..1.5"))

    test(m"a glob identifies tests, and kind: and tag: terms narrow them"):
      ( texts(Suggest(t"N=", List(t"jacinta/**"), schedule)),
        texts(Suggest(t"N=", List(t"**", t"kind:stress"), schedule)),
        texts(Suggest(t"N=", List(t"**", t"tag:heavy"), schedule)) )
    . assert:
        _ == ( List(t"N=4", t"N=8", t"N=64", t"N=4..64", t"N=4..", t"N=..64"),
               List(t"N=2..16", t"N=2..", t"N=..16"),
               List(t"N=2..16", t"N=2..", t"N=..16") )

    test(m"values from several identified tests union, and templates span them"):
      texts(Suggest(t"N=", List(t"parseJson", t"sweep"), schedule))
    . assert(_ == List(t"N=4", t"N=8", t"N=64", t"N=2..64", t"N=2..", t"N=..64"))

    test(m"the --axis operand offers stubs until an = is typed"):
      (cores(Suggest.axes(t"", List(t"parseJson"), schedule)), texts(Suggest.axes(t"parser=", List(t"parseJson"), schedule)))
    . assert(_ == (List(t"N=", t"parser="), List(t"parser=jacinta", t"parser=circe")))

    test(m"tag and kind operands continue after a comma"):
      ( Suggest.tags(t"slow,", schedule).map { s => (s.prefix, s.core) },
        Suggest.kinds(t"bench,").map(_.core) )
    . assert(_ == (List((t"slow,", t"heavy")), List(t"test", t"stress", t"profile")))

    test(m"axes render for listing as values or bounds"):
      schedule.map { test => Suggest.axesText(test.axes) }
    . assert(_ == List(t"N=4,8,64;parser=jacinta,circe", t"-", t"N=2..16", t"x=0.5,1.5"))

    test(m"a listing pre-pass announcing every suite before any test still nests by declaration"):
      val model = Model()
      model.handle(TestEvent.SuiteStarted(root, 0L))
      model.handle(TestEvent.SuiteStarted(suiteA, 0L))
      model.handle(TestEvent.SuiteStarted(suiteB, 0L))
      model.handle(TestEvent.TestScheduled(testX, t"check", Unset, Nil, Nil))
      model.handle(TestEvent.TestScheduled(testA1, t"check", Unset, Nil, Nil))
      model.handle(TestEvent.TestScheduled(testB1, t"check", Unset, Nil, Nil))
      paths(model.state())
    . assert(_ == List(t"root", t"root/x", t"root/A", t"root/A/a1", t"root/B", t"root/B/b1"))

    test(m"a queued runner's per-test announcements seed rows without claiming a schedule"):
      val model = Model()
      model.handle(TestEvent.SuiteStarted(root, 0L))
      model.handle(TestEvent.TestScheduled(testX, t"check", Unset, Nil, Nil))
      val announced = model.state()
      model.listed()
      (announced.scheduled, paths(announced), model.state().scheduled)
    . assert(_ == (false, List(t"root", t"root/x"), true))

    test(m"a plain run's lines keep their arrival order"):
      val model = Model()
      model.handle(TestEvent.SuiteStarted(root, 0L))
      model.handle(TestEvent.TestCompleted(testX, t"check", Nil, passed, Nil, 0L))
      model.handle(TestEvent.SuiteStarted(suiteA, 0L))
      model.handle(TestEvent.TestCompleted(testA1, t"check", Nil, passed, Nil, 0L))
      model.handle(TestEvent.SuiteEnded(suiteA, 0L))
      model.handle(TestEvent.SuiteStarted(suiteB, 0L))
      model.handle(TestEvent.TestCompleted(testB1, t"check", Nil, passed, Nil, 0L))
      model.handle(TestEvent.SuiteEnded(suiteB, 0L))
      model.handle(TestEvent.SuiteEnded(root, 0L))
      paths(model.state())
    . assert(_ == List(t"root", t"root/x", t"root/A", t"root/A/a1", t"root/B", t"root/B/b1"))

    suite(m"Charts"):
      val testA2 = ref(t"000004", Unset, List(t"root", t"A", t"a2"))

      def bench(test: TestEvent.Ref, mean: Double, runs: Int, sd: Double,
          coordinates: List[TestEvent.Coordinate] = Nil): TestEvent.BenchmarkRecorded =
        TestEvent.BenchmarkRecorded(test, coordinates, 1000000L, 1L, runs, mean, mean, mean, sd, 95, Unset, Unset, Unset, 0L)

      def strain(test: TestEvent.Ref, concurrency: Int, operations: Long): TestEvent.StrainRecorded =
        TestEvent.StrainRecorded(test, Nil, concurrency, operations, 1000000000L, 0L, 0L, 0L, 0L, 0L, Unset, Unset, Unset, Unset, Unset, false, 0L)

      def integral(axis: Text, value: Long): TestEvent.Coordinate =
        TestEvent.Coordinate(axis, t"integral", false, Unset, value, Unset)

      def discrete(axis: Text, value: Text): TestEvent.Coordinate =
        TestEvent.Coordinate(axis, t"discrete", false, value, Unset, Unset)

      def benchModel(events: TestEvent*): Model =
        val model = Model()
        model.handle(TestEvent.SuiteStarted(root, 0L))
        model.handle(TestEvent.SuiteStarted(suiteA, 0L))
        events.each(model.handle(_))
        model

      def members(model: Model, kind: Text): List[Model.Entry] =
        Documenting.measurements(model.state()).seek(_(1) == kind).lay(Nil: List[Model.Entry])(_(2))

      val plainKey: Text = Charts.key(suiteA, t"bench", t"plain")
      val stressKey: Text = Charts.key(suiteA, t"stress", t"stress")

      val half: Double = Figures.tQuantile(95, 9)*100000.0/10.0.sqrt

      val expectedBars: List[Charts.Bar] =
        List(Charts.Bar(t"a1", 2.0, (2000000.0 - half)/1e6, (2000000.0 + half)/1e6), Charts.Bar(t"a2", 0.5, 0.5, 0.5))

      // The block kinds of a board, groups flattened, for checking where a figure stands.
      def kinds(blocks: List[Block]): List[Text] =
        blocks.bind[List[Text], Text, List[Text]]:
          case Block.Group(content) => kinds(content)
          case Block.Figure(_)      => List(t"figure")
          case Block.Table(_, _, _) => List(t"table")
          case Block.Heading(_, _)  => List(t"heading")
          case Block.Chart(_, _)    => List(t"chart")
          case _                    => List(t"other")

      test(m"the timebase follows the report's thresholds"):
        (Charts.timebase(50000.0).label, Charts.timebase(2000000.0).label, Charts.timebase(500000000.0).label)
      . assert(_ == (t"µs", t"ms", t"s"))

      test(m"a group's plain benchmarks are one series of bars, mean and confidence interval in the group's timebase"):
        val model = benchModel(bench(testA1, 2000000.0, 10, 100000.0), bench(testA2, 500000.0, 1, 0.0))
        Charts.plain(members(model, t"bench"))
      . assert(_ == Charts.BarData(List(Charts.Slice(t"mean", expectedBars)), Charts.Timebase(t"ms", 1e6)))

      test(m"a scheduled benchmark holds an empty bar until it records"):
        val model = benchModel(TestEvent.TestScheduled(testA1, t"bench", Unset, Nil, Nil), TestEvent.TestScheduled(testA2, t"bench", Unset, Nil, Nil), bench(testA2, 40000.0, 1, 0.0))
        Charts.plain(members(model, t"bench")).slices.bind(_.bars)
      . assert(_ == List(Charts.Bar(t"a1", 0.0, 0.0, 0.0), Charts.Bar(t"a2", 40.0, 40.0, 40.0)))

      test(m"a benchmark over one axis has a bar per value"):
        val model = benchModel(bench(testA1, 40000.0, 1, 0.0, List(integral(t"N", 8))), bench(testA1, 60000.0, 1, 0.0, List(integral(t"N", 64))))
        members(model, t"bench").prim.let(Charts.axial(_))
      . assert(_ == Charts.BarData(List(Charts.Slice(t"a1", List(Charts.Bar(t"8", 40.0, 40.0, 40.0), Charts.Bar(t"64", 60.0, 60.0, 60.0)))), Charts.Timebase(t"µs", 1e3)))

      test(m"a benchmark over two axes has a group per value of the first and a bar per value of the second"):
        val model = benchModel
          ( bench(testA1, 10000.0, 1, 0.0, List(integral(t"N", 4), discrete(t"parser", t"jacinta"))),
            bench(testA1, 20000.0, 1, 0.0, List(integral(t"N", 4), discrete(t"parser", t"circe"))),
            bench(testA1, 30000.0, 1, 0.0, List(integral(t"N", 8), discrete(t"parser", t"jacinta"))),
            bench(testA1, 40000.0, 1, 0.0, List(integral(t"N", 8), discrete(t"parser", t"circe"))) )

        members(model, t"bench").prim.let(Charts.axial(_)).let(_.slices)
      . assert:
          _ == List
            ( Charts.Slice(t"jacinta", List(Charts.Bar(t"4", 10.0, 10.0, 10.0), Charts.Bar(t"8", 30.0, 30.0, 30.0))),
              Charts.Slice(t"circe", List(Charts.Bar(t"4", 20.0, 20.0, 20.0), Charts.Bar(t"8", 40.0, 40.0, 40.0))) )

      val scheduledGrid: TestEvent.TestScheduled =
        TestEvent.TestScheduled(testA1, t"bench", Unset, Nil,
            List(axis(t"N", t"integral", List(t"4", t"8")), axis(t"parser", t"discrete", List(t"jacinta", t"circe"))))

      test(m"a scheduled crosstab is prefilled with empty bars, in the schedule's order"):
        val model = benchModel(scheduledGrid)
        members(model, t"bench").prim.let(Charts.axial(_)).let(_.slices)
      . assert:
          _ == List
            ( Charts.Slice(t"jacinta", List(Charts.Bar(t"4", 0.0, 0.0, 0.0), Charts.Bar(t"8", 0.0, 0.0, 0.0))),
              Charts.Slice(t"circe", List(Charts.Bar(t"4", 0.0, 0.0, 0.0), Charts.Bar(t"8", 0.0, 0.0, 0.0))) )

      test(m"a record into a prefilled crosstab raises its own bar and revises only that series"):
        val charts = Charts()
        val model = benchModel(scheduledGrid, bench(testA1, 200.0, 1, 0.0, List(integral(t"N", 4), discrete(t"parser", t"jacinta"))))
        val key = Charts.key(suiteA, t"bench", testA1.id)
        val figure: Optional[pyrocosm.Figure] = charts.refresh(model.state())(key)
        model.handle(bench(testA1, 100.0, 1, 0.0, List(integral(t"N", 8), discrete(t"parser", t"circe"))))
        charts.refresh(model.state())

        val part: Text = figure.let(_.revision()) match
          case pyrocosm.Figure.Revision.Replace(part, _) => part
          case _                                         => t"none"

        (part, members(model, t"bench").prim.let(Charts.axial(_)).let(_.slices.map(_.bars.map(_.mean))))
      . assert(_ == (t"series-1", List(List(0.2, 0.0), List(0.0, 0.1))))

      test(m"a scheduled axial benchmark's table lists its values blank until they record"):
        val model = benchModel(TestEvent.TestScheduled(testA1, t"bench", Unset, Nil, List(axis(t"N", t"integral", List(t"4", t"8")))), TestEvent.TestStarted(testA1, 0L))
        val document = Documenting.document(model.state())
        document.groups.bind(_.blocks).sweep { case Doc.Block.Table(title, _, rows, _) => (title.let(_.id), rows.size, rows.map(_.prim.or(Doc.Datum.Blank))) }
      . assert(_ == List((testA1.id, 2, List(Doc.Datum.Str(t"4"), Doc.Datum.Str(t"8")))))

      test(m"a recorded bar replaces only the bars when it fits the axes"):
        val charts = Charts()
        val model = benchModel(TestEvent.TestScheduled(testA1, t"bench", Unset, Nil, Nil), TestEvent.TestScheduled(testA2, t"bench", Unset, Nil, Nil), bench(testA2, 40000.0, 1, 0.0))
        val figure: Optional[pyrocosm.Figure] = charts.refresh(model.state())(plainKey)
        model.handle(bench(testA1, 20000.0, 1, 0.0))
        charts.refresh(model.state())
        figure.let(_.revision()) match
          case pyrocosm.Figure.Revision.Replace(part, _) => part
          case _                                         => t"none"
      . assert(_ == t"series-0")

      test(m"a stress curve is throughput against concurrency, each step once"):
        val model = benchModel(strain(testA1, 1, 100L), strain(testA1, 2, 180L), strain(testA1, 2, 999L), strain(testA1, 4, 300L))
        Charts.curves(members(model, t"stress"))
      . assert(_ == List(Charts.Curve(t"a1", List((1, 100.0), (2, 180.0), (4, 300.0)))))

      test(m"a refresh with nothing new keeps the figure and revises nothing"):
        val charts = Charts()
        val model = benchModel(strain(testA1, 1, 100L), strain(testA1, 2, 180L))
        val first: Optional[pyrocosm.Figure] = charts.refresh(model.state())(stressKey)
        val second: Optional[pyrocosm.Figure] = charts.refresh(model.state())(stressKey)
        (first.present, first.let(_.id) == second.let(_.id), first.let(_.revision()).absent)
      . assert(_ == (true, true, true))

      test(m"a new point revises the same figure in place"):
        val charts = Charts()
        val model = benchModel(strain(testA1, 1, 100L), strain(testA1, 2, 180L))
        val first: Optional[pyrocosm.Figure] = charts.refresh(model.state())(stressKey)
        model.handle(strain(testA1, 4, 300L))
        val second: Optional[pyrocosm.Figure] = charts.refresh(model.state())(stressKey)
        ( first.let(_.id) == second.let(_.id),
          second.let(_.revision()).present,
          second.let(_.svg.contains(t"series-0")).or(false) )
      . assert(_ == (true, true, true))

      test(m"a group appearing adds its figure"):
        val charts = Charts()
        val model = benchModel(strain(testA1, 1, 100L), strain(testA1, 2, 180L))
        val first = charts.refresh(model.state()).to[List].size
        model.handle(bench(testA2, 2000000.0, 10, 100000.0))
        val second = charts.refresh(model.state()).to[List].size
        (first, second)
      . assert(_ == (1, 2))

      test(m"the web board places each chart above its table, and the terminal board has none"):
        val charts = Charts()
        val model = benchModel(bench(testA2, 2000000.0, 10, 100000.0), strain(testA1, 1, 100L), strain(testA1, 2, 180L))
        val state = model.state()
        val document = Documenting.document(state)
        (kinds(Blocks.board(state, document, charts.refresh(state))), kinds(Blocks.board(state, document)))
      . assert(_ == (List(t"heading", t"figure", t"table", t"heading", t"figure", t"table"), List(t"heading", t"table", t"heading", t"other", t"chart", t"table")))

    suite(m"Progress"):
      def observed(suite: Text, tests: Int, millis: Long): Journal.SuiteRun =
        Journal.SuiteRun(suite, true, Doc.Totals(tests, 0, 0, 0, Nil), at(1000L), at(1000L + millis))

      def forecastDirectory(): Path on Linux = scratch()

      val classpath = t"/build/tests.jar:/build/lib.jar"

      test(m"a classpath with no forecast loads empty"):
        Forecasts.load(classpath, forecastDirectory()).size
      . assert(_ == 0)

      test(m"a saved run is read back, suite by suite"):
        val directory = forecastDirectory()
        Forecasts.save(classpath, List(observed(t"a.Tests", 120, 4000L), observed(t"b.Tests", 30, 1000L)), directory)
        val forecast = Forecasts.load(classpath, directory)
        (forecast(t"a.Tests"), forecast(t"b.Tests"), forecast(t"c.Tests"))
      . assert(_ == (Forecasts.Observation(120, Duration(4000L)), Forecasts.Observation(30, Duration(1000L)), Unset))

      test(m"a later run of one suite refines that line and keeps the others"):
        val directory = forecastDirectory()
        Forecasts.save(classpath, List(observed(t"a.Tests", 120, 4000L), observed(t"b.Tests", 30, 1000L)), directory)
        Forecasts.save(classpath, List(observed(t"b.Tests", 31, 1500L)), directory)
        val forecast = Forecasts.load(classpath, directory)
        (forecast(t"a.Tests"), forecast(t"b.Tests"))
      . assert(_ == (Forecasts.Observation(120, Duration(4000L)), Forecasts.Observation(31, Duration(1500L))))

      test(m"a suite without totals teaches nothing, and different classpaths do not share"):
        val directory = forecastDirectory()
        Forecasts.save(classpath, List(Journal.SuiteRun(t"a.Tests", true, Unset, at(0L), at(100L))), directory)
        Forecasts.save(t"/other.jar", List(observed(t"a.Tests", 5, 50L)), directory)
        Forecasts.load(classpath, directory).size
      . assert(_ == 0)

      def forecastOf(runs: Journal.SuiteRun*): Forecasts.Forecast =
        val directory = forecastDirectory()
        Forecasts.save(classpath, runs.to(List), directory)
        Forecasts.load(classpath, directory)

      test(m"the forecast total sums the known suites exactly"):
        val progress = Progress(List(t"a", t"b"), forecastOf(observed(t"a", 100, 1000L), observed(t"b", 50, 500L)))
        (progress.forecastTotal, progress.approximate)
      . assert(_ == (150, false))

      test(m"an unknown suite contributes the mean of the known, and marks the total approximate"):
        val progress = Progress(List(t"a", t"b", t"c"), forecastOf(observed(t"a", 100, 1000L), observed(t"b", 50, 500L)))
        (progress.forecastTotal, progress.approximate)
      . assert(_ == (225, true))

      test(m"with no forecast at all there is no total and no time left"):
        val progress = Progress(List(t"a", t"b"), Forecasts.Forecast.empty)
        (progress.forecastTotal, progress.remaining(at(0L)))
      . assert(_ == (Unset, Unset))

      test(m"the time left is the unfinished suites' forecast, the current one net of its elapsed time"):
        val progress = Progress(List(t"a", t"b", t"c"), forecastOf(observed(t"a", 10, 1000L), observed(t"b", 10, 2000L), observed(t"c", 10, 3000L)))
        progress.begin(t"a", at(0L))
        progress.end(t"a", at(1000L))
        progress.begin(t"b", at(1000L))
        progress.remaining(at(1500L))
      . assert(_ == Duration(4500L))

      test(m"a slow start scales the estimate, clamped to at most double"):
        val progress = Progress(List(t"a", t"b"), forecastOf(observed(t"a", 10, 1000L), observed(t"b", 10, 1000L)))
        progress.begin(t"a", at(0L))
        progress.end(t"a", at(5000L))
        progress.begin(t"b", at(5000L))
        progress.remaining(at(5000L))
      . assert(_ == Duration(2000L))

      test(m"a fast start scales the estimate, clamped to at least half"):
        val progress = Progress(List(t"a", t"b"), forecastOf(observed(t"a", 10, 1000L), observed(t"b", 10, 1000L)))
        progress.begin(t"a", at(0L))
        progress.end(t"a", at(100L))
        progress.begin(t"b", at(100L))
        progress.remaining(at(100L))
      . assert(_ == Duration(500L))

      test(m"the caption names the suite in flight, the tests of the total and the time left"):
        val progress = Progress(List(t"a", t"b", t"c"), forecastOf(observed(t"a", 5000, 10000L), observed(t"b", 9000, 90000L)))
        progress.begin(t"a", at(0L))
        progress.end(t"a", at(10000L))
        progress.begin(t"b", at(10000L))
        progress.caption(6234, at(20000L))
      . assert(_ == t"suite 2/3 · 6,234 of ≈21,000 · about 2m10s left")

      test(m"without a forecast the caption counts the tests done"):
        val progress = Progress(List(t"a", t"b"), Forecasts.Forecast.empty)
        progress.begin(t"a", at(0L))
        progress.caption(12, at(100L))
      . assert(_ == t"suite 1/2 · 12 done")

      test(m"budgets of an hour or more show hours and minutes"):
        Budget.show(3_720_000_000_000L)
      . assert(_ == t"1h02m")

    suite(m"Relay"):
      // The wire format between a controller and a worker: every message shape must survive
      // the round trip through the derived BinTEL codec, since a worker built from a different
      // fume decodes exactly these bytes.
      def roundTrip(message: Relay): Relay = Relay.codec.decode(Relay.codec.encode(message))

      test(m"a plan round-trips with its entries, suites and selection"):
        val entries = List(pyrocosm.Blobs.Entry(t"ab"*32, true, t"out/classes"),
                           pyrocosm.Blobs.Entry(t"cd"*32, false, t"lib.jar"))

        roundTrip(Relay.Plan(entries, List(t"a.Tests"), List(t"kind:bench"), t"0.5"))
      . assert(_ == Relay.Plan(List(pyrocosm.Blobs.Entry(t"ab"*32, true, t"out/classes"),
                                    pyrocosm.Blobs.Entry(t"cd"*32, false, t"lib.jar")),
                               List(t"a.Tests"), List(t"kind:bench"), t"0.5"))

      test(m"a plan with no max-load and no selection round-trips"):
        roundTrip(Relay.Plan(Nil, List(t"a.Tests"), Nil, Unset))
      . assert(_ == Relay.Plan(Nil, List(t"a.Tests"), Nil, Unset))

      test(m"the digests a worker needs round-trip"):
        roundTrip(Relay.Need(List(t"ab"*32, t"cd"*32)))
      . assert(_ == Relay.Need(List(t"ab"*32, t"cd"*32)))

      test(m"an empty need round-trips as empty"):
        roundTrip(Relay.Need(Nil))
      . assert(_ == Relay.Need(Nil))

      test(m"a blob's digest, offset and last flag round-trip"):
        roundTrip(Relay.Blob(t"ef"*32, 1048576, true))
      . assert(_ == Relay.Blob(t"ef"*32, 1048576, true))

      test(m"the messages carrying no payload round-trip"):
        List(roundTrip(Relay.Start), roundTrip(Relay.Abort), roundTrip(Relay.Done))
      . assert(_ == List(Relay.Start, Relay.Abort, Relay.Done))

      test(m"a suite's beginning and its frame announcement round-trip"):
        (roundTrip(Relay.Began(t"a.Tests")), roundTrip(Relay.Frame(t"a.Tests")))
      . assert(_ == (Relay.Began(t"a.Tests"), Relay.Frame(t"a.Tests")))

      test(m"captured output round-trips, newlines and all"):
        roundTrip(Relay.Captured(t"a.Tests", t"one\ntwo\n", t""))
      . assert(_ == Relay.Captured(t"a.Tests", t"one\ntwo\n", t""))

      test(m"each way a suite can end round-trips"):
        List(roundTrip(Relay.Ended(t"a.Tests", Relay.completed, 0, t"")),
             roundTrip(Relay.Ended(t"a.Tests", Relay.failed, 2, t"a trace")),
             roundTrip(Relay.Ended(t"a.Tests", Relay.legacy, 2, t"")))
      . assert(_ == List(Relay.Ended(t"a.Tests", Relay.completed, 0, t""),
                         Relay.Ended(t"a.Tests", Relay.failed, 2, t"a trace"),
                         Relay.Ended(t"a.Tests", Relay.legacy, 2, t"")))

      test(m"a rejection round-trips with its reason"):
        roundTrip(Relay.Rejected(t"the worker has no blob store"))
      . assert(_ == Relay.Rejected(t"the worker has no blob store"))

      // The fingerprint names the protocol in the handshake, so a worker whose `Relay` differs
      // in any field, order or case refuses the connection rather than misreading it.
      test(m"the protocol fingerprint is 32 bytes, and stable across summonings"):
        (Relay.codec.fingerprint.length, Relay.codec.protocol == Relay.codec.protocol)
      . assert(_ == (32, true))

      test(m"the worker's default port is not the dashboard's"):
        Relay.port != fume.Dashboard.web.port
      . assert(_ == true)

    // A tagged, axial test of fume's own, so that `fume list --axes`, `tag:selection` and
    // `scale=` completion can be exercised against this very suite.
    test(m"the factor scales with the budget", n"selection")
    . over(Axis(t"scale")(1L, 2L, 4L)): scale =>
        Budget.factor(100_000_000_000L*scale, 100_000_000_000L)
    . assert((scale, factor) => factor == t"$scale.000000000")

