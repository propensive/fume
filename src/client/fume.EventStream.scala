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

import java.io as ji

import soundness.*

import alphabets.hexLowerCase

import probates.cancelProbate
import systems.javaBaseSystem

// Consumes a suite's `TestEvent` stream: fume calls `probably.Streamer.stream` in the SUITE'S
// classloader world through a structural type (JDK-typed signature only), hands it a
// `StreamOutputStream`, and reads back a lazy chain of chunks which it splits into
// length-prefixed frames and decodes — with ITS OWN bundles' BinTEL codec — into its own
// `probably.TestEvent`s. The first frame is the producer's schema fingerprint, compared with
// this side's before anything is decoded: a mismatch means the suite was built against a
// Soundness whose event layout differs, and the caller falls back to the legacy run.
object EventStream:
  private type Streamable = { def stream(suite: Text, arguments: Text, output: ji.OutputStream): Int }

  enum Outcome:
    case Completed(exit: Int)
    // The two schema fingerprints, the suite's and fume's own, as hex: the notice names both.
    case Incompatible(theirs: Text, ours: Text)
    // A handler (the model or the live board) threw; the suite was stopped. Reported by the
    // caller once the board has left the alternate screen, so the trace is not lost with it.
    case Failed(error: Throwable)

  // Splits a chunk chain into length-prefixed frames, lazily: nothing is pulled from the
  // (blocking, Relay-backed) chunk chain until the next frame is demanded, and partial bytes
  // accumulate across chunk boundaries in an immutable array. A trailing partial frame (a
  // truncated stream) is dropped.
  private[fume] def split(chunks: Chain[Data]): Chain[Data] =
    def join(left: Data, right: Data): Data =
      val out = Array.allocate[Byte](left.length + right.length)
      out.place(left, 0, 0, left.length)
      out.place(right, 0, left.length, right.length)
      Array.freeze(out)

    def slice(data: Data, from: Int, until: Int): Data =
      val out = Array.allocate[Byte](until - from)
      out.place(data, from, 0, until - from)
      Array.freeze(out)

    def recur(buffer: Data, rest: Chain[Data]): Chain[Data] = Chain.defer:
      def byte(index: Int): Int = buffer.readUnchecked(index) & 0xff

      if buffer.length >= 4 then
        val length = (byte(0) << 24) | (byte(1) << 16) | (byte(2) << 8) | byte(3)

        if buffer.length >= 4 + length then
          val frame: Data = slice(buffer, 4, 4 + length)
          Chain.cons(frame, recur(slice(buffer, 4 + length, buffer.length), rest))
        else pull(buffer, rest)
      else pull(buffer, rest)

    def pull(buffer: Data, rest: Chain[Data]): Chain[Data] = rest match
      case chunk #:: more => recur(join(buffer, chunk), more)
      case _              => Chain.empty

    recur(Array.empty[Byte], chunks)

  // Runs `suite` (loaded from the isolating classloader over `classpath`) with the event
  // protocol, feeding each decoded event to `handle` as it arrives, and returning the run's
  // exit status. `Unset` when the suite's Probably predates the event stream (no
  // `probably.Streamer` on its classpath); `Incompatible` when the schema fingerprints
  // disagree. Whatever the suite prints through the JVM's streams — which under the daemon
  // are nobody's — is captured for the whole run and handed to `captured` at its end, never
  // shown as it happens: the invocation's terminal may be the board's, which stray output
  // would corrupt. Events travel the chunk chain.
  // The exit status reported for an aborted run, conventionally 128 + SIGINT.
  val abortExit: Int = 130

  // Whether the suites `loader` sees may be invoked repeatedly through it: Probably advertises
  // this with `Streamer.reentrant` (an older Probably, whose suites memoize their first runner,
  // lacks the method and needs a fresh loader per invocation).
  def reentrant(loader: Classloader): Boolean =
    safely(loader.on(t"probably.Streamer$$")).let { streamer => safely(streamer.getMethod("reentrant")) }.present

  // Whether the suites `loader` sees understand `--workers=<n>` (Probably's `Streamer.queued`):
  // a queued runner traverses each suite once and executes its pure assertions behind the
  // traversal. To an older Probably the term is a name glob admitting nothing, so it is only
  // passed when advertised.
  def queued(loader: Classloader): Boolean =
    safely(loader.on(t"probably.Streamer$$")).let { streamer => safely(streamer.getMethod("queued")) }.present

  // A consumer of a suite's frames: shown the first (fingerprint) frame, which it may reject,
  // and then every event frame. The decoding sink is the local run's; a worker relaying a run
  // to another fume forwards every frame, the fingerprint included, and rejects nothing, so
  // the controller makes the compatibility decision against its OWN Probably.
  trait Sink:
    def fingerprint(theirs: Data): Boolean
    def frame(frame: Data): Unit

  object Sink:
    // Decodes each frame with fume's own Probably, once the fingerprints agree.
    def decoding(handle: probably.TestEvent => Unit): Sink^{handle} = new Sink:
      def fingerprint(theirs: Data): Boolean =
        theirs.readable.sameElements(probably.Streamer.fingerprint.readable)

      def frame(frame: Data): Unit = handle(probably.Streamer.read(frame))

    // Forwards every frame, the fingerprint first, and rejects nothing.
    def forwarding(forward: Data => Unit): Sink^{forward} = new Sink:
      def fingerprint(theirs: Data): Boolean =
        forward(theirs)
        true

      def frame(frame: Data): Unit = forward(frame)

  // The producer of a run's frames, as `consume` controls it: `exit` awaits its exit status,
  // and `stop` stops it. One handle rather than two thunks, since both close over the same
  // task or job, which separation checking would otherwise see as overlapping arguments.
  private trait Producer:
    def exit(): Int
    def stop(): Unit

  // What is made of a run's frames, wherever they come from: the first is the producer's
  // schema fingerprint, which the sink may reject, and every later one goes to the sink.
  private def consume
    ( chunks:   Chain[Data],
      sink:     Sink^,
      abort:    () => Boolean,
      producer: Producer^ )
    ( using monitor: Monitor )
  :   Outcome =

    EventStream.split(chunks) match
      case first #:: _ if !sink.fingerprint(first) =>
        def hex(data: Data): Text = data.serialize[Hex]
        Outcome.Incompatible(hex(first), hex(probably.Streamer.fingerprint))

      case first #:: rest =>
        // Frames are consumed on their own task, so the invocation thread stays free to notice
        // an abort (a trapped Ctrl+C) even while the chain is blocked mid-benchmark waiting for
        // the next event. Cancelling the tasks interrupts the blocked take. A failure in a
        // handler (the model or the live board) must end the run with its cause on stderr, not
        // leave the invocation polling a dead task for ever while the suite runs on unobserved.
        val failure: Atomic[Optional[Throwable]] = Atomic.Ref.vacant[Throwable]

        val consumer = async:
          try rest.each { (frame: Data) => sink.frame(frame) }
          catch case error: Throwable =>
            failure() = error
            throw error

        // A short wait, so a finished suite is noticed at once: the slack compounds over a
        // classpath of hundreds of suites.
        def drained(): Boolean =
          scala.caps.unsafe.unsafeAssumeSeparate(safely(consumer.await(0.01*Second)).present)

        def spin(): Outcome = failure() match
          case failed: Throwable =>
            producer.stop()
            Outcome.Failed(failed)

          case _ =>
            if drained() then Outcome.Completed(producer.exit())
            else if abort() then
              consumer.cancel()
              producer.stop()
              Outcome.Completed(abortExit)
            else
              spin()

        spin()

      case _ =>
        Outcome.Completed(producer.exit())

  // Whether a suite on the classpath `loader` sees can be run in a JVM of its own, through
  // Probably's `probably.Standalone` entry point; an older Probably has none, and a suite of its
  // vintage is forked the legacy way, if at all.
  def forkable(loader: Classloader): Boolean = safely(loader.on(t"probably.Standalone$$")).present

  // As `frames`, but the suite runs in a JVM of its own, launched through `probably.Standalone`
  // in the given working directory and environment, which for `fume run --fork` are the
  // invocation's: a suite then sees the directory and the variables `fume` was run with, where
  // in-process it sees the daemon's, which belong to no invocation. The frames arrive on the
  // process's standard output, and everything the suite prints on its standard error, which is
  // captured.
  def forked
    ( classpath: LocalClasspath, suite: Text, args: List[Text] )
    ( sink:     Sink^,
      abort:    () => Boolean,
      captured: (Text, Text) => Unit )
    ( using Monitor, WorkingDirectory, Environment )
  :   Optional[Outcome] =

    val java: Text =
      safely(System.properties.java.home[Text]()).lay(t"java") { home => t"$home/bin/java" }

    safely:
      val job = sh"$java -cp ${classpath()} probably.Standalone $suite $args".fork[Unit]()
      val errors = async(job.errorText())
      val producer: Producer^{job} = new Producer:
        def exit(): Int = job.status()
        def stop(): Unit = job.abort()

      val outcome = consume(job.stdout().chain, sink, abort, producer)
      captured(t"", scala.caps.unsafe.unsafeAssumeSeparate(safely(errors.await()).or(t"")))
      outcome

  def stream
    ( classpath: LocalClasspath,
      suite:     Text,
      args:      List[Text],
      shared:    Optional[Classloader] = Unset )
    ( handle:   probably.TestEvent => Unit,
      abort:    () => Boolean,
      captured: (Text, Text) => Unit )
    ( using monitor: Monitor )
  :   Optional[Outcome] =

    frames(classpath, suite, args, shared)(Sink.decoding(handle), abort, captured)

  // As `stream`, but the frames go to `sink` undecoded: the general form both the local run
  // and a relaying worker are built on.
  def frames
    ( classpath: LocalClasspath,
      suite:     Text,
      args:      List[Text],
      shared:    Optional[Classloader] = Unset )
    ( sink:     Sink^,
      abort:    () => Boolean,
      captured: (Text, Text) => Unit )
    ( using monitor: Monitor )
  :   Optional[Outcome] =

    import scala.reflect.Selectable.reflectiveSelectable

    // One loader for a whole run, when the suites allow it; otherwise a fresh, isolating one.
    val loader: Classloader = shared.or(classpath.classloader(Classloader.Delegation.Preferential))

    // `Classloader#on` THROWS `ClassNotFoundException` (rather than yielding `Unset`) when the
    // class is absent; `safely` maps that to `Unset` — the signal that this suite's Probably
    // predates the event stream. (Upstream: `on` should arguably absorb it itself.)
    safely(loader.on(t"probably.Streamer$$")).let: moduleClass =>
      safely:
        val instance = moduleClass.getField("MODULE$").nn.get(null).nn
        val output = StreamOutputStream()

        val capture: Stdio.Capture[Outcome] = Stdio.capture:
          val arguments: Text = args.join(t"\n")

          val task = async:
            loader.use(instance.asInstanceOf[Streamable].stream(suite, arguments, output))

          // The task is single-owner and awaited exactly once after the frame chain is
          // exhausted; the separation checker cannot see that through the capture-polymorphic
          // `await`, nor that the handle's hold on the monitor is the one `consume` is given,
          // hence the (sanctioned, narrow) `unsafeAssumeSeparate` and `unsafeAssumePure`.
          val producer: Producer =
            scala.caps.unsafe.unsafeAssumePure:
              new Producer:
                def exit(): Int = scala.caps.unsafe.unsafeAssumeSeparate(unsafely(task.await()))
                def stop(): Unit = task.cancel()

          consume(output.stream, sink, abort, producer)

        captured(capture.out, capture.err)
        capture.result
