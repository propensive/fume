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

import java.util.concurrent as juc

import soundness.*

import pyrocosm.{Block, Hints, Inline, Interface, Panel, hints}

// The live board as a Pyrocosm interface: one primary panel of the run's blocks, following the
// newest results, and a status panel with the run's progress. Events only MARK the board;
// `repaint`, called by a task of its own, rebuilds both panels from the model when something
// has changed since the last paint, so the thread consuming the run's events never renders,
// and a burst of events costs one repaint.
final class Board(model: Model, val title: Text):
  private val dirty: juc.atomic.AtomicBoolean = juc.atomic.AtomicBoolean(true)

  val results: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)
  val progress: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)

  // The results again, with the dashboard's charts above their tables: for the web alone,
  // since it is not a panel of this interface, so the terminal never draws them.
  val webResults: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)
  private val charts: Charts = Charts()
  private val memo: Blocks.RowMemo = Blocks.RowMemo()

  def figures: Ledger[Text, pyrocosm.Figure] = charts.figures

  // Marks the board, or paints it now when forced: the final state must always be shown.
  def refresh(force: Boolean = false): Unit = if force then paint() else dirty.set(true)

  // Paints if anything has been marked since the last paint. For the repaint task.
  def repaint(): Unit = if dirty.getAndSet(false) then paint()

  private def paint(): Unit = synchronized:
    val state = model.state()
    val document = Documenting.document(state)
    results() = Blocks.board(state, document, memo = memo)
    // The web results, and the charts they carry, only while a dashboard can show them.
    if Server.serving then webResults() = Blocks.board(state, document, charts.refresh(state), memo)
    progress() = List(Blocks.progress(state))

  val interface: Interface =
    Interface
      ( Inline.text(title),
        List
          ( Panel(Panel.Id(t"results"), Panel.Role.Primary, Unset, results, Panel.Priority.Essential,
                hints = Hints(hints.Follow, hints.terminal.Border.None)),
            Panel(Panel.Id(t"progress"), Panel.Role.Status, Unset, progress, Panel.Priority.Essential,
                hints = Hints(hints.terminal.Border.None)) ) )
