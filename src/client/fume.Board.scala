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

import pyrocosm.{Activity, Block, Hints, Inline, Interface, Panel, hints}

// The live board as a Pyrocosm interface: one primary panel of the run's blocks, following the
// newest results, and the run's progress as the interface's activity. Events only MARK the
// board; `repaint`, called by a task of its own, rebuilds both from the model when something
// has changed since the last paint, so the thread consuming the run's events never renders,
// and a burst of events costs one repaint.
final class Board(model: Model, val title: Text, progress0: Optional[Progress] = Unset):
  private val dirty: Atomic[Boolean] = Atomic(true)

  val results: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)

  // The results again, with the dashboard's charts above their tables: for the web alone,
  // since it is not a panel of this interface, so the terminal never draws them.
  val webResults: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)
  private val charts: Charts = Charts()
  private val memo: Blocks.RowMemo = Blocks.RowMemo()

  // How the dashboard's readers have turned the run's tests over several axes: the web
  // results', and the finished report's, layouts. The terminal's are always as declared.
  private val rotator: Rotator = Rotator()

  def figures: Ledger[Text, pyrocosm.Figure] = charts.figures

  // Marks the board, or paints it now when forced: the final state must always be shown.
  def refresh(force: Boolean = false): Unit = if force then paint() else dirty() = true

  // Paints if anything has been marked since the last paint, or when forced: the repaint task
  // forces one every second, so a time-left estimate ticks while the run is quiet.
  def repaint(force: Boolean = false): Unit = if dirty.swap(false) || force then paint()

  private def paint(): Unit = synchronized:
    val state = model.state()
    val document = Documenting.document(state)
    results() = Blocks.board(state, document, memo = memo)

    // The web results, and the charts they carry, only while a dashboard can show them; laid
    // out as their tests have been turned, which is another document only once one has been.
    if Server.serving then
      val rotations: Rotations = rotator.rotations
      val figures = charts.refresh(state, rotations)

      val turned =
        if rotations == Rotations.none then document else Documenting.document(state, rotations)

      webResults() = Blocks.board(state, turned, figures, memo, rotator)

    interface.activities() = List(Blocks.progress(state, progress0))

  // Turns the test `action` is the button of, if it is one of this board's; the results are
  // the same until they are next painted, or reported.
  def turn(action: pyrocosm.Action): Boolean = rotator.press(action)

  // Whether any test of the run can be turned: whether the finished report might change.
  def turnable: Boolean = Documenting.turnable(model.state())

  // The finished run's report for the dashboard — its results table, its groups under their
  // charts, laid out as their tests have been turned, and whatever follows them.
  def report(extra: List[Block]): List[Block] = synchronized:
    val state = model.state()
    val rotations: Rotations = rotator.rotations
    val document = Documenting.document(state, rotations)
    Blocks.document(document, charts.refresh(state, rotations), rotator) + extra

  // The run's progress so far, for the dashboard's card of it.
  def activity: Optional[Activity] = interface.activities().head

  val interface: Interface =
    Interface
      ( Inline.text(title),
        List
          ( Panel
              ( Panel.Id(t"results"),
                Panel.Role.Primary,
                Unset,
                results,
                Panel.Priority.Essential,
                hints = Hints(hints.Follow, hints.terminal.Border.None) ) ),
        hints = Hints(hints.terminal.Occupancy.Fullscreen) )
