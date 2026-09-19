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

import pyrocosm.{Action, Block, Event, Hints, Inline, Interface, Panel, Tone, Tool, hints}

// The runs the daemon has seen, for the web front-end: the board of every suite in flight,
// and the final blocks of every suite that has finished, kept for the runs the journal
// remembers. A run registers its board when serving is on, so a browser opened before or
// during a run watches the same cells the terminal shows.
object Server:
  case class Finished(suite: Text, blocks: List[Block])

  private val serving0: Atomic[Boolean] = Atomic(false)

  // Both registries are amended by the runs' threads and read by the dashboard's, so every
  // access is mutex-guarded, as the journal's are.
  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var boards: Map[Int, fume.Board] = Map()

  @scala.caps.unsafe.untrackedCaptures
  private var finished: Map[Int, List[Finished]] = Map()

  def serving: Boolean = serving0()
  def serving_=(value: Boolean): Unit = serving0() = value

  def attach(run: Int, board: fume.Board): Unit = mutex { boards = boards.define(run, board) }

  def detach(run: Int, suite: Text, blocks: List[Block]): Unit = mutex:
    boards = boards.omit(run)
    finished = finished.define(run, finished(run).or(Nil) + List(Finished(suite, blocks)))

  def board(run: Int): Optional[fume.Board] = mutex(boards(run))
  def done(run: Int): List[Finished] = mutex(finished(run).or(Nil))

  def forget(run: Int): Unit = mutex:
    boards = boards.omit(run)
    finished = finished.omit(run)

// The dashboard as the web front-end `Tool` serves from the daemon: launched once, for as long
// as the daemon lives, when a config says `serve` (on its `port`, or 8090), and stopped by
// `fume quit`. `fume serve` remains the interactive way to serve it, from a terminal.
object Dashboard:
  // The host's zone, for a run's time of day. Aviation has no notion of a system default, so
  // the JVM is asked for its name; UTC if aviation does not know it.
  private lazy val timezone: Timezone =
    safely(Timezone(java.time.ZoneId.systemDefault.nn.getId.nn.tt)).or(tz"UTC")

  val web: Tool.Web = new Tool.Web:
    def port: Int = 8090

    // The frontend holds the monitor and the error page, which outlive it; vouched pure so it
    // can be stopped from another invocation.
    @scala.caps.unsafe.untrackedCaptures
    @volatile
    private var frontend: Optional[pyrocosm.WebFrontend] = Unset

    // Serves until `stop`: the dashboard is rebuilt a few times a second by a ticker, for as
    // long as the front-end runs, exactly as the interactive `fume serve` loop rebuilds it.
    def serve(port: Int)(using Monitor, Probate): Unit =
      import webserverErrorPages.minimalErrorPage

      val dashboard = Dashboard()

      val running: pyrocosm.WebFrontend =
        scala.caps.unsafe.unsafeAssumePure(pyrocosm.WebFrontend(port, fallback = Assets.serve))

      frontend = running
      Server.serving = true

      try
        async:
          while Server.serving do
            dashboard.refresh()
            snooze(0.25*Second)

        running.run(dashboard.interface)(dashboard.handle)
      finally Server.serving = false

    def stop(): Unit = frontend.let(_.stop())

// The dashboard: the journal's runs to choose from, and the chosen run's report, live while it
// runs. Rebuilt from the journal and the registry a few times a second; each cell is assigned
// only when its content has changed, so a quiet dashboard sends nothing.
final class Dashboard():
  private val mutex: Mutex = Mutex()

  // The action selecting each run, by run id, made as the run first appears.
  @scala.caps.unsafe.untrackedCaptures
  private var actions: Map[Int, Action] = Map()

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

  // Lays a two-axis benchmark out the other way round: its second axis as the rows and the
  // bar groups, its first as the columns and the bars within a group.
  val transpose: pyrocosm.Toggle = pyrocosm.Toggle(t"transpose")
  val transposeControl: pyrocosm.Control = pyrocosm.Control.Toggle(transpose, Inline.text(t"Transpose axes"))

  private def action(run: Int): Action = mutex:
    actions(run).or:
      val action = Action(t"run-$run")
      actions = actions.define(run, action)
      action

  def handle(event: Event): Unit = event match
    case Event.Toggled(`transpose`, state) =>
      Documenting.transposed = state
      lastLive = null
      refresh()

    case Event.Pressed(action) =>
      val found: Optional[Int] = mutex(actions.to[List]).seek(_(1) == action).let(_(0))
      found.let { run => selected = run; refresh() }

    case _ =>
      ()

  // When a run started: relative to now within the hour — "just now", "5 minutes ago" — and
  // as a time of day beyond it. The dashboard is rebuilt a few times a second, so a relative
  // time keeps current.
  private def when(started: Instant over Unix): Text =
    val minutes: Long = ((now() - started).value/60.0).toLong

    if minutes < 1L then t"just now"
    else if minutes == 1L then t"1 minute ago"
    else if minutes < 60L then t"${minutes.toString} minutes ago"
    else
      import calendars.gregorianCalendar
      import timeFormats.railwayTimeFormat
      (started in Dashboard.timezone).time.show

  private def runItem(run: Journal.Run): Block.Item =
    val standing: Inline = run.outcome.lay(Inline.Toned(Tone.Accent, List(Inline.Symbol(pyrocosm.Glyph.Running)))):
      case Journal.Outcome.Passed  => Inline.Toned(Tone.Success, List(Inline.Symbol(pyrocosm.Glyph.Check)))
      case Journal.Outcome.Failed  => Inline.Toned(Tone.Failure, List(Inline.Symbol(pyrocosm.Glyph.Cross)))
      case Journal.Outcome.Aborted => Inline.Toned(Tone.Warning, List(Inline.Symbol(pyrocosm.Glyph.Warning)))

    // An agent's run carries the agent's icon, served by `Assets`; a human's carries nothing.
    val agent: List[Inline] = run.invoker.icon.lay(Nil: List[Inline]): icon =>
      List(Inline.Icon(Assets.location(icon), run.invoker.name), Inline.Textual(t" "))

    val name: Text = run.current.or(run.suites.last.let(_.suite).or(run.scheduled.prim.or(t"run ${run.id}")))

    val detail: List[Inline] =
      List(Inline.Emphasis(Inline.text(name)), Inline.Textual(t" "),
          Inline.Toned(Tone.Muted, Inline.text(t"${when(run.started)} · ${run.client}")))

    val label: List[Inline] = List(standing, Inline.Textual(t" ")) + agent + detail

    val selectedMark: List[Inline] = if selected == run.id then List(Inline.Toned(Tone.Accent, List(Inline.Symbol(pyrocosm.Glyph.ArrowRight))), Inline.Textual(t" ")) else Nil
    Block.Item(List(Block.Paragraph(selectedMark + label)), action(run.id))

  // Rebuild what has changed. Called by the serving loop a few times a second, and after a
  // selection.
  def refresh(): Unit = synchronized:
    val active = Journal.active
    val completed = Journal.completed

    if selected.absent then (active + completed).prim.let { run => selected = run.id }

    val items: List[Block.Item] = (active + completed).map(runItem)
    val listing: List[Block] = if items.nil then List(Block.paragraph(t"No runs yet.")) else List(Block.Listing(false, items))

    if listing != lastRuns then
      lastRuns = listing
      runs() = listing

    selected.let: run =>
      val board = Server.board(run)
      val finished = Server.done(run)
      val live: AnyRef | Null = board.lay(null: AnyRef | Null) { board => board.webResults().asInstanceOf[AnyRef] }
      val changed = lastRun != run || finished.size != lastFinished || !(live.asInstanceOf[AnyRef] eq lastLive.asInstanceOf[AnyRef])
      lastRun = run
      lastFinished = finished.size
      lastLive = live

      if changed then
        val past: List[Block] = finished.bind: entry =>
          Block.Heading(2, Inline.text(entry.suite)) :: entry.blocks

        val current: List[Block] = board.lay(Nil: List[Block]) { board => Block.Heading(2, Inline.text(board.title)) :: board.webResults() }
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
            Panel(Panel.Id(t"run"), Panel.Role.Primary, Unset, content, Panel.Priority.Essential, controls = List(transposeControl), hints = Hints(hints.Follow)),
            Panel(Panel.Id(t"progress"), Panel.Role.Status, Unset, progress, Panel.Priority.Important) ) )
