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
┃    Fume, version 0.1.0.                                                                          ┃
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

import scala.collection.immutable as sci

import soundness.*

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
    case Incompatible

  // Splits a chunk chain into length-prefixed frames, lazily: nothing is pulled from the
  // (blocking, Relay-backed) chunk chain until the next frame is demanded, and partial bytes
  // accumulate across chunk boundaries in an immutable array. A trailing partial frame (a
  // truncated stream) is dropped.
  private def frames(chunks: Chain[Data]): Chain[Data] =
    def recur(buffer: sci.ArraySeq[Byte], rest: sci.LazyList[Data]): Chain[Data] = Chain.defer:
      if buffer.length >= 4 then
        val length =
          ((buffer(0) & 0xff) << 24) | ((buffer(1) & 0xff) << 16)
            | ((buffer(2) & 0xff) << 8) | (buffer(3) & 0xff)

        if buffer.length >= 4 + length then
          val frame: Data = Array.from(buffer.slice(4, 4 + length))
          Chain.cons(frame, recur(buffer.drop(4 + length), rest))
        else pull(buffer, rest)
      else pull(buffer, rest)

    def pull(buffer: sci.ArraySeq[Byte], rest: sci.LazyList[Data]): Chain[Data] =
      if rest.isEmpty then Chain.empty
      else recur(buffer ++ sci.ArraySeq.unsafeWrapArray(rest.head.mutable(using Unsafe)), rest.tail)

    recur(sci.ArraySeq.empty[Byte], chunks.stdlib)

  // Runs `suite` (loaded from the isolating classloader over `classpath`) with the event
  // protocol, feeding each decoded event to `handle` as it arrives, and returning the run's
  // exit status. `Unset` when the suite's Probably predates the event stream (no
  // `probably.Streamer` on its classpath); `Incompatible` when the schema fingerprints
  // disagree. The `System.out`/`err` swap covers the whole run, exactly as the legacy path:
  // the suite's own prints reach the invocation's stdio, while events travel the chunk chain.
  // The exit status reported for an aborted run, conventionally 128 + SIGINT.
  val abortExit: Int = 130

  def stream(classpath: LocalClasspath, suite: Text, args: List[Text])
     (handle: probably.TestEvent => Unit, abort: () => Boolean = () => false)
     (using stdio: Stdio, monitor: Monitor)
  :   Optional[Outcome] =

    import scala.reflect.Selectable.reflectiveSelectable

    val loader: Classloader = classpath.classloader()

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
            sci.ArraySeq.unsafeWrapArray(left.mutable(using Unsafe))
              == sci.ArraySeq.unsafeWrapArray(right.mutable(using Unsafe))

          // The task is single-owner and awaited exactly once after the frame chain is
          // exhausted; the separation checker cannot see that through the capture-polymorphic
          // `await`, hence the (sanctioned, narrow) `unsafeAssumeSeparate`.
          def exit(): Int = scala.caps.unsafe.unsafeAssumeSeparate(unsafely(task.await()))

          val allFrames: sci.LazyList[Data] = frames(output.stream).stdlib

          if allFrames.isEmpty then Outcome.Completed(exit())
          else if !matches(allFrames.head, probably.Streamer.fingerprint) then Outcome.Incompatible
          else
            // Frames are consumed on their own task, so the invocation thread stays free to
            // notice an abort (a trapped Ctrl+C) even while the chain is blocked mid-benchmark
            // waiting for the next event. Cancelling the tasks interrupts the blocked take.
            val consumer = async:
              allFrames.tail.foreach { (frame: Data) => handle(probably.Streamer.read(frame)) }

            def drained(): Boolean =
              scala.caps.unsafe.unsafeAssumeSeparate(safely(consumer.await(0.1*Second)).present)

            def spin(): Outcome =
              if drained() then Outcome.Completed(exit())
              else if abort() then
                consumer.cancel()
                task.cancel()
                Outcome.Completed(abortExit)
              else spin()

            spin()

        finally
          stdio.out.flush()
          stdio.err.flush()
          jl.System.setOut(out)
          jl.System.setErr(err)
