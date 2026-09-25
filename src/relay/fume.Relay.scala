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

import pyrocosm.{Blobs, Channel}

// Fume's own messages over a Pyrocosm `Channel`: what a controller (the fume the user invoked)
// and a worker (the fume daemon on another machine) say to each other once Pyrocosm's handshake
// has welcomed the connection. Bulk payloads — a classpath entry being shipped, a suite's test
// event frame — travel as the RAW frame that follows the message announcing them, never through
// this codec; and a worker never decodes a suite's event frames at all: they are Probably's
// `Streamer` frames, forwarded byte for byte, so the controller checks their schema
// fingerprint against its own exactly as it does for a local suite. Only this enum's layout
// must agree between the two fumes.
//
//   controller → worker   plan      the classpath (by digest), the suites, the selection
//   worker → controller   need      the digests the worker's blob store lacks
//   controller → worker   blob …    one chunk of one entry, followed by its bytes
//   controller → worker   start     everything is there; run
//   worker → controller   began     a suite is starting
//   worker → controller   frame …   one of the suite's event frames follows
//   worker → controller   captured  what the suite printed outside its report
//   worker → controller   ended     how the suite ended
//   controller → worker   abort     Ctrl+C at the controller
//   worker → controller   done      the run is over
//   worker → controller   rejected  the plan could not be run at all
enum Relay:
  case Plan
    ( entries:   List[Blobs.Entry],
      suites:    List[Text],
      arguments: List[Text],
      maxLoad:   Optional[Text] )

  case Need(digests: List[Text])
  case Blob(digest: Text, offset: Int, last: Boolean)
  case Start
  case Began(suite: Text)
  case Frame(suite: Text)
  case Captured(suite: Text, out: Text, err: Text)

  // `outcome` is one of `completed` (with the suite's `exit` status), `failed` (the worker's
  // consumer threw; `detail` is the trace), or `legacy` (the suite's Probably cannot stream
  // events, which a worker does not run). An incompatible schema is the CONTROLLER's finding,
  // made from the fingerprint frame, so it is not an outcome here.
  case Ended(suite: Text, outcome: Text, exit: Int, detail: Text)
  case Abort
  case Done
  case Rejected(reason: Text)

object Relay:
  // Derived once: the schema, its fingerprint (the protocol the handshake names) and the codec.
  lazy val codec: Channel.Codec[Relay] =
    import Channel.derivation.throwing
    val schema: Tels = Tels.tels[Relay](t"fume-relay")

    Channel.Codec
      ( t"fume-relay", schema, message => Channel.encode(message, schema),
        data => Channel.decode[Relay](data) )

  val completed: Text = t"completed"
  val failed: Text = t"failed"
  val legacy: Text = t"legacy"

  // The port a fume worker listens on by default; the dashboard is 8090.
  val port: Int = 8091
