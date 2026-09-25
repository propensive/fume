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

// `Relay` is fume's own message enum, not turbulence's `Relay`.
import soundness.{Relay as _, *}

import pyrocosm.{Blobs, Channel, Machine, Peer, Tool}

import denominative.dysasymptotics.linearSize
import environments.javaBaseEnvironment
import logging.silentLogging
import probates.cancelProbate
import systems.javaBaseSystem

// This daemon as a WORKER: a `Tool.Service` that listens (on `listen-port`, 8091 by default)
// for a controller — a fume on another machine, invoked with `--on <this machine>` — and runs
// the selection it sends exactly as a local `fume run` would, except that every suite's event
// frames are forwarded to the controller, undecoded, instead of folding into a board here. The
// controller shows, journals and serves the run; this daemon journals it too, as a run by
// `Invoker.Remote`, so its own dashboard shows what it ran and for whom.
//
// One run at a time: a second controller is refused as `busy` in the handshake. A worker only
// runs suites that can stream events; a classpath whose Probably predates the event protocol
// ends each suite as `legacy`, which the controller reports.
object Worker:
  private val running: Atomic[Boolean] = Atomic(false)

  // The identity and token a listener needs, from the process's own environment: a service
  // runs for the daemon, outside any invocation.
  private def identity: Optional[Peer.Identity] = safely(Peer.identity)

  val service: Tool.Service = new Tool.Service:
    def keyword: Text = t"listen"
    def portKeyword: Text = t"listenPort"
    def port: Int = Relay.port

    @scala.caps.unsafe.untrackedCaptures
    @volatile
    private var listener: Optional[Peer.Listener[Relay]] = Unset

    // `listen-token` names the shared secret (a file, or the secret itself); without it the
    // machine's own token file is used, generated on first use. `capability` words describe
    // the machine to controllers.
    def serve(port: Int, settings: Text => Optional[Text])(using monitor: Monitor, probate: Probate)
    :   Unit =

      identity.let: identity =>
        val token: Optional[Text] = settings(t"listenToken").let(Machine.secret(_)).or(Peer.token)

        token.let: token =>
          val capabilities: List[Text] =
            settings(t"capability").lay(Nil: List[Text])(_.cut(t":").filter(_ != t""))

          val gate: () => Optional[Text] = () => if running() then Peer.Refusal.busy else Unset

          val made: Peer.Listener[Relay] =
            Peer.Listener[Relay](t"fume", Fume.version, Relay.codec, token, identity, capabilities, gate):
              session => Worker.run(session)

          listener = made
          made.serve(port)

    def stop(): Unit = listener.let(_.stop())

  // Waits for the load average to fall below `target`, silently: the controller has the
  // terminal, and the wait is theirs to report.
  private def settle(target: Double, aborted: Atomic[Boolean])(using Monitor): Unit =
    Load.average.let: load =>
      if load >= target && !aborted() then
        snooze(0.2*Second)
        settle(target, aborted)

  // One controller's connection: the plan, the blobs it lacks, then the run.
  private def run(session: Peer.Session[Relay])(using Monitor): Unit =
    running() = true

    try
      session.receive() match
        case Channel.Frame.Message(Relay.Plan(entries, suites, arguments, maxLoad)) =>
          Blobs.store match
            case store: Blobs.Store =>
              session.send(Relay.Need(store.missing(entries.map(_.digest))))
              val assembly = Blobs.Assembly(store)

              // Blobs arrive until `start`; a chunk whose digest lies ends the run.
              def gather(): Boolean = session.receive() match
                case Channel.Frame.Message(Relay.Blob(digest, _, last)) =>
                  session.receive() match
                    case Channel.Frame.Raw(data) =>
                      assembly.receive(digest, data, last) match
                        case false =>
                          session.send(Relay.Rejected(t"the bytes sent for $digest do not have that digest"))
                          false

                        case _ =>
                          gather()

                    case _ =>
                      false

                case Channel.Frame.Message(Relay.Start) => true
                case _                                  => false

              if gather() then
                val missing: List[Text] = store.missing(entries.map(_.digest))

                if !missing.nil
                then session.send(Relay.Rejected(t"${missing.size} classpath entries were never received"))
                else execute(session, store, entries, suites, arguments, maxLoad)

            case _ =>
              session.send(Relay.Rejected(t"the worker has no blob store"))

        case _ =>
          ()

    finally running() = false

  private def execute
    ( session:   Peer.Session[Relay],
      store:     Blobs.Store,
      entries:   List[Blobs.Entry],
      suites:    List[Text],
      arguments: List[Text],
      maxLoad:   Optional[Text] )
    ( using Monitor )
  :   Unit =

    // Every entry is a jar in the store: a directory was bundled into one by the controller.
    val jars: List[Classpath.Entry.Jar] =
      entries.map { entry => Classpath.Entry.Jar(store.path(entry.digest).encode) }

    val classpath: LocalClasspath = LocalClasspath(jars*)
    val aborted: Atomic[Boolean] = Atomic(false)

    // The controller's `abort`, or its disappearance, ends the run at the next check.
    val watcher = async:
      def recur(): Unit = session.receive() match
        case Channel.Frame.Message(Relay.Abort) => aborted() = true
        case Channel.Frame.Closed               => aborted() = true
        case _                                  => recur()

      recur()

    maxLoad.let { text => safely(text.as[Double]).let(settle(_, aborted)) }

    val journalId: Int =
      Journal.start
        ( session.peer.identity.hostname, Invoker.Remote, classpath(), arguments, suites,
          session.peer.identity.hostname )

    val loader: Classloader = classpath.classloader()
    val shared: Optional[Classloader] = if EventStream.reentrant(loader) then loader else Unset

    def recur(remaining: List[Text], failures: Int): Int = remaining match
      case _ if aborted() =>
        failures

      case suite :: tail =>
        session.send(Relay.Began(suite))
        Journal.began(journalId, suite)
        val started: Instant over Unix = now()

        val sink: EventStream.Sink =
          EventStream.Sink.forwarding { frame => session.send(Relay.Frame(suite), frame) }

        val outcome: Optional[EventStream.Outcome] =
          EventStream.frames(classpath, suite, arguments, shared)
            ( sink,
              () => aborted(),
              (out, err) => session.send(Relay.Captured(suite, out, err)) )

        val passed: Boolean = outcome match
          case EventStream.Outcome.Completed(exit) =>
            session.send(Relay.Ended(suite, Relay.completed, exit, t""))
            exit == 0

          case EventStream.Outcome.Failed(error) =>
            session.send(Relay.Ended(suite, Relay.failed, 2, error.stackTrace.show))
            false

          case EventStream.Outcome.Incompatible(_, _) =>
            // A forwarding sink rejects no fingerprint, so this cannot arise; reported as a
            // failure if it somehow does.
            session.send(Relay.Ended(suite, Relay.failed, 2, t"incompatible"))
            false

          case _ =>
            session.send(Relay.Ended(suite, Relay.legacy, 2, t""))
            false

        Journal.record(journalId, suite, passed, Unset, started)
        recur(tail, if passed then failures else failures + 1)

      case _ =>
        failures

    val failures: Int = recur(suites, 0)

    val outcome: Journal.Outcome =
      if aborted() then Journal.Outcome.Aborted
      else if failures == 0 then Journal.Outcome.Passed
      else Journal.Outcome.Failed

    Journal.finish(journalId, outcome, Unset)
    session.send(Relay.Done)
    watcher.cancel()
