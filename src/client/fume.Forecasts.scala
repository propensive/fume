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

import alphabets.hexLowerCase
import charDecoders.utf8Decoder
import charEncoders.utf8Encoder
import filesystemBackends.javaBaseFilesystem
import logging.silentLogging
import providers.javaBaseProvider
import systems.javaBaseSystem
import textSanitizers.skipSanitizer

// What the last run of a classpath taught about each of its suites — how many tests it ran and
// how long it took — kept on disk under the XDG state home (`~/.local/state/fume/forecasts`),
// one file per classpath, so the next run can say how far it has come before the suites have
// told it. Keyed by the classpath STRING, not its jars' fingerprint: a rebuild keeps its
// forecast. A suite that did not run keeps its last observation, so a run of one suite refines
// one line. Plain lines (`suite<TAB>tests<TAB>millis`): a cache, never a document.
object Forecasts:
  case class Observation(tests: Int, time: Duration)

  object Forecast:
    def empty: Forecast = Forecast(Map())

  // A classpath's forecast: an observation per suite it knows.
  final class Forecast(entries: Map[Text, Observation]):
    def apply(suite: Text): Optional[Observation] = entries(suite)
    def size: Int = entries.size
    def known(suite: Text): Boolean = entries.defines(suite)

  // The forecasts' directory, under the XDG state home; `Unset` if the environment names no
  // home to put it under.
  def directory(using Environment): Optional[Path on Linux] =
    safely(Xdg.stateHome[Path on Linux] / "fume" / "forecasts")

  private def digest(classpath: Text): Text = classpath.digest[Sha2[256]].serialize[Hex]

  // The digest is hex, so the name is always admissible.
  private def file(directory: Path on Linux, classpath: Text): Path on Linux =
    unsafely(directory / t"${digest(classpath)}.tsv")

  // One line of a forecast file as an entry, or nothing for a line that is not one.
  private def entry(line: Text): List[(Text, Observation)] = line.cut(t"\t") match
    case suite :: tests :: millis :: Nil =>
      val observation: Optional[Observation] =
        safely(tests.as[Int]).let: tests =>
          safely(millis.as[Long]).let { millis => Observation(tests, Duration(millis)) }

      observation.lay(Nil: List[(Text, Observation)]) { observation => List(suite -> observation) }

    case _ =>
      Nil

  // The entries of a forecast file: none when it is absent or cannot be read.
  private def read(path: Path on Linux): Map[Text, Observation] =
    if !path.existent() then Map()
    else safely(path.read[Text]).lay(Map())(_.cut(t"\n").bind(entry).to[Map])

  // The forecast for `classpath`, empty when there is none or it cannot be read.
  def load(classpath: Text, directory: Optional[Path on Linux]): Forecast =
    Forecast(directory.lay(Map()) { directory => read(file(directory, classpath)) })

  private def millis(time: Duration): Long = (time.value*1000.0).round

  private def line(entry: (Text, Observation)): Text =
    t"${entry(0)}\t${entry(1).tests}\t${millis(entry(1).time)}"

  private def learn(entries: Map[Text, Observation], run: Journal.SuiteRun)
  :   Map[Text, Observation] =

    run.totals.lay(entries): totals =>
      entries.define(run.suite, Observation(totals.total, run.duration))

  // Records a run's suites over the existing forecast; a suite without totals (a legacy or
  // forked run) teaches nothing, and a directory that could not be written is left as it is.
  def save(classpath: Text, suites: List[Journal.SuiteRun], directory: Optional[Path on Linux])
  :   Unit =

    directory.let: directory =>
      if suites.exists(_.totals.present) then
        val path = file(directory, classpath)
        val entries: Map[Text, Observation] = suites.fold(read(path))(learn)
        val content: Text = entries.to[List].map(line).join(t"\n") + t"\n"

        safely:
          if !directory.existent() then directory.create[Directory](CreateFlag.Parents)
          path.write(content)
