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

// Which tests reach which code. `fume run --coverage <class or source file>` has
// `probably.Instrumenter` recompile the units it names from their TASTy, with a probe at every
// method entry and branch; puts the recompiled classes ahead of the originals on the run's
// classpath; and reads back, from the suites' own `probably.Coverage`, which probes each test
// reached: there the runner credits a test with what its thread does, and what the threads it
// starts do.
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
  // unit's index and a probe's. The empty key is what was reached outside any test.
  case class Record(subjects: List[Subject], reached: Map[Text, List[(Int, Int)]])

  // The key `probably.Runner` records a test's coverage under.
  def key(ref: probably.TestEvent.Ref): Text = t"${ref.path.join(t"/")}#${ref.id}"

  // Where recompiled classes are kept, under the XDG cache home; `Unset` if the environment
  // names no home to put it under.
  def directory(using Environment): Optional[Text] =
    safely(Xdg.cacheHome[Path on Linux] / "fume" / "coverage").let(_.encode)

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

  // Plans the coverage of the units the terms name, recompiling them unless that has been done
  // before: the plan, or why there is none.
  def plan
    ( classpath: LocalClasspath, terms: List[Text], options: List[Text], cache: Optional[Text] )
  :   (Optional[Plan], List[Text]) =

    cache.lay((Unset, List(t"there is no cache directory to recompile into"))): cache =>
      val planned: probably.Instrumenter.Plan =
        probably.Instrumenter.plan
          ( plain(classpath().cut(t":")).map(_.s),
            plain(terms).map(_.s),
            plain(options).map(_.s),
            cache.s )

      if !planned.problems.isEmpty then (Unset, planned.problems.to(List).map(_.tt)) else
        val classes = pyrocosm.Classpaths.entry(planned.classes.tt)
        val covered: LocalClasspath = LocalClasspath((classes :: classpath.entries)*)

        (Plan(covered, planned.units.to(List).map(subject)), Nil: List[Text])

  // Has the suites' `probably.Coverage` — the one this classloader loads — open a collector
  // for each test from now on; false if there is none to ask.
  def activate(loader: Classloader): Boolean = probably.Instrumenter.activate(loader.java)

  // What the tests run through this classloader have reached since the last time it was asked.
  def drain(loader: Classloader): Map[Text, List[(Int, Int)]] =
    probably.Instrumenter.drain(loader.java).to(List).map: reached =>
      reached.test.tt -> reached.probes.to(List)

    . to[Map]

  // A record as text: a line for each unit, each probe, and each test, tab-separated.
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
      lines += t"test\t$test\t${probes.map { (unit, probe) => t"$unit:$probe" }.join(t" ")}"

    lines.toList.to(List).join(t"", t"\n", t"\n")

  def parse(text: Text): Record =
    def number(text: Text): Int = safely(text.as[Int]).or(0)

    val units: scm.ListBuffer[(Int, Text)] = scm.ListBuffer()
    val probes: scm.ListBuffer[(Int, Probe)] = scm.ListBuffer()
    val reached: scm.ListBuffer[(Text, List[(Int, Int)])] = scm.ListBuffer()

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

        case t"test" :: test :: pairs :: _ =>
          val list: List[(Int, Int)] =
            pairs.cut(t" ").filter(_ != t"").bind[List[(Int, Int)], (Int, Int), List[(Int, Int)]]:
              pair => pair.cut(t":") match
                case unit :: probe :: _ => List((number(unit), number(probe)))
                case _                  => Nil

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
      plain(record.reached.to[List]).map { (test, places) => (test, plain(places)) }

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
