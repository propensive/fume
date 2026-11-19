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

import filesystemBackends.javaBaseFilesystem
import systems.javaBaseSystem
import temporaryDirectories.systemTemporaryDirectory

// A suite run with `--fork` gets a temporary directory of its own: its JVM's `java.io.tmpdir`,
// and `TMPDIR` for whatever that JVM starts. The directory, and everything the suite leaves in
// it, is deleted once the JVM has exited, however it ended: so a suite's forgotten scratch files
// do not outlive its run, as they would in the shared temporary directory, where the daemon
// which starts every run would keep them.
object Scratches:
  // A forked suite's JVM may call the restricted methods of the foreign function API — to open
  // a pseudo-terminal, say — as a suite run in-process may, without the JDK warning, in its own
  // output, that a later release will forbid it unless native access is enabled.
  val nativeAccess: Text = t"--enable-native-access=ALL-UNNAMED"

  // Lends `block` a fresh directory beneath this JVM's temporary directory, and an environment
  // which is the given one with `TMPDIR` naming it.
  def lend[result](block: (Text, Environment) => result)(using outer: Environment)
    ( using Tactic[Io.Error] )
  :   result =

    temporaryDirectory[Path on Linux].open[Scratch](Read & Write): scratch ?=>
      val directory: Text = scratch.stem.encode
      block(directory, Scratches.environment(outer, directory))

  // `outer`, with `TMPDIR` naming `directory`. A child process is started with exactly the
  // variables of an environment which can be enumerated; one which cannot (a lambda) leaves the
  // child the daemon's own, and only `java.io.tmpdir` then names the directory.
  def environment(outer: Environment, directory: Text): Environment = new Environment:
    def variable(name: Text): Optional[Text] =
      if name == t"TMPDIR" then directory else outer.variable(name)

    override def entries: Optional[Map[Text, Text]] =
      outer.entries.let(_.define(t"TMPDIR", directory))
