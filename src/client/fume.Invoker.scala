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

object Invoker:
  val all: List[Invoker] = List(Human, Claude, Codex, Remote, Mcp)

  // The invoker of the current invocation. Each variable must be set to exactly `1`; Codex's is
  // checked first, so an environment carrying both reads as Codex.
  def detect(using Environment): Invoker =
    if safely(Environment.codexSandbox[Text]) == t"1" then Codex
    else if safely(Environment.claudecode[Text]) == t"1" then Claude
    else Human

  // The invoker a record names; a word no fume wrote is read as a human's.
  def of(word: Text): Invoker = all.seek(_.word == word).or(Human)

// Who or what ran the `fume` command: an agent, when its environment says so — Codex sets
// `CODEX_SANDBOX=1` and Claude Code `CLAUDECODE=1` — or otherwise a human; or another fume,
// when this daemon is a worker running a selection sent to it by a controller. Recorded on
// every run in the journal, so the dashboard can mark the runs an agent launched; or an MCP
// client, when the run was launched through the daemon's MCP server.
enum Invoker:
  case Human, Claude, Codex, Remote, Mcp

  // The word a run's record carries.
  def word: Text = this match
    case Human  => RunRecord.human
    case Claude => RunRecord.claude
    case Codex  => RunRecord.codex
    case Remote => RunRecord.remote
    case Mcp    => RunRecord.mcp

  // The invoker named in words, where its icon cannot be shown.
  def name: Text = this match
    case Human  => t"a human"
    case Claude => t"Claude"
    case Codex  => t"Codex"
    case Remote => t"another fume"
    case Mcp    => t"an MCP client"

  // The basename of the invoker's icon among the client's `fume/` resources; a human has none,
  // and a remote controller is named in words.
  def icon: Optional[Text] = this match
    case Human  => Unset
    case Claude => t"claude"
    case Codex  => t"codex"
    case Remote => Unset
    case Mcp    => t"mcp"
