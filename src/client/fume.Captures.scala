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

import calendars.gregorianCalendar
import charEncoders.utf8Encoder
import filesystemBackends.javaBaseFilesystem
import logging.silentLogging
import pyrocosm.{Block, Inline}
import systems.javaBaseSystem

// What a suite printed through the JVM's own streams while it ran in-process: never shown as
// it happens, since the terminal may be the board's, but kept — appended to a log under the
// XDG state home, and shown to the dashboard as captured output.
object Captures:
  case class Captured(suite: Text, out: Text, err: Text):
    def empty: Boolean = out == t"" && err == t""
    def lines: Int = out.cut(t"\n").count(_ != t"") + err.cut(t"\n").count(_ != t"")

  // The log every capture is appended to; `Unset` if the environment names no home for it.
  def log(using Environment): Optional[Path on Linux] =
    safely(Xdg.stateHome[Path on Linux] / "fume" / "output.log")

  private def section(stream: Text, text: Text): Text =
    if text == t"" then t"" else t"[$stream]\n$text${if text.ends(t"\n") then t"" else t"\n"}"

  // Appends a capture to the log under a heading naming the suite and the time, returning the
  // log's path; `Unset` if it could not be written.
  def record(captured: Captured)(using Environment): Optional[Path on Linux] =
    log.let: path =>
      safely:
        path.parent.let: directory =>
          if !directory.existent() then directory.create[Directory](CreateFlag.Parents)

        if !path.existent() then path.create[File]()
        val moment: Text = (now() in tz"UTC").show
        val heading: Text = t"── ${captured.suite} · $moment ──\n"

        val entry: Text =
          heading + section(t"stdout", captured.out) + section(t"stderr", captured.err)

        Eof(path).open(Write) { handle ?=> handle.write(Chain(entry.in[Data])) }
        path

  private def describe(captured: Captured): List[Block] =
    val out: List[Block] = if captured.out == t"" then Nil else List(Block.Output(captured.out))

    val err: List[Block] =
      if captured.err == t"" then Nil else List(Block.Output(captured.err, true))

    Block.Heading(3, Inline.text(captured.suite)) :: (out + err)

  // The captures as a section of a run's report, for the dashboard; nothing if nothing was
  // captured.
  def blocks(captures: List[Captured]): List[Block] =
    val shown: List[Captured] = captures.filter(!_.empty)

    if shown.nil then Nil
    else Block.Heading(2, Inline.text(t"Captured output")) :: shown.bind(describe)
