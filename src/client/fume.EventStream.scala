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
import java.lang as jl

import soundness.*

import alphabets.hexLowerCase

import probates.cancelProbate

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
  private def frames(chunks: Chain[Data]): Chain[Data] =
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
  // disagree. The `System.out`/`err` swap covers the whole run, exactly as the legacy path:
  // the suite's own prints reach the invocation's stdio, while events travel the chunk chain.
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

  def stream(classpath: LocalClasspath, suite: Text, args: List[Text], shared: Optional[Classloader] = Unset)
     (handle: probably.TestEvent => Unit, abort: () => Boolean = () => false)
     (using stdio: Stdio, monitor: Monitor)
  :   Optional[Outcome] =

    import scala.reflect.Selectable.reflectiveSelectable

    // One loader for a whole run, when the suites allow it; otherwise a fresh, isolating one.
    val loader: Classloader = shared.or(classpath.classloader())

    // `Classloader#on` THROWS `ClassNotFoundException` (rather than yielding `Unset`) when the
    // class is absent; `safely` maps that to `Unset` — the signal that this suite's Probably
    // predates the event stream. (Upstream: `on` should arguably absorb it itself.)
    safely(loader.on(t"probably.Streamer$$")).let: moduleClass =>
      safely:
        val instance = moduleClass.getField("MODULE$").nn.get(null).nn
        val output = StreamOutputStream()
        val out = jl.System.out.nn
        val err = jl.System.err.nn
        jl.System.setOut(stdio.out)
        jl.System.setErr(stdio.err)

        try
          val arguments: Text = args.join(t"\n")

          val task = async:
            loader.use(instance.asInstanceOf[Streamable].stream(suite, arguments, output))

          def matches(left: Data, right: Data): Boolean =
            java.util.Arrays.equals(Array.unsafeJvm(left), Array.unsafeJvm(right))

          // The task is single-owner and awaited exactly once after the frame chain is
          // exhausted; the separation checker cannot see that through the capture-polymorphic
          // `await`, hence the (sanctioned, narrow) `unsafeAssumeSeparate`.
          def exit(): Int = scala.caps.unsafe.unsafeAssumeSeparate(unsafely(task.await()))

          frames(output.stream) match
            case first #:: _ if !matches(first, probably.Streamer.fingerprint) =>
              def hex(data: Data): Text = data.serialize[Hex]
              Outcome.Incompatible(hex(first), hex(probably.Streamer.fingerprint))

            case first #:: rest =>
              // Frames are consumed on their own task, so the invocation thread stays free to
              // notice an abort (a trapped Ctrl+C) even while the chain is blocked mid-benchmark
              // waiting for the next event. Cancelling the tasks interrupts the blocked take.
              // A failure in a handler (the model or the live board) must end the run with its
              // cause on stderr, not leave the invocation polling a dead task for ever while the
              // suite runs on unobserved.
              val failure = java.util.concurrent.atomic.AtomicReference[Throwable | Null](null)

              val consumer = async:
                try rest.each { (frame: Data) => handle(probably.Streamer.read(frame)) }
                catch case error: Throwable =>
                  failure.set(error)
                  throw error

              // A short wait, so a finished suite is noticed at once: the slack compounds
              // over a classpath of hundreds of suites.
              def drained(): Boolean =
                scala.caps.unsafe.unsafeAssumeSeparate(safely(consumer.await(0.01*Second)).present)

              def spin(): Outcome =
                val failed = failure.get()
                if failed != null then
                  task.cancel()
                  Outcome.Failed(failed)
                else if drained() then Outcome.Completed(exit())
                else if abort() then
                  consumer.cancel()
                  task.cancel()
                  Outcome.Completed(abortExit)
                else spin()

              spin()

            case _ =>
              Outcome.Completed(exit())

        finally
          stdio.out.flush()
          stdio.err.flush()
          jl.System.setOut(out)
          jl.System.setErr(err)
