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

import pyrocosm.{Block, Hints, Inline, Interface, Panel, hints}

// The live board as a Pyrocosm interface: one primary panel of the run's blocks, following the
// newest results, and a status panel with the run's progress. `refresh` rebuilds both from the
// model, at most ten times a second, and the frontend showing them repaints.
final class Board(model: Model, val title: Text):
  private val throttle: Long = 100L

  @scala.caps.unsafe.untrackedCaptures
  @volatile
  private var painted: Long = 0L

  val results: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)
  val progress: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)

  def refresh(force: Boolean = false): Unit =
    val now = jl.System.currentTimeMillis

    if force || now - painted >= throttle then
      painted = now
      val state = model.state()
      results() = Blocks.board(state)
      progress() = List(Blocks.progress(state))

  val interface: Interface =
    Interface
      ( Inline.text(title),
        List
          ( Panel(Panel.Id(t"results"), Panel.Role.Primary, Unset, results, Panel.Priority.Essential,
                hints = Hints(hints.Follow, hints.terminal.Border.None)),
            Panel(Panel.Id(t"progress"), Panel.Role.Status, Unset, progress, Panel.Priority.Essential,
                hints = Hints(hints.terminal.Border.None)) ) )
