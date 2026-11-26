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
import pyrocosm.Action

// How a test over several axes is laid out, and how far each such test has been turned from
// the way it was declared.
//
// A test over two or more axes is drawn as a grid and, for a benchmark, as grouped bars. Its
// axes, in order, have two roles: the LAST is the series — the grid's columns, and the bars
// within a group, one colour to a value — and the others, the first varying slowest, are the
// categories — the grid's rows, and the bar groups. A test is declared with its axes in an
// order, which is how it is first laid out; one turn moves the first axis to the end, so
// each axis takes the series in its turn, and as many turns as there are axes come back to
// the start. With two axes a turn is a transposition; with more, the turns reach every choice
// of series, and of the orders the categories might nest in, the one which follows from it.
//
// Whether a test can be turned follows from what is drawn, not from its axes' domains:
//
//  - a grid and a bar chart take every axis as categories — a numeric axis is its values in
//    order, as a discrete one is its values as declared — so any axis may be the series or
//    among the categories, and a test over two or more axes can always be turned;
//  - a test over one axis has one layout, and a test over none has no axes at all;
//  - a stress test's chart is lines over a continuous abscissa, concurrency, which is a
//    measure and not an axis of the test, with a line per test: its roles are fixed, and
//    there is nothing to turn.
object Rotations:
  val none: Rotations = Rotations(Map())

  // The axes after `count` turns: each turn moves the first to the end.
  def turn(axes: List[Text], count: Int): List[Text] =
    if axes.size < 2 then axes else
      val shift: Int = count%axes.size
      axes.skip(shift) + axes.keep(shift)

  // The roles of the axes of a layout: the categories, and the series.
  def roles(axes: List[Text]): (List[Text], Optional[Text]) = axes.reverse match
    case series :: categories => (categories.reverse, series)
    case _                    => (Nil, Unset)

  // What a layout is called beside the button which turns it: rows × columns.
  def describe(axes: List[Text]): Text =
    val (categories, series) = roles(axes)
    series.lay(t""): series => t"${categories.join(t", ")} × $series"

  // The button's mark: two arrows chasing each other round a circle, drawn in the colour of
  // the text it is set in.
  val icon: Text =
    val stroke: Text =
      t"""fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" """ +
        t"""stroke-linejoin="round""""

    t"""<svg viewBox="0 0 24 24">""" +
      t"""<path d="M4 12A8 8 0 0 1 18.93 8M19 3v5h-5" $stroke/>""" +
      t"""<path d="M20 12A8 8 0 0 1 5.07 16M5 21v-5h5" $stroke/>""" +
      t"""</svg>"""

// How many times each test, by its id, has been turned; a test nobody has turned is as declared.
case class Rotations(counts: Map[Text, Int]):
  def of(test: Text): Int = counts(test).or(0)
  def arrange(test: Text, axes: List[Text]): List[Text] = Rotations.turn(axes, of(test))
  def turned(test: Text): Rotations = Rotations(counts.define(test, of(test) + 1))

// The turns of one run's tests, as whoever watches its report has made them, and the action
// which turns each: pressed from the button beside the test, any number of times. One per
// board, so a turn applies to the one test of the one run it was pressed on, and lasts as
// long as the daemon remembers the run.
final class Rotator:
  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var rotations0: Rotations = Rotations.none

  @scala.caps.unsafe.untrackedCaptures
  private var actions: Map[Text, Action] = Map()

  def rotations: Rotations = mutex(rotations0)

  // The action turning a test, made when its button is first drawn.
  def action(test: Text): Action = mutex:
    actions(test).or:
      val action = Action(t"turn-$test")
      actions = actions.define(test, action)
      action

  // Turns the test `action` belongs to, if it is one of this rotator's.
  def press(action: Action): Boolean = mutex:
    actions.to[List].seek(_(1) == action).lay(false): pair =>
      rotations0 = rotations0.turned(pair(0))
      true
