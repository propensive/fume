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
import java.nio.file as jnf

import soundness.*

import denominative.dysasymptotics.linearSize

import calendars.gregorianCalendar
import charsets.utf8Charset
import systems.javaBaseSystem
import textSanitizers.skipSanitizer

// Where runs are persisted: one directory per run under `$XDG_STATE_HOME/fume/runs/`
// (`~/.local/state/fume/runs/`), holding the run's record, `run.tel`, rewritten as the run
// progresses; each suite's event frames, verbatim, as `events/<n>.bintel`; and whatever each
// suite printed outside its report, as `captured/<n>.txt`. The record is small and the frames
// are the source of truth: a run's results are replayed from them, through the same `Model` a
// live run folds into, whenever they are asked for — by the MCP server, for a run the journal
// has forgotten or a daemon that never saw it.
//
// Files are written with the JDK's own IO, as `Index` reads with it: a frame file is held open
// for a whole suite, which galilei's scoped handles do not express, and a record replaces its
// predecessor atomically.
object Runs:
  // The directory, as the environment of the first invocation to locate it names it: a service
  // (the MCP server, serving outside any invocation) sees the daemon's own environment, which
  // need not name a home at all, so the located directory is remembered.
  private val located: Atomic[Optional[Path on Linux]] = Atomic.Ref.vacant[Path on Linux]

  def locate()(using Environment): Optional[Path on Linux] =
    val found: Optional[Path on Linux] = safely(Xdg.stateHome[Path on Linux] / "fume" / "runs")

    found.let { path => located() = path }
    found

  def directory: Optional[Path on Linux] = located().or:
    import environments.javaBaseEnvironment
    locate()

  private def directory(id: Text): Optional[Path on Linux] =
    directory.let { directory => unsafely(directory / id) }

  // A run's id: the UTC time it started, to the second, and four hex digits of entropy, so ids
  // sort by time and two runs starting in one second — one per client — stay distinct.
  def id(started: Instant over Unix): Text =
    val shown: Text = (started in tz"UTC").show
    val digits: Text = shown.s.filter(_.isDigit).tt
    val date: Text = digits.keep(8)
    val time: Text = digits.skip(8).keep(6)
    val entropy: Text = Uuid().show.keep(4)
    t"$date-$time-$entropy"

  private def wellFormed(name: Text): Boolean =
    name.length == 20 && name.s.forall { char => char.isDigit || char == '-' || (char >= 'a' && char <= 'f') }

  def eventsFile(index: Int): Text =
    val number: Text = index.toString.tt
    t"events/$number.bintel"

  def capturedFile(index: Int): Text =
    val number: Text = index.toString.tt
    t"captured/$number.txt"

  private def file(id: Text, name: Text): Optional[ji.File] =
    directory(id).let { directory => ji.File(directory.encode.s, name.s) }

  // Writes the record, replacing any earlier version atomically: a reader sees the old record
  // or the new, never a partial one.
  def write(record: RunRecord): Unit =
    file(record.id, t"run.tel").let: target =>
      safely:
        val parent: ji.File = target.getParentFile.nn
        parent.mkdirs()
        val temporary = ji.File(parent, "run.tel.tmp")
        jnf.Files.writeString(temporary.toPath, RunRecord.write(record).s)

        jnf.Files.move
          ( temporary.toPath,
            target.toPath,
            jnf.StandardCopyOption.ATOMIC_MOVE,
            jnf.StandardCopyOption.REPLACE_EXISTING )

      . unit

  def read(id: Text): Optional[RunRecord] =
    file(id, t"run.tel").let: file =>
      if !file.exists then Unset
      else safely(jnf.Files.readString(file.toPath).nn.tt).let(RunRecord.read(_))

  // Every run's id, newest first.
  def ids: List[Text] =
    directory.lay(Nil: List[Text]): directory =>
      val listed: scala.Array[String | Null] | Null = ji.File(directory.encode.s).list

      // The well-formed names, gathered newest-first by sorting the stdlib way: the names are
      // fixed-width digits and hex, so a string order is a time order.
      if listed == null then Nil else
        val names: scala.Array[String | Null] = listed
        val sorted: scala.Array[String] = names.map(_.nn).sorted(using scala.math.Ordering.String.reverse)

        def recur(index: Int, done: List[Text]): List[Text] =
          if index < 0 then done else
            val name: Text = sorted(index).tt
            recur(index - 1, if wellFormed(name) then name :: done else done)

        recur(sorted.length - 1, Nil)

  // The newest `limit` records that can be read.
  def load(limit: Int): List[RunRecord] =
    def recur(todo: List[Text], done: List[RunRecord]): List[RunRecord] = todo match
      case id :: tail =>
        read(id) match
          case record: RunRecord => recur(tail, record :: done)
          case _                 => recur(tail, done)

      case _ =>
        done.reverse

    recur(ids.keep(limit), Nil)

  // The bytes of a suite's frame file, or of its captured output.
  def bytes(id: Text, name: Text): Optional[Data] =
    file(id, name).let: file =>
      if !file.exists then Unset
      else
        safely:
          val raw: scala.Array[Byte] = jnf.Files.readAllBytes(file.toPath).nn
          Array.unsafeFrozen[Byte](raw)

  def captured(id: Text, index: Int): Optional[Text] =
    bytes(id, capturedFile(index)).let { data => safely(data.read[Text]) }

  // Stores what a suite printed outside its report, in the sections the output log uses.
  def capture(id: Text, index: Int, captured: Captures.Captured): Boolean =
    if captured.empty then false else
      file(id, capturedFile(index)).lay(false): file =>
        safely:
          file.getParentFile.nn.mkdirs()
          jnf.Files.writeString(file.toPath, Captures.sections(captured).s)
          true
        . or(false)

  // Writes a suite's frames as they arrive, each with the 4-byte big-endian length the wire
  // gives it, so the file is the stream the suite produced, fingerprint first.
  final class Writer(stream: ji.OutputStream):
    def frame(data: Data): Unit =
      val length: Int = data.length
      stream.write((length >>> 24) & 0xff)
      stream.write((length >>> 16) & 0xff)
      stream.write((length >>> 8) & 0xff)
      stream.write(length & 0xff)
      val raw: scala.Array[Byte] = Array.unsafeJvm[Byte](data)
      stream.write(raw)

    def close(): Unit = safely(stream.close()).unit

  def writer(id: Text, index: Int): Optional[Writer] =
    file(id, eventsFile(index)).let: file =>
      safely:
        file.getParentFile.nn.mkdirs()
        Writer(ji.BufferedOutputStream(ji.FileOutputStream(file)))

  // A sink teeing every frame — the fingerprint included — into a writer, if there is one.
  def tee(writer: Optional[Writer], inner: EventStream.Sink^): EventStream.Sink^{inner} =
    new EventStream.Sink:
      def fingerprint(theirs: Data): Boolean =
        writer.let(_.frame(theirs))
        inner.fingerprint(theirs)

      def frame(frame: Data): Unit =
        writer.let(_.frame(frame))
        inner.frame(frame)

  // Deletes the oldest runs beyond `retention`, and any directory of a run that never wrote a
  // record.
  def prune(retention: Int): Unit =
    def wipe(file: ji.File): Unit =
      val children: scala.Array[ji.File | Null] | Null = file.listFiles

      if children != null then
        val present: scala.Array[ji.File | Null] = children
        present.foreach { child => wipe(child.nn) }

      file.delete()

    def remove(id: Text): Unit = file(id, t"") match
      case file: ji.File => safely(wipe(file)).unit
      case _             => ()

    ids.skip(retention.max(0)).each(remove)

  // How far a run's results can be served: every suite's frames readable (`available`), some
  // of them (`partial`), none because the suites' event schema is not this fume's
  // (`incompatible`), or none at all (`none`).
  case class Replayed(state: Model.State, availability: Text)

  private val replayed: scala.collection.concurrent.TrieMap[Text, Replayed] =
    scala.collection.concurrent.TrieMap()

  private val fingerprint: Data = probably.Streamer.fingerprint

  def replay(record: RunRecord): Replayed =
    def compute(): Replayed =
      val model = Model()
      var readable: Int = 0
      var incompatible: Int = 0

      def handle(frame: Data): Unit = model.handle(probably.Streamer.read(frame))

      def replaySuite(suite: RunRecord.Suite): Unit =
        val data: Optional[Data] = suite.events.let { name => bytes(record.id, name) }

        data.let: data =>
          val chunks: Chain[Data] = Chain(data)

          EventStream.split(chunks) match
            case first #:: rest =>
              if !first.readable.sameElements(fingerprint.readable) then incompatible += 1
              else
                readable += 1
                model.enter(suite.suite)
                // A frame that cannot be read ends the suite's replay, keeping what came before.
                safely(rest.each(handle)).unit

            case _ =>
              ()

      record.suites.each(replaySuite)

      model.finish()

      val availability: Text =
        if readable == 0 then (if incompatible > 0 then t"incompatible" else t"none")
        else if readable == record.suites.size then t"available"
        else t"partial"

      Replayed(model.state(), availability)

    // A finished run's frames never change, so its replay is kept; a run in flight is replayed
    // afresh each time, since its live model, where this daemon has it, is preferred anyway.
    if record.running then compute()
    else
      val cached: Optional[Replayed] = replayed.getOrElse(record.id, Unset)

      cached.or:
        val result: Replayed = compute()
        if replayed.size > 8 then replayed.clear()
        replayed(record.id) = result
        result
