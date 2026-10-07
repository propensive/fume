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
import codepages.utf8Codepage
import decodables.instantTelDecodable
import encodables.instantTelEncodable

// The record of one run, as the daemon persists it in `run.tel` under the run's directory
// (`Runs`), and as the journal holds it: who ran what, where, and how each suite fared. Pure
// data — texts, numbers, instants, lists — so the TEL codec derives, and a record written by
// one daemon is read by the next. The results themselves are not here: they are replayed from
// the suites' event frames, stored beside the record, so the record stays small however large
// the run.
//
// Vocabularies: `invoker` is `human`, `claude`, `codex`, `remote` or `mcp`; `outcome` is `passed`,
// `failed` or `aborted`, and absent while the run is in flight.
case class RunRecord
  ( id:        Text,
    version:   Text,
    workspace: Text,
    invoker:   Text,
    client:    Text,
    machine:   Text,
    started:   Instant over Unix,
    finished:  Optional[Instant over Unix],
    outcome:   Optional[Text],
    classpath: List[Text],
    selection: List[Text],
    scheduled: List[Text],
    suites:    List[RunRecord.Suite],
    totals:    Optional[RunRecord.Totals] ):

  def running: Boolean = finished.absent
  def duration: Optional[Duration] = finished.let(_ - started)
  def failures: Int = suites.count(!_.passed)

object RunRecord:
  // The counts of a run, or of one suite's share of it.
  case class Totals(passed: Int, failed: Int, aspirePassed: Int, aspireFailed: Int):
    def total: Int = passed + failed + aspirePassed + aspireFailed

  // One suite's contribution to a run: whether it passed, its totals when it ran by the event
  // protocol (a legacy or forked suite reports only its exit status), the file its event
  // frames were stored in, if any, and whether it printed anything outside its report.
  case class Suite
    ( suite:    Text,
      passed:   Boolean,
      started:  Instant over Unix,
      finished: Instant over Unix,
      totals:   Optional[Totals],
      events:   Optional[Text],
      captured: Boolean ):

    def duration: Duration = finished - started

  // The record as TEL text, and back. A record which cannot be read — written by a fume whose
  // record differs — is `Unset`, and the caller lists what it can.
  def write(record: RunRecord): Text = record.tel.show

  def read(text: Text): Optional[RunRecord] = safely(text.read[Tel].as[RunRecord])

  // The words the vocabularies use.
  val human: Text = t"human"
  val claude: Text = t"claude"
  val codex: Text = t"codex"
  val remote: Text = t"remote"
  val mcp: Text = t"mcp"

  val passed: Text = t"passed"
  val failed: Text = t"failed"
  val aborted: Text = t"aborted"
