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

import java.nio.file as jnf
import java.util as ju

import soundness.*

// What the last run of a classpath taught about each of its suites — how many tests it ran and
// how long it took — kept on disk under the XDG state home (`~/.local/state/fume/forecasts`),
// one file per classpath, so the next run can say how far it has come before the suites have
// told it. Keyed by the classpath STRING, not its jars' fingerprint: a rebuild keeps its
// forecast. A suite that did not run keeps its last observation, so a run of one suite refines
// one line. Plain lines (`suite<TAB>tests<TAB>millis`): a cache, never a document.
object Forecasts:
  case class Observation(tests: Int, millis: Long)

  // A classpath's forecast: an observation per suite it knows.
  final class Forecast(entries: ju.HashMap[String, Observation]):
    def apply(suite: Text): Optional[Observation] = Optional(entries.get(suite.s))
    def size: Int = entries.size
    def known(suite: Text): Boolean = entries.containsKey(suite.s)

  object Forecast:
    def empty: Forecast = Forecast(ju.HashMap())

  def directory: Text =
    val xdg: String | Null = java.lang.System.getenv("XDG_STATE_HOME")
    val base: String = if xdg == null || xdg.isEmpty then s"${java.lang.System.getProperty("user.home")}/.local/state" else xdg
    t"$base/fume/forecasts"

  private def digest(classpath: Text): String =
    val bytes = java.security.MessageDigest.getInstance("SHA-256").nn.digest(classpath.s.getBytes("UTF-8")).nn
    val out = StringBuilder()
    var index = 0
    while index < bytes.length do
      val hex = Integer.toHexString(bytes(index) & 0xff).nn
      if hex.length < 2 then out.append('0')
      out.append(hex)
      index += 1
    out.toString

  private def file(directory: Text, classpath: Text): jnf.Path =
    jnf.Path.of(directory.s, s"${digest(classpath)}.tsv").nn

  private def read(path: jnf.Path): ju.HashMap[String, Observation] =
    val entries: ju.HashMap[String, Observation] = ju.HashMap()

    try
      if jnf.Files.exists(path) then
        val lines: ju.List[String] = jnf.Files.readAllLines(path).nn
        var index = 0
        while index < lines.size do
          val parts = lines.get(index).nn.split('\t')
          if parts.length == 3 then
            try entries.put(parts(0).nn, Observation(parts(1).nn.toInt, parts(2).nn.toLong))
            catch case _: NumberFormatException => ()
          index += 1
    catch case _: Exception => ()

    entries

  // The forecast for `classpath`, empty when there is none or it cannot be read.
  def load(classpath: Text, directory: Text = directory): Forecast = Forecast(read(file(directory, classpath)))

  private def learn(entries: ju.HashMap[String, Observation], run: Journal.SuiteRun): Boolean =
    run.totals match
      case Unset          => false
      case totals: Doc.Totals =>
        entries.put(run.suite.s, Observation(totals.total, run.duration))
        true

  // Records a run's suites over the existing forecast; a suite without totals (a legacy or
  // forked run) teaches nothing.
  def save(classpath: Text, suites: List[Journal.SuiteRun], directory: Text = directory): Unit =
    val path = file(directory, classpath)
    val entries: ju.HashMap[String, Observation] = read(path)
    var learned = false

    def observe(run: Journal.SuiteRun): Unit = if learn(entries, run) then learned = true
    suites.each(observe)

    if learned then
      val content = StringBuilder()
      val keys: ju.Iterator[String] = entries.keySet.nn.iterator.nn

      while keys.hasNext do
        val key: String = keys.next.nn
        val observation: Observation = entries.get(key).nn
        content.append(key).append('\t').append(observation.tests).append('\t').append(observation.millis).append('\n')

      try
        jnf.Files.createDirectories(path.getParent.nn)
        jnf.Files.writeString(path, content.toString)
        ()
      catch case _: Exception => ()
