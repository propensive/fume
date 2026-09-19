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

import denominative.dysasymptotics.linearSize

// How far a run has come, and how far it has to go: the suites are known before anything runs,
// so the suite in flight is exact; the tests and the time ahead are forecast from the last run
// of the classpath (`Forecasts`), where it had one. The forecast is scaled by how the suites
// finished so far compared with their forecasts, so a slower machine or a heavier build is
// reflected as the run proceeds.
object Progress:
  // No time at all, the sum's starting point.
  val none: Duration = 0.0*Second

final class Progress(val suites: List[Text], forecast: Forecasts.Forecast):
  private val count: Int = suites.size

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var finished0: Int = 0

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var current0: Optional[Text] = Unset

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var currentStarted: Instant over Unix = Instant.of[Unix](0L)

  // Elapsed and forecast time over the suites finished so far, for the scaling ratio.
  @scala.caps.unsafe.untrackedCaptures
  @volatile private var observed: Duration = Progress.none

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var forecastTime: Duration = Progress.none

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var finishedSuites: List[Text] = Nil

  // `time` is when the suite began or ended: now, unless a test says otherwise.
  def begin(suite: Text, time: Instant over Unix = now()): Unit =
    current0 = suite
    currentStarted = time

  def end(suite: Text, time: Instant over Unix = now()): Unit =
    finished0 += 1
    finishedSuites = suite :: finishedSuites
    observed = observed + (time - currentStarted)
    forecast(suite).let { observation => forecastTime = forecastTime + observation.time }
    current0 = Unset

  def finished: Int = finished0
  def total: Int = count
  def current: Optional[Text] = current0

  // The suites the forecast knows, and the mean tests and time of one, standing in for a suite
  // it does not know.
  private def observation(suite: Text): List[Forecasts.Observation] =
    forecast(suite).lay(Nil: List[Forecasts.Observation])(List(_))

  private val known: List[Forecasts.Observation] =
    suites.bind[List[Forecasts.Observation], Forecasts.Observation, List[Forecasts.Observation]](observation(_))

  private def meanTests: Int = if known.nil then 0 else known.map(_.tests).fold(0)(_ + _)/known.size

  private def meanTime: Duration =
    if known.nil then Progress.none
    else known.map(_.time).fold(Progress.none)(_ + _)/known.size.toDouble

  // Whether any suite of the run is unknown to the forecast: the total is then approximate.
  def approximate: Boolean = known.size < count

  // The forecast number of tests over the whole run, or `Unset` with no forecast at all.
  def forecastTotal: Optional[Int] =
    if known.nil then Unset else suites.map(forecastTests(_)).fold(0)(_ + _)

  // The observed time so far against the forecast for the same suites, clamped: a wildly
  // different first suite should not swing the whole estimate.
  private def ratio: Double =
    if forecastTime.value <= 0.0 || observed.value <= 0.0 then 1.0
    else (observed.value/forecastTime.value).max(0.5).min(2.0)

  // The forecast time left: the unfinished suites' forecasts, the one in flight net of the
  // time it has had, scaled by the ratio; `Unset` with no forecast at all.
  // Named predicates and mappers rather than block lambdas, which crash the 3.9.0-p16
  // compiler inside implicit search (`wildApprox` assertion) when passed to `filter` or `map`.
  private def unfinished(suite: Text): Boolean = !finishedSuites.has(suite)

  private def expected(suite: Text, time: Instant over Unix): Duration =
    val forecast0: Duration = forecast(suite).let(_.time).or(meanTime)

    if current0 == suite then
      val left: Duration = forecast0 - (time - currentStarted)
      if left.value < 0.0 then Progress.none else left
    else
      forecast0

  private def forecastTests(suite: Text): Int = forecast(suite).let(_.tests).or(meanTests)

  def remaining(time: Instant over Unix = now()): Optional[Duration] =
    if known.nil then Unset else
      val pending: List[Text] = suites.filter(unfinished(_))
      pending.map(expected(_, time)).fold(Progress.none)(_ + _)*ratio

  // The status line: the suite in flight of the total, the tests done of the forecast total
  // (or simply done, with no forecast), and the time left. Named methods rather than lambdas
  // around the interpolations: a block lambda interpolating crashes the 3.9.0-p16 compiler
  // inside implicit search (`wildApprox` assertion).
  private def place: Text =
    val index: Int = (finished0 + (if current0.present then 1 else 0)).min(count)
    t"suite $index/$count"

  private def ofTotal(done: Int, total: Int): Text =
    val mark: Text = if approximate then t"≈" else t""
    t"${Figures.grouped(done)} of $mark${Figures.grouped(total)}"

  private def leftText(left: Duration): Text =
    t" · about ${Budget.show((left.value*1_000_000_000.0).round)} left"

  def caption(done: Int, time: Instant over Unix = now()): Text =
    val doneText: Text = t"${Figures.grouped(done)} done"
    val tests: Text = forecastTotal.lay(doneText)(ofTotal(done, _))
    val left: Text = remaining(time).lay(t"")(leftText(_))
    t"$place · $tests$left"
