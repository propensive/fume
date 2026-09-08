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
import java.util.concurrent as juc

import soundness.*

import pyrocosm.{Action, Block, Event, Hints, Inline, Interface, Panel, Tone, hints}

// The runs the daemon has seen, for the web front-end: the board of every suite in flight,
// and the final blocks of every suite that has finished, kept for the runs the journal
// remembers. A run registers its board when serving is on, so a browser opened before or
// during a run watches the same cells the terminal shows.
object Server:
  case class Finished(suite: Text, blocks: List[Block])

  private val serving0: juc.atomic.AtomicBoolean = juc.atomic.AtomicBoolean(false)
  private val boards: juc.ConcurrentHashMap[Int, fume.Board] = juc.ConcurrentHashMap()
  private val finished: juc.ConcurrentHashMap[Int, List[Finished]] = juc.ConcurrentHashMap()

  def serving: Boolean = serving0.get
  def serving_=(value: Boolean): Unit = serving0.set(value)

  def attach(run: Int, board: fume.Board): Unit = boards.put(run, board)

  def detach(run: Int, suite: Text, blocks: List[Block]): Unit =
    boards.remove(run)
    finished.compute(run, (_, existing) => (if existing == null then Nil else existing) + List(Finished(suite, blocks)))

  def board(run: Int): Optional[fume.Board] = Optional(boards.get(run))
  def done(run: Int): List[Finished] = Optional(finished.get(run)).or(Nil)

  def forget(run: Int): Unit =
    boards.remove(run)
    finished.remove(run)

// The dashboard: the journal's runs to choose from, and the chosen run's report, live while it
// runs. Rebuilt from the journal and the registry a few times a second; each cell is assigned
// only when its content has changed, so a quiet dashboard sends nothing.
final class Dashboard():
  private val actions: juc.ConcurrentHashMap[Int, Action] = juc.ConcurrentHashMap()

  @scala.caps.unsafe.untrackedCaptures
  @volatile
  private var selected: Optional[Int] = Unset

  @scala.caps.unsafe.untrackedCaptures
  private var lastRuns: List[Block] = Nil

  @scala.caps.unsafe.untrackedCaptures
  private var lastLive: AnyRef | Null = null

  @scala.caps.unsafe.untrackedCaptures
  private var lastFinished: Int = -1

  @scala.caps.unsafe.untrackedCaptures
  private var lastRun: Optional[Int] = Unset

  val runs: pyrocosm.Live[List[Block]] = pyrocosm.Live(List(Block.paragraph(t"No runs yet.")))
  val content: pyrocosm.Live[List[Block]] = pyrocosm.Live(List(Block.paragraph(t"Select a run.")))
  val progress: pyrocosm.Live[List[Block]] = pyrocosm.Live(Nil)

  private def action(run: Int): Action = actions.computeIfAbsent(run, _ => Action(t"run-$run")).nn

  def handle(event: Event): Unit = event match
    case Event.Pressed(action) =>
      val chosen = actions.entrySet.nn.iterator.nn
      var found: Optional[Int] = Unset
      while chosen.hasNext do
        val entry = chosen.next.nn
        if entry.getValue == action then found = entry.getKey.nn.intValue
      found.let { run => selected = run; refresh() }

    case _ =>
      ()

  private def when(millis: Long): Text =
    val instant = java.time.Instant.ofEpochMilli(millis).nn
    val zoned = instant.atZone(java.time.ZoneId.systemDefault).nn
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").nn.format(zoned).nn.tt

  private def runItem(run: Journal.Run): Block.Item =
    val standing: Inline = run.outcome.lay(Inline.Toned(Tone.Accent, List(Inline.Symbol(pyrocosm.Glyph.Running)))):
      case Journal.Outcome.Passed  => Inline.Toned(Tone.Success, List(Inline.Symbol(pyrocosm.Glyph.Check)))
      case Journal.Outcome.Failed  => Inline.Toned(Tone.Failure, List(Inline.Symbol(pyrocosm.Glyph.Cross)))
      case Journal.Outcome.Aborted => Inline.Toned(Tone.Warning, List(Inline.Symbol(pyrocosm.Glyph.Warning)))

    val name: Text = run.current.or(run.suites.stdlib.lastOption.map(_.suite).getOrElse(run.scheduled.stdlib.headOption.getOrElse(t"run ${run.id}")))
    val label: List[Inline] =
      List(standing, Inline.Textual(t" "), Inline.Emphasis(Inline.text(name)), Inline.Textual(t" "),
          Inline.Toned(Tone.Muted, Inline.text(t"${when(run.started)} · ${run.client}")))

    val selectedMark: List[Inline] = if selected == run.id then List(Inline.Toned(Tone.Accent, List(Inline.Symbol(pyrocosm.Glyph.ArrowRight))), Inline.Textual(t" ")) else Nil
    Block.Item(List(Block.Paragraph(selectedMark + label)), action(run.id))

  // Rebuild what has changed. Called by the serving loop a few times a second, and after a
  // selection.
  def refresh(): Unit = synchronized:
    val active = Journal.active
    val completed = Journal.completed

    if selected.absent then active.stdlib.headOption.orElse(completed.stdlib.headOption).foreach { run => selected = run.id }

    val items: List[Block.Item] = (active + completed).map(runItem)
    val listing: List[Block] = if items.nil then List(Block.paragraph(t"No runs yet.")) else List(Block.Listing(false, items))

    if listing != lastRuns then
      lastRuns = listing
      runs() = listing

    selected.let: run =>
      val board = Server.board(run)
      val finished = Server.done(run)
      val live: AnyRef | Null = board.lay(null: AnyRef | Null) { board => board.results().stdlib }
      val changed = lastRun != run || finished.stdlib.length != lastFinished || !(live.asInstanceOf[AnyRef] eq lastLive.asInstanceOf[AnyRef])
      lastRun = run
      lastFinished = finished.stdlib.length
      lastLive = live

      if changed then
        val past: List[Block] = finished.bind: entry =>
          Block.Heading(2, Inline.text(entry.suite)) :: entry.blocks

        val current: List[Block] = board.lay(Nil: List[Block]) { board => Block.Heading(2, Inline.text(board.title)) :: board.results() }
        val all = past + current
        content() = if all.nil then List(Block.paragraph(t"Nothing recorded yet.")) else all
        // A run in flight shows its progress; a finished one, its outcome and totals.
        progress() = board.let(_.progress()).or:
          (active + completed).seek(_.id == run).lay(Nil: List[Block]): entry =>
            val outcome: Inline = entry.outcome.lay(Inline.Toned(Tone.Accent, Inline.text(t"running"))):
              case Journal.Outcome.Passed  => Inline.Toned(Tone.Success, Inline.text(t"passed"))
              case Journal.Outcome.Failed  => Inline.Toned(Tone.Failure, Inline.text(t"failed"))
              case Journal.Outcome.Aborted => Inline.Toned(Tone.Warning, Inline.text(t"aborted"))

            val totals: List[Inline] = entry.totals.lay(Nil: List[Inline]): totals =>
              List(Inline.Textual(t"  "), Inline.Figure(totals.passed.toDouble, 0), Inline.Textual(t" passed, "),
                  Inline.Figure(totals.failed.toDouble, 0), Inline.Textual(t" failed"))

            List(Block.Paragraph(outcome :: totals))

  val interface: Interface =
    Interface
      ( Inline.text(t"fume"),
        List
          ( Panel(Panel.Id(t"runs"), Panel.Role.Navigation, Inline.text(t"Runs"), runs, Panel.Priority.Important),
            Panel(Panel.Id(t"run"), Panel.Role.Primary, Unset, content, Panel.Priority.Essential, hints = Hints(hints.Follow)),
            Panel(Panel.Id(t"progress"), Panel.Role.Status, Unset, progress, Panel.Priority.Important) ) )
