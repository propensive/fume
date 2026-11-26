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
import pyrocosm.Machine
import pyrocosm.WebService
import pyrocosm.{Action, Activity, Block, Event, Hints, Inline, Interface, Panel, Tone, Tool, hints}

// The runs the daemon has seen, for the web front-end: the board of every suite in flight,
// and the final blocks of every suite that has finished, kept for the runs the journal
// remembers. A run registers its board when serving is on, so a browser opened before or
// during a run watches the same cells the terminal shows.
object Server:
  // A finished suite's report. Its board is kept with it when a test of it can be turned, so
  // that the report can be laid out again; `extra` is what follows the board's own blocks.
  // `version` counts the layouts it has had, which is how a dashboard knows to show it again.
  final class Finished
    ( val suite: Text, blocks0: List[Block], board: Optional[fume.Board], extra: List[Block] ):

    @scala.caps.unsafe.untrackedCaptures
    @volatile
    private var current: List[Block] = blocks0

    @scala.caps.unsafe.untrackedCaptures
    @volatile
    private var version0: Int = 0

    def blocks: List[Block] = current
    def version: Int = version0

    // Turns the test `action` is the button of, if it is one of this report's.
    def turn(action: Action): Boolean = board.lay(false): board =>
      synchronized:
        if !board.turn(action) then false else
          current = board.report(extra)
          version0 += 1
          true

  private val serving0: Atomic[Boolean] = Atomic(false)

  // The services this daemon is serving, by name — `dashboard`, `listener`, `mcp` — and port,
  // for the MCP server's `processes`.
  @scala.caps.unsafe.untrackedCaptures
  private var services0: Map[Text, Int] = Map()

  // Both registries are amended by the runs' threads and read by the dashboard's, so every
  // access is mutex-guarded, as the journal's are.
  private val mutex: Mutex = Mutex()

  @scala.caps.unsafe.untrackedCaptures
  private var boards: Map[Text, fume.Board] = Map()

  @scala.caps.unsafe.untrackedCaptures
  private var finished: Map[Text, List[Finished]] = Map()

  // Every run in flight registers its model, whether or not anyone is watching a board, so
  // the MCP server can answer for it live; and the pid of the JVM a forked suite runs in.
  @scala.caps.unsafe.untrackedCaptures
  private var models: Map[Text, Model] = Map()

  @scala.caps.unsafe.untrackedCaptures
  private var forks: Map[Text, Long] = Map()

  def serving: Boolean = serving0()
  def serving_=(value: Boolean): Unit = serving0() = value

  def announce(service: Text, port: Int): Unit = mutex:
    services0 = services0.define(service, port)

  def withdraw(service: Text): Unit = mutex { services0 = services0.omit(service) }
  def services: List[(Text, Int)] = mutex(services0.to[List])

  def attach(run: Text, board: fume.Board): Unit = mutex { boards = boards.define(run, board) }

  // A suite of the run has finished: its board's last report is kept for the dashboard, and
  // the board with it if the report has anything to turn.
  def detach(run: Text, suite: Text, board: fume.Board, extra: List[Block]): Unit =
    val kept: Optional[fume.Board] = if board.turnable then board else Unset
    finish(run, Finished(suite, board.report(extra), kept, extra))

  // A suite finished with nobody serving a dashboard to show its report on.
  def forgo(run: Text, suite: Text): Unit = finish(run, Finished(suite, Nil, Unset, Nil))

  private def finish(run: Text, report: Finished): Unit = mutex:
    boards = boards.omit(run)
    finished = finished.define(run, finished(run).or(Nil) + List(report))

  // A press of a test's turning button in a run's report: the test is turned, and the report
  // — the board's next paint while the run is in flight, a finished suite's blocks after —
  // laid out again. False if the action is not such a button's.
  def turn(run: Text, action: Action): Boolean =
    val live: Boolean = board(run).lay(false): board =>
      if !board.turn(action) then false else
        board.refresh(force = true)
        true

    live || done(run).exists(_.turn(action))

  def board(run: Text): Optional[fume.Board] = mutex(boards(run))
  def done(run: Text): List[Finished] = mutex(finished(run).or(Nil))

  def open(run: Text, model: Model): Unit = mutex { models = models.define(run, model) }
  def close(run: Text): Unit = mutex { models = models.omit(run); forks = forks.omit(run) }
  def model(run: Text): Optional[Model] = mutex(models(run))

  def fork(run: Text, pid: Long): Unit = mutex { forks = forks.define(run, pid) }
  def unfork(run: Text): Unit = mutex { forks = forks.omit(run) }
  def forked(run: Text): Optional[Long] = mutex(forks(run))

  def forget(run: Text): Unit = mutex:
    boards = boards.omit(run)
    finished = finished.omit(run)

// The dashboard as the web front-end `Tool` serves from the daemon: launched once, for as long
// as the daemon lives, when a config says `serve`, and stopped by `fume quit`. It is served
// behind the machine's Pyrocosm hub, at `/fume/` on the hub's origin (8090), from a port of its
// own the hub reaches it by; a `port` in a config serves it on that port too. `fume serve`
// remains the interactive way to serve it, from a terminal.
object Dashboard:
  // The host's zone, for a run's time of day. Aviation has no notion of a system default, so
  // the JVM is asked for its name; UTC if aviation does not know it.
  private lazy val timezone: Timezone =
    safely(Timezone(java.time.ZoneId.systemDefault.nn.getId.nn.tt)).or(tz"UTC")

  // Where the dashboard on `port` is found.
  def address(port: Int): Text = Fume.address(port)

  // A run in the list of runs: one mark, for how it stands — in flight, passed, failed, or
  // stopped short — and its name, which is its record's, with what else is worth knowing of
  // it in a note beneath, set smaller. The selected run's name is strong, and in the accent.
  def title(record: RunRecord, selected: Boolean): List[Inline] =
    val standing: Inline = record.outcome.lay(Inline.glyph(Tone.Accent, pyrocosm.Glyph.Running)):
      case RunRecord.passed => Inline.glyph(Tone.Success, pyrocosm.Glyph.Check)
      case RunRecord.failed => Inline.glyph(Tone.Failure, pyrocosm.Glyph.Cross)
      case _                => Inline.glyph(Tone.Warning, pyrocosm.Glyph.Warning)

    val name: Inline =
      if selected then Inline.Toned(Tone.Accent, List(Inline.Strong(Inline.text(record.name))))
      else Inline.Textual(record.name)

    List(standing, Inline.Textual(t" "), name)

  // The note beneath a run's name, its parts separated by a dot: `when` it started; how it
  // went — its tests' count, or how many of them failed, or, for a run with no totals, the
  // word for its outcome; who launched it, when that was not a human at a terminal — an
  // agent or an MCP client, with its icon, or the machine whose fume sent it here; and the
  // machine it ran on, when that is not `host`, this one. What a record holds besides — the
  // launching client's process id, the classpath, the selection — is the report's to show.
  def note(record: RunRecord, when: Text, host: Text): List[Inline] =
    def count(number: Int): Text = Figures.grouped(number)

    val result: Text = record.totals.lay(record.outcome.or(t"running")): totals =>
      val failed: Int = totals.failed + totals.aspireFailed

      if failed == 0 then t"${count(totals.total)} passed"
      else t"${count(failed)} of ${count(totals.total)} failed"

    val invoker: Invoker = Invoker.of(record.invoker)

    val launcher: List[List[Inline]] = invoker match
      case Invoker.Human  => Nil
      case Invoker.Remote => List(Inline.text(t"for ${record.client}"))
      case Invoker.Mcp    => List(agent(invoker, t"MCP"))
      case _              => List(agent(invoker, invoker.name))

    val machine: List[List[Inline]] =
      if record.machine == host || record.machine == t"" then Nil
      else List(Inline.text(t"on ${record.machine}"))

    val rest: List[List[Inline]] = List(Inline.text(result)) + launcher + machine
    Inline.text(when) + rest.bind: part => Inline.Textual(t" · ") :: part

  // An agent or a protocol in a note: its icon, served by `Assets`, and its name, which is
  // all the icon would say, so it has no description of its own.
  private def agent(invoker: Invoker, name: Text): List[Inline] =
    invoker.icon.lay(Inline.text(name)): icon =>
      List(Inline.Icon(Assets.location(icon), t""), Inline.Textual(t" $name"))

  // Served until `stop`, as every tool's web front-end is (`WebService`): the dashboard is
  // rebuilt a few times a second by the ticker, for as long as the front-end runs, exactly as
  // the interactive `fume serve` loop rebuilds it. The MCP server answers `/mcp` on the
  // dashboard's port too, so one address serves both.
  val web: Tool.Web =
    val service: Tool.Web = WebService(fallback = Answering.fallback): () =>
      val dashboard = Dashboard()
      WebService.Session(dashboard.interface, dashboard.handle, () => dashboard.refresh())

    new Tool.Web:
      def port: Int = 0

      def serve(port: Int)(using Monitor, Probate): Unit =
        Server.serving = true
        Server.announce(t"dashboard", port)

        try service.serve(port) finally
          Server.serving = false
          Server.withdraw(t"dashboard")

      def stop(): Unit = service.stop()

// The dashboard: the journal's runs to choose from, and the chosen run's report, live while it
// runs. Rebuilt from the journal and the registry a few times a second; each cell is assigned
// only when its content has changed, so a quiet dashboard sends nothing.
final class Dashboard():
  private val mutex: Mutex = Mutex()

  // The action selecting each run, by run id, made as the run first appears.
  @scala.caps.unsafe.untrackedCaptures
  private var actions: Map[Text, Action] = Map()

  @scala.caps.unsafe.untrackedCaptures
  @volatile
  private var selected: Optional[Text] = Unset

  @scala.caps.unsafe.untrackedCaptures
  private var lastRuns: List[Block] = Nil

  @scala.caps.unsafe.untrackedCaptures
  private var lastLive: AnyRef | Null = null

  @scala.caps.unsafe.untrackedCaptures
  private var lastFinished: Int = -1

  @scala.caps.unsafe.untrackedCaptures
  private var lastLayouts: Int = 0

  @scala.caps.unsafe.untrackedCaptures
  private var lastRun: Optional[Text] = Unset

  @scala.caps.unsafe.untrackedCaptures
  private var lastActivities: List[Activity] = Nil

  val runs: pyrocosm.Live[List[Block]] = pyrocosm.Live(List(Block.paragraph(t"No runs yet.")))
  val content: pyrocosm.Live[List[Block]] = pyrocosm.Live(List(Block.paragraph(t"Select a run.")))

  private def action(run: Text): Action = mutex:
    actions(run).or:
      val action = Action(t"run-$run")
      actions = actions.define(run, action)
      action

  // A press is of a run in the list, which selects it, or of the button beside a test over
  // several axes in the report being shown, which turns that test in that run (`Rotations`).
  def handle(event: Event): Unit = event match
    case Event.Pressed(action) =>
      val found: Optional[Text] = mutex(actions.to[List]).seek(_(1) == action).let(_(0))

      found.or(selected.let { run => if Server.turn(run, action) then run else Unset }).let: run =>
        selected = run
        refresh()

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

  // What a run is called: its record's name, which no suite starting or finishing changes.
  private def nameOf(run: Journal.Run): Text = run.record.name

  // A run in flight as an activity: its name, its board's gauge, with the board's caption and
  // verdict as its state, and the action selecting the run, so its card opens the run's view.
  // A run whose board is not yet attached is starting.
  private def activityOf(run: Journal.Run): Activity =
    val board: Optional[Activity] = Server.board(run.id).let(_.activity)
    val status: pyrocosm.Status = board.let(_.status).or(pyrocosm.Status.Reckoning(0L, Unset))

    val state: List[Inline] =
      board.lay(Inline.text(t"starting")): activity =>
        activity.title + List(Inline.Textual(t" · ")) + activity.state

    val destination: Inline.Destination = Inline.Destination.Internal(action(run.id))
    Activity(run.id, Inline.text(nameOf(run)), status, destination, state)

  private def runItem(run: Journal.Run): Block.Item =
    val title: List[Inline] = Dashboard.title(run.record, selected == run.id)
    val host: Text = Machine.Identity.local.hostname
    val note: List[Inline] = Dashboard.note(run.record, when(run.record.started), host)
    Block.Item(List(Block.Paragraph(title)), action(run.id), note = note)

  // Whether two references are the same object, or both absent: how a board's published blocks
  // are known to be unchanged without comparing them.
  private def identical(left: AnyRef | Null, right: AnyRef | Null): Boolean = (left, right) match
    case (left: AnyRef, right: AnyRef) => left eq right
    case _                             => left == null && right == null

  // Rebuild what has changed. Called by the serving loop a few times a second, and after a
  // selection.
  def refresh(): Unit = synchronized:
    val active = Journal.active
    val completed = Journal.completed

    if selected.absent then (active + completed).head.let: run => selected = run.id

    val items: List[Block.Item] = (active + completed).map(runItem)

    val listing: List[Block] =
      if items.nil then List(Block.paragraph(t"No runs yet."))
      else List(Block.Listing(false, items))

    if listing != lastRuns then
      lastRuns = listing
      runs() = listing

    // Every run in flight is a card in the masthead, and gone once it finishes.
    val activities: List[Activity] = active.map(activityOf)

    if activities != lastActivities then
      lastActivities = activities
      interface.activities() = activities

    selected.let: run =>
      val board = Server.board(run)
      val finished = Server.done(run)

      val live: AnyRef | Null = board.lay(null: AnyRef | Null): board =>
        board.webResults() match
          case results: AnyRef => results
          case _               => null

      // How many layouts the finished suites have had between them: one more whenever a test
      // of one is turned, from this dashboard or another.
      val layouts: Int = finished.map(_.version).fold(0)(_ + _)

      val changed =
        lastRun != run || finished.size != lastFinished || layouts != lastLayouts ||
          !identical(live, lastLive)

      lastRun = run
      lastFinished = finished.size
      lastLayouts = layouts
      lastLive = live

      if changed then
        val past: List[Block] = finished.bind: entry =>
          Block.Heading(2, Inline.text(entry.suite)) :: entry.blocks

        val current: List[Block] = board.lay(Nil: List[Block]): board =>
          Block.Heading(2, Inline.text(board.title)) :: board.webResults()

        // A finished run's report opens with its outcome and totals; a run in flight has its
        // card in the masthead instead.
        val summary: List[Block] = if board.present then Nil else
          (active + completed).seek(_.id == run).lay(Nil: List[Block]): entry =>
            val running: Inline = Inline.Toned(Tone.Accent, Inline.text(t"running"))

            val outcome: Inline = entry.record.outcome.lay(running):
              case RunRecord.passed => Inline.Toned(Tone.Success, Inline.text(t"passed"))
              case RunRecord.failed => Inline.Toned(Tone.Failure, Inline.text(t"failed"))
              case _                => Inline.Toned(Tone.Warning, Inline.text(t"aborted"))

            val totals: List[Inline] = entry.record.totals.lay(Nil: List[Inline]): totals =>
              List
                ( Inline.Textual(t"  "),
                  Inline.Numeral(totals.passed.toDouble, 0),
                  Inline.Textual(t" passed, "),
                  Inline.Numeral(totals.failed.toDouble, 0),
                  Inline.Textual(t" failed") )

            List(Block.Paragraph(outcome :: totals))

        val all = summary + past + current
        content() = if all.nil then List(Block.paragraph(t"Nothing recorded yet.")) else all

  val interface: Interface =
    Interface
      ( Inline.text(t"fume"),
        List
          ( Panel
              ( Panel.Id(t"runs"),
                Panel.Role.Navigation,
                Inline.text(t"Runs"),
                runs,
                Panel.Priority.Important ),
            Panel
              ( Panel.Id(t"run"),
                Panel.Role.Primary,
                Unset,
                content,
                Panel.Priority.Essential,
                hints = Hints(hints.Follow) ) ) )
