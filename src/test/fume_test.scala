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
┃    Fume, version 0.2.0.                                                                          ┃
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

import java.io as ji
import java.nio.file as jnf

import soundness.*

// A wildcard import brings no givens: the `n"…"` tag literal's plane inference needs the
// moniker and tag planes' `Nominative`s in lexical scope, by name. The compiler reports both
// as unused — the literal's macro resolves them — but the tagged spread below does not
// compile without them.
import soundness.{nominative, taggingNominative}

import probably.TestEvent

object Tests extends Suite(m"Fume tests"):
  private def ref(id: Text, moniker: Optional[Text], path: List[Text]): TestEvent.Ref =
    TestEvent.Ref(id, path.stdlib.last, moniker, path, t"", 0)

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

  private def cores(suggestions: List[Suggestion]): List[Text] = suggestions.map(_.core)
  private def texts(suggestions: List[Suggestion]): List[Text] = suggestions.map(_.text)

  // A fresh project directory whose `.fume/config.tel` holds `content`, with a nested
  // `sub/dir` to invoke from, so the upward search is exercised. Rooted in a unique temporary
  // directory per call.
  private def project(content: Text): ji.File =
    val root = jnf.Files.createTempDirectory("fume-test").nn.toFile.nn
    val fumeDir = ji.File(root, ".fume").nn
    fumeDir.mkdirs()
    val nested = ji.File(root, "sub/dir").nn
    nested.mkdirs()
    jnf.Files.write(ji.File(fumeDir, "config.tel").nn.toPath, content.s.getBytes("UTF-8"))
    nested

  private def read(directory: ji.File, name: Text): Optional[Text] =
    Workspace.configurator(directory.getAbsolutePath.nn.tt).read(name)

  def run(): Unit =
    test(m"the version is set"):
      fumeVersion
    . assert(_ == t"0.2.0")

    test(m"a config file is located in an ancestor directory"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"classpath")
    . assert(_ == t"out/tests.jar")

    test(m"an absent keyword reads as Unset"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"failFast")
    . assert(_ == Unset)

    test(m"a directory with no config reads as Unset"):
      val empty = jnf.Files.createTempDirectory("fume-empty").nn.toFile.nn
      read(empty, t"classpath")
    . assert(_ == Unset)

    test(m"repeated classpath entries join with ':'"):
      read(project(t"tel 1.0\n\nclasspath out/a.jar\nclasspath out/b.jar\n"), t"classpath")
    . assert(_ == t"out/a.jar:out/b.jar")

    test(m"a bare keyword reads as true"):
      read(project(t"tel 1.0\n\nfail-fast\n"), t"failFast")
    . assert(_ == t"true")

    test(m"suites are discovered from a directory-form classpath entry"):
      val root = jnf.Files.createTempDirectory("fume-classes").nn.toFile.nn
      val services = ji.File(root, "META-INF/services").nn
      services.mkdirs()

      jnf.Files.write
        ( ji.File(services, "probably.Suite").nn.toPath,
          "# source: one.scala\nexample.Tests\n\n# source: two.scala\nother.Tests\n".getBytes("UTF-8") )

      Suites.discover(LocalClasspath(List(Classpath.Entry.Directory(root.getAbsolutePath.nn.tt))*))
    . assert(_ == List(t"example.Tests", t"other.Tests"))

    test(m"glob classpath entries expand, sorted, one segment at a time"):
      val root = jnf.Files.createTempDirectory("fume-glob").nn.toFile.nn
      ji.File(root, "b/test").nn.mkdirs()
      ji.File(root, "a/test").nn.mkdirs()
      ji.File(root, "a/other").nn.mkdirs()
      jnf.Files.write(ji.File(root, "b/test/out.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "a/test/out.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/*/test/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/a/test/out.jar", t"/b/test/out.jar"))

    test(m"glob classpath entries support ? and character ranges"):
      val root = jnf.Files.createTempDirectory("fume-glob2").nn.toFile.nn
      jnf.Files.write(ji.File(root, "m1.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "m2.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "n1.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/m?.jar").map(_.skip(base.length))
        + Suites.expand(base, t"$base/[n]1.jar").map(_.skip(base.length))
    . assert(_ == List(t"/m1.jar", t"/m2.jar", t"/n1.jar"))

    test(m"a whole-segment ** spans directories"):
      val root = jnf.Files.createTempDirectory("fume-globstar").nn.toFile.nn
      ji.File(root, "x/deep/test").nn.mkdirs()
      ji.File(root, "y").nn.mkdirs()
      jnf.Files.write(ji.File(root, "x/deep/test/out.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "y/out.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/**/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/x/deep/test/out.jar", t"/y/out.jar"))

    test(m"an edited config file is reparsed"):
      val directory = project(t"tel 1.0\n\nclasspath out/old.jar\n")
      val first = read(directory, t"classpath")
      val root = directory.getParentFile.nn.getParentFile.nn
      val file = ji.File(ji.File(root, ".fume"), "config.tel")
      jnf.Files.write(file.toPath, "tel 1.0\n\nclasspath out/renewed.jar\n".getBytes("UTF-8"))
      (first, read(directory, t"classpath"))
    . assert(_ == (t"out/old.jar", t"out/renewed.jar"))

    test(m"a started run is entered in the active ledger"):
      val id = Journal.start(t"1", t"out.jar", List(t"kind:bench"), List(t"a.Tests"))
      Journal.active.seek(_.id == id).let { run => (run.running, run.scheduled) }
    . assert(_ == (true, List(t"a.Tests")))

    test(m"a finished run moves to the completed ledger"):
      val id = Journal.start(t"1", t"out.jar", List(), List(t"b.Tests"))
      Journal.finish(id, Journal.Outcome.Passed, Unset)

      ( Journal.active.exists(_.id == id),
        Journal.completed.seek(_.id == id).let(_.outcome) )

    . assert(_ == (false, Journal.Outcome.Passed))

    test(m"each suite's verdict is recorded against its run"):
      val id = Journal.start(t"1", t"out.jar", List(), List(t"c.Tests", t"d.Tests"))
      Journal.record(id, t"c.Tests", true, Unset, 0L)
      Journal.record(id, t"d.Tests", false, Unset, 0L)
      Journal.finish(id, Journal.Outcome.Failed, Unset)

      Journal.completed.seek(_.id == id).let: run =>
        (run.suites.map(_.suite), run.failures)

    . assert(_ == (List(t"c.Tests", t"d.Tests"), 1))

    test(m"a run in flight names the suite it is running"):
      val id = Journal.start(t"1", t"out.jar", List(), List(t"e.Tests"))
      Journal.began(id, t"e.Tests")
      val during = Journal.active.seek(_.id == id).let(_.current)
      Journal.record(id, t"e.Tests", true, Unset, 0L)
      (during, Journal.active.seek(_.id == id).let(_.current))
    . assert(_ == (t"e.Tests", Unset))

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

    // A tagged, axial test of fume's own, so that `fume list --axes`, `tag:selection` and
    // `scale=` completion can be exercised against this very suite.
    test(m"the factor scales with the budget", n"selection")
    . over(Axis(t"scale")(1L, 2L, 4L)): scale =>
        Budget.factor(100_000_000_000L*scale, 100_000_000_000L)
    . assert((scale, factor) => factor == t"$scale.000000000")

