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

import scala.collection.mutable as scm

import soundness.*

import denominative.dysasymptotics.linearSize
import systems.javaBaseSystem
import textMetrics.uniformMetric

// Which tests reach which code. `fume run --coverage <classes or source files>` has
// `probably.Instrumenter` recompile the units it names from their TASTy, with a probe at every
// method entry and branch; puts the recompiled classes ahead of the originals on the run's
// classpath; and reads back, from the suites' own `probably.Coverage`, how many times each test
// reached each probe: there the runner credits a test with what its thread does, and what the
// threads it starts do.
//
// The recompilation must generate what the original compilation did, bar the probes, so it is
// done only by the compiler which wrote the TASTy — fume's own, or the run is refused — and is
// kept, under the cache directory, for as long as the units and the compiler are the same.
object Coverage:
  // A place a probe was put (see `probably.Instrumenter.Probe`), in a unit — the classes one
  // TASTy file defines, known by the name of the class it is named after.
  case class Probe
    ( index:     Int,
      kind:      Text,
      file:      Text,
      line:      Int,
      column:    Int,
      endLine:   Int,
      endColumn: Int,
      scope:     Text )

  case class Subject(index: Int, name: Text, probes: List[Probe])

  // What a run is to be covered by: the classpath with the recompiled classes ahead of the
  // rest, and the units they were recompiled from.
  case class Plan(classpath: LocalClasspath, subjects: List[Subject])

  // What a run recorded: for each test, by its key (`key` below), the probes it reached, as a
  // unit's index, a probe's, and the number of times the test reached it. The empty key is what
  // was reached outside any test.
  case class Record(subjects: List[Subject], reached: Map[Text, List[(Int, Int, Long)]])

  // The key `probably.Runner` records a test's coverage under.
  def key(ref: probably.TestEvent.Ref): Text = t"${ref.path.join(t"/")}#${ref.id}"

  // Where recompiled classes are kept, under the XDG cache home; `Unset` if the environment
  // names no home to put it under.
  def directory(using Environment): Optional[Text] =
    safely(Xdg.cacheHome[Path on Linux] / "fume" / "coverage").let(_.encode)

  // What a classpath holds that could be covered: its units, each known by the name of the
  // top-level class its TASTy file is named after (`iridescence.Rgb24`, which is the class and
  // its companion both), in the order a classloader would find them; and, for each which
  // records one, the source file it was compiled from, as its TASTy has it — relative to the
  // root of its build (`lib/iridescence/src/core/iridescence.Rgb24.scala`). The names are a
  // listing of each entry; the sources need every TASTy file read, which is done only when
  // something asks for them.
  class Census(val names: List[Text], sourced: => List[(Text, Text)]):
    lazy val sources: List[(Text, Text)] = sourced

    // The source files, each once, in name order.
    lazy val files: List[Text] =
      import sortingAlgorithms.timsort
      sources.map(_(1)).distinct.order: (file: Text) => file.s

  private val suffix: String = ".tasty"

  // The TASTy files of one classpath entry, as resource names (`iridescence/Rgb24.tasty`).
  private def listing(entry: Classpath.Entry): List[Text] = entry match
    case Classpath.Entry.Jar(path) =>
      safely:
        val zip: java.util.zip.ZipFile = java.util.zip.ZipFile(path.s)

        try
          val found: scm.ListBuffer[Text] = scm.ListBuffer()
          val entries = zip.entries().nn

          while entries.hasMoreElements do
            val name: String = entries.nextElement().nn.getName.nn
            if name.endsWith(suffix) then found += name.tt

          found.toList.to(List)
        finally zip.close()

      . or(Nil)

    case Classpath.Entry.Directory(path) =>
      safely:
        val root: java.nio.file.Path = java.nio.file.Path.of(path.s).nn
        val found: scm.ListBuffer[Text] = scm.ListBuffer()

        if java.nio.file.Files.isDirectory(root) then
          val walk = java.nio.file.Files.walk(root).nn

          try
            walk.forEach: file =>
              val name: String = root.relativize(file).nn.toString.replace('\\', '/').nn
              if name.endsWith(suffix) then found += name.tt
          finally walk.close()

        found.toList.to(List)

      . or(Nil)

    case _ =>
      Nil

  // The source each of an entry's TASTy files records, by resource name.
  private def origins(entry: Classpath.Entry, resources: List[Text]): List[(Text, Text)] =
    // The bytes are read for this alone and never written to, which is all that capture
    // checking, tracking an array as mutable state, would want to know.
    def origin(resource: Text, bytes: scala.Array[Byte] | Null): List[(Text, Text)] =
      val source: String | Null =
        if bytes == null then null
        else probably.Instrumenter.source(scala.caps.unsafe.unsafeAssumePure(bytes))

      Optional(source).lay(Nil: List[(Text, Text)]): source =>
        List((resource, source.replace('\\', '/').nn.tt))

    entry match
      case Classpath.Entry.Jar(path) =>
        safely:
          val zip: java.util.zip.ZipFile = java.util.zip.ZipFile(path.s)

          try
            resources.flatMap: resource =>
              origin(resource, zip.getInputStream(zip.getEntry(resource.s)).nn.readAllBytes())
          finally zip.close()

        . or(Nil)

      case Classpath.Entry.Directory(path) =>
        resources.flatMap: resource =>
          safely:
            origin
              ( resource,
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path.s, resource.s)) )

          . or(Nil)

      case _ =>
        Nil

  private def named(resource: Text): Text =
    resource.s.substring(0, resource.s.length - suffix.length).nn.replace('/', '.').nn.tt

  // Takes the census of a classpath. Where two entries hold the same unit, it is the first's,
  // as it is for a classloader.
  def census(classpath: LocalClasspath): Census =
    val seen: scm.HashSet[Text] = scm.HashSet()

    val held: List[(Classpath.Entry, List[Text])] = classpath.entries.map: entry =>
      entry -> listing(entry).filter(seen.add(_))

    Census
      ( held.flatMap(_(1)).map(named),
        held.flatMap { (entry, resources) => origins(entry, resources) }.map: (resource, source) =>
          (named(resource), source) )

  private val censuses: scala.collection.concurrent.TrieMap[Text, Census] =
    scala.collection.concurrent.TrieMap()

  // The census of a classpath, taken once for each build of its entries.
  def cached(classpath: LocalClasspath): Census =
    val key: Text = Suites.fingerprint(classpath)
    val cached: Optional[Census] = censuses.getOrElse(key, Unset)

    cached.or:
      val census: Census = this.census(classpath)
      censuses(key) = census
      census

  def wild(term: Text): Boolean = term.contains(t"*") || term.contains(t"?") || term.contains(t"[")

  // Whether a term names source files rather than classes: it is a path, or a Scala file.
  def pathlike(term: Text): Boolean = term.ends(t".scala") || term.contains(t"/")

  private def globbed(pattern: Text, text: Text): Boolean =
    safely(Glob.parse(pattern)).lay(false)(_.matches(text))

  // Whether a recorded source file is one a path term asks for. A file records its source
  // relative to wherever its build was rooted, so a term need only say how the path ends, and
  // may say more than was recorded:
  //
  //   - a glob (`lib/iridescence/**/*.scala`) matches the recorded path, or the end of it from
  //     any `/`;
  //   - a file (`core/iridescence.Rgb24.scala`) is the end of the recorded path, or the
  //     recorded path the end of it;
  //   - anything else is a directory (`lib/iridescence`), at the start of the recorded path or
  //     after any `/` of it, and asks for every file below.
  def sourced(asked: Text, recorded: Text): Boolean =
    if wild(asked) then
      def recur(tail: Text): Boolean =
        globbed(asked, tail) || {
          val slash: Int = tail.s.indexOf('/')
          slash >= 0 && recur(tail.skip(slash + 1))
        }

      recur(recorded)
    else if asked.ends(t".scala") then
      recorded == asked || recorded.ends(t"/$asked") || asked.ends(t"/$recorded")
    else
      val directory: Text = if asked.ends(t"/") then asked else t"$asked/"
      recorded.starts(directory) || recorded.contains(t"/$directory")

  // The units a term names, in the census's order:
  //
  //   - a path (`lib/iridescence/src/core/iridescence.Rgb24.scala`, `lib/iridescence`,
  //     `'lib/iridescence/**/*.scala'`) is every unit compiled from a source file it asks for;
  //   - a glob (`'iridescence.*'`, `'iridescence.Rgb*'`, `'iridescence.**'`) is every unit whose
  //     name it matches, where `*` and `?` stay within a package and `**` does not;
  //   - a unit's name (`iridescence.Rgb24`) is that unit: the class and its companion;
  //   - a package's name (`iridescence`) — a name no unit has, which others begin with — is
  //     every unit in the package and in the packages within it;
  //   - a name with more after a unit's (`iridescence.Rgb24.apply`) is the unit.
  def resolve(term: Text, census: Census): List[Text] =
    val text: Text = term.sub(t"\\", t"/")

    if pathlike(text) then
      val files: List[Text] = census.files.filter(sourced(text, _))
      census.sources.filter { (_, source) => files.has(source) }.map(_(0))
    else if wild(text) then
      val pattern: Text = text.sub(t".", t"/")
      census.names.filter: name => globbed(pattern, name.sub(t".", t"/"))
    else if census.names.has(text) then
      List(text)
    else
      val within: List[Text] = census.names.filter(_.starts(t"$text."))

      def enclosing(name: Text): List[Text] =
        val dot: Int = name.s.lastIndexOf('.')

        if dot < 0 then Nil else
          val outer: Text = name.keep(dot)
          if census.names.has(outer) then List(outer) else enclosing(outer)

      if within.nil then enclosing(text) else within

  // A list as the standard library's, which is what `probably.Instrumenter` takes and what the
  // report below sorts and groups.
  private def plain[element](list: List[element]): scala.List[element] =
    val buffer: scm.ListBuffer[element] = scm.ListBuffer()
    list.each { element => buffer += element }
    buffer.toList

  private def subject(unit: probably.Instrumenter.Unit): Subject =
    val probes: List[Probe] = unit.probes.to(List).map: probe =>
      Probe
        ( probe.index, probe.kind.tt, probe.file.tt, probe.startLine, probe.startColumn,
          probe.endLine, probe.endColumn, probe.scope.tt )

    Subject(unit.index, unit.name.tt, probes)

  // Plans the coverage of the units the terms name (see `resolve`), recompiling them unless
  // that has been done before: the plan, or why there is none.
  def plan
    ( classpath: LocalClasspath, terms: List[Text], options: List[Text], cache: Optional[Text] )
  :   (Optional[Plan], List[Text]) =

    val census: Census = cached(classpath)
    val found: List[(Text, List[Text])] = terms.map: term => term -> resolve(term, census)
    val missing: List[Text] = found.filter(_(1).nil).map(_(0))

    def unmatched(term: Text): Text =
      t"no class, package or source file on the classpath matches $term"

    if !missing.nil then (Unset, missing.map(unmatched)) else
      val uncached: (Optional[Plan], List[Text]) =
        (Unset, List(t"there is no cache directory to recompile into"))

      cache.lay(uncached): cache =>
        val planned: probably.Instrumenter.Plan =
          probably.Instrumenter.plan
            ( plain(classpath().cut(t":")).map(_.s),
              plain(found.flatMap(_(1)).distinct).map(_.s),
              plain(options).map(_.s),
              cache.s )

        if !planned.problems.isEmpty then (Unset, planned.problems.to(List).map(_.tt)) else
          val classes = pyrocosm.Classpaths.entry(planned.classes.tt)
          val covered: LocalClasspath = LocalClasspath((classes :: classpath.entries)*)

          (Plan(covered, planned.units.to(List).map(subject)), Nil: List[Text])

  // Has the suites' `probably.Coverage` — the one this classloader loads — open a collector
  // for each test from now on; false if there is none to ask.
  def activate(loader: Classloader): Boolean = probably.Instrumenter.activate(loader.java)

  // What the tests run through this classloader have reached since the last time it was asked,
  // and how many times.
  def drain(loader: Classloader): Map[Text, List[(Int, Int, Long)]] =
    probably.Instrumenter.drain(loader.java).to(List).map: reached =>
      reached.test.tt -> reached.probes.to(List)

    . to[Map]

  // A record as text: a line for each unit, each probe, and each test, tab-separated; a test's
  // line lists what it reached as `unit:probe:count`.
  def serialize(record: Record): Text =
    val lines: scm.ListBuffer[Text] = scm.ListBuffer()

    record.subjects.each: subject =>
      lines += t"unit\t${subject.index}\t${subject.name}\t${subject.probes.size}"

      subject.probes.each: probe =>
        lines +=
          List
            ( t"probe", subject.index.show, probe.index.show, probe.kind, probe.file,
              probe.line.show, probe.column.show, probe.endLine.show, probe.endColumn.show,
              probe.scope )
          . join(t"\t")

    record.reached.to[List].each: (test, probes) =>
      val reached: List[Text] = probes.map { (unit, probe, count) => t"$unit:$probe:$count" }
      lines += t"test\t$test\t${reached.join(t" ")}"

    lines.toList.to(List).join(t"", t"\n", t"\n")

  def parse(text: Text): Record =
    def number(text: Text): Int = safely(text.as[Int]).or(0)

    val units: scm.ListBuffer[(Int, Text)] = scm.ListBuffer()
    val probes: scm.ListBuffer[(Int, Probe)] = scm.ListBuffer()
    val reached: scm.ListBuffer[(Text, List[(Int, Int, Long)])] = scm.ListBuffer()

    text.cut(t"\n").each: line =>
      line.cut(t"\t") match
        case t"unit" :: index :: name :: _ =>
          units += ((number(index), name))

        case t"probe" :: unit :: index :: kind :: file :: line :: column :: endLine :: endColumn
            :: scope :: _ =>
          probes +=
            ( ( number(unit),
                Probe
                  ( number(index), kind, file, number(line), number(column), number(endLine),
                    number(endColumn), scope ) ) )

        // A record written before probes were counted has no count, and each probe in it was
        // reached at least once.
        case t"test" :: test :: places :: _ =>
          type Place = (Int, Int, Long)

          val list: List[Place] =
            places.cut(t" ").filter(_ != t"").bind[List[Place], Place, List[Place]]: place =>
              place.cut(t":") match
                case unit :: probe :: count :: _ =>
                  List((number(unit), number(probe), safely(count.as[Long]).or(1L)))

                case unit :: probe :: _ =>
                  List((number(unit), number(probe), 1L))

                case _ =>
                  Nil

          reached += ((test, list))

        case _ =>
          ()

    val subjects: List[Subject] = units.toList.to(List).map: (index, name) =>
      Subject(index, name, probes.toList.filter(_(0) == index).map(_(1)).to(List))

    Record(subjects, reached.toList.to(List).to[Map])

  // The report of a record, as lines: for each source file, how much of it the tests reached,
  // what none of them did, and which tests reached the most.
  def report(record: Record): List[Text] =
    type Place = (Int, Int)
    val lines: scm.ListBuffer[Text] = scm.ListBuffer()
    val tests: scala.List[(Text, scala.List[Place])] =
      plain(record.reached.to[List]).map: (test, places) =>
        (test, plain(places).map { (unit, probe, _) => (unit, probe) })


    val orphaned: scala.collection.immutable.Set[Place] =
      tests.filter(_(0) == t"").flatMap(_(1)).toSet

    val tested: scala.collection.immutable.Set[Place] =
      tests.filter(_(0) != t"").flatMap(_(1)).toSet

    val places: scala.List[(Place, Probe)] =
      plain(record.subjects).flatMap: subject =>
        plain(subject.probes).map { probe => ((subject.index, probe.index), probe) }

    places.groupBy(_(1).file.s).toList.sortBy(_(0)).foreach: (file, places) =>
      val total: Int = places.length
      val reached: Int = places.count { (place, _) => tested(place) }
      val outside: Int = places.count { (place, _) => orphaned(place) && !tested(place) }
      val percent: Int = if total == 0 then 0 else reached*100/total
      val aside: Text = if outside == 0 then t"" else t"; $outside more only outside any test"

      lines +=
        t"coverage of ${file.tt}: tests reach $reached of $total probes ($percent%)$aside"

      val unreached: scala.List[Probe] =
        places.filter { (place, _) => !tested(place) && !orphaned(place) }.map(_(1))
        . sortBy { probe => (probe.line, probe.column) }

      if unreached.nonEmpty then lines += t"  not reached:"

      unreached.take(40).foreach: probe =>
        val place: Text = t"${probe.line}:${probe.column}".pad(9)
        lines += t"    $place ${probe.kind.pad(7)} ${probe.scope}"

      if unreached.length > 40 then lines += t"    … and ${unreached.length - 40} more"

    val counts: scala.List[(Text, Int)] =
      tests.filter(_(0) != t"").map { (test, places) => (test, places.length) }.filter(_(1) > 0)
      . sortBy { (test, count) => (-count, test.s) }

    if counts.isEmpty then lines += t"no test reached any of it"
    else
      lines += t"reached by ${counts.length} tests; the probes each reaches:"
      counts.take(25).foreach { (test, count) => lines += t"  ${count.show.pad(5, Rtl)} $test" }
      if counts.length > 25 then lines += t"  … and ${counts.length - 25} more"

    lines.toList.to(List)
