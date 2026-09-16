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

import java.lang as jl

import soundness.*

import denominative.dysasymptotics.linearSize

// How far a run has come, and how far it has to go: the suites are known before anything runs,
// so the suite in flight is exact; the tests and the time ahead are forecast from the last run
// of the classpath (`Forecasts`), where it had one. The forecast is scaled by how the suites
// finished so far compared with their forecasts, so a slower machine or a heavier build is
// reflected as the run proceeds.
final class Progress(val suites: List[Text], forecast: Forecasts.Forecast):
  private val count: Int = suites.size

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var finished0: Int = 0

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var current0: Optional[Text] = Unset

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var currentStarted: Long = 0L

  // Elapsed and forecast time over the suites finished so far, for the scaling ratio.
  @scala.caps.unsafe.untrackedCaptures
  @volatile private var observedMillis: Long = 0L

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var forecastMillis: Long = 0L

  @scala.caps.unsafe.untrackedCaptures
  @volatile private var finishedSuites: List[Text] = Nil

  def begin(suite: Text, now: Long = jl.System.currentTimeMillis): Unit =
    current0 = suite
    currentStarted = now

  def end(suite: Text, now: Long = jl.System.currentTimeMillis): Unit =
    finished0 += 1
    finishedSuites = suite :: finishedSuites
    observedMillis += now - currentStarted
    forecast(suite).let { observation => forecastMillis += observation.millis }
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
  private def meanMillis: Long = if known.nil then 0L else known.map(_.millis).fold(0L)(_ + _)/known.size

  // Whether any suite of the run is unknown to the forecast: the total is then approximate.
  def approximate: Boolean = known.size < count

  // The forecast number of tests over the whole run, or `Unset` with no forecast at all.
  def forecastTotal: Optional[Int] =
    if known.nil then Unset else suites.map(forecastTests(_)).fold(0)(_ + _)

  // The observed time so far against the forecast for the same suites, clamped: a wildly
  // different first suite should not swing the whole estimate.
  private def ratio: Double =
    if forecastMillis <= 0L || observedMillis <= 0L then 1.0
    else (observedMillis.toDouble/forecastMillis.toDouble).max(0.5).min(2.0)

  // The forecast time left: the unfinished suites' forecasts, the one in flight net of the
  // time it has had, scaled by the ratio; `Unset` with no forecast at all.
  // Named predicates and mappers rather than block lambdas, which crash the 3.9.0-p16
  // compiler inside implicit search (`wildApprox` assertion) when passed to `filter` or `map`.
  private def unfinished(suite: Text): Boolean = !finishedSuites.has(suite)

  private def expected(suite: Text, now: Long): Long =
    val forecastMillis: Long = forecast(suite).let(_.millis).or(meanMillis)
    if current0 == suite then (forecastMillis - (now - currentStarted)).max(0L) else forecastMillis

  private def forecastTests(suite: Text): Int = forecast(suite).let(_.tests).or(meanTests)

  def remaining(now: Long = jl.System.currentTimeMillis): Optional[Long] =
    if known.nil then Unset else
      val pending: List[Text] = suites.filter(unfinished(_))
      val millis: Long = pending.map(expected(_, now)).fold(0L)(_ + _)
      (millis.toDouble*ratio).toLong

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

  private def leftText(millis: Long): Text = t" · about ${Budget.show(millis*1_000_000L)} left"

  def caption(done: Int, now: Long = jl.System.currentTimeMillis): Text =
    val doneText: Text = t"${Figures.grouped(done)} done"
    val tests: Text = forecastTotal.lay(doneText)(ofTotal(done, _))
    val left: Text = remaining(now).lay(t"")(leftText(_))
    t"$place · $tests$left"
