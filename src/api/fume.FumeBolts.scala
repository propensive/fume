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

// The bolt codes of Fume, `↯/f.29` to `↯/f.32`: the subdivisions of the letter Fume shares with
// Pyrocosm and its other tools (`pyrocosm.Bolts`), one for each of Fume's modules. The record of
// what every code means is `f.tel` in propensive/bolt. Declared here, in the module every other
// module of Fume's that has errors depends on.
object FumeBolts:
  final val api      = 29
  final val relay    = 30
  final val client   = 31
  final val launcher = 32

  // The code of the `error`th error of the client, or of its `variant`th variant.
  def apply(error: Int, variant: Int = 0): fulminate.Bolt =
    pyrocosm.Bolts(client, error, variant)

  // The API's one error is `Api.Error`, `↯/f.29.1`. The client's are failures it reports other
  // than through an error type, each printed after its code's label (`Render.announce`): a
  // worker's rejection of a run, which it sends to the controller; a machine which is neither
  // on the swarm nor declared; a classpath with no suites; a suite's event schema which is not
  // fume's; an event consumer which failed; a suite a worker could not stream; a connection to
  // a worker lost, or a run it rejected; a connection which could not be made, carrying the
  // code of Pyrocosm's `Peer.Error`; a run which timed out; no classpath; an identity which
  // could not be created; a subcommand not yet implemented; and a suite which could not be
  // invoked.
  final val rejection     = 1
  final val noMachine     = 2
  final val noSuites      = 3
  final val incompatible  = 4
  final val consumer      = 5
  final val unstreamable  = 6
  final val remote        = 7
  final val connection    = 8
  final val timedOut      = 9
  final val noClasspath   = 10
  final val identity      = 11
  final val unimplemented = 12
  final val uninvocable   = 13
