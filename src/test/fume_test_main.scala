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

import probably.TestEvent
import stdios.fileDescriptorStdio
import termcapDefinitions.basicTermcap

// Fume's own suite, run WITHOUT fume: a `Suite` has no `main` (the host — normally fume —
// drives it through `invoke`), so this is the plain-`java` entry point `make test` and CI use,
// printing one line per completed test and exiting with the suite's status (0 = passed,
// 1 = failures, 2 = the suite threw). `fume run -c <test jar>` remains the full experience.
@main
def runTests(): Unit = runSuite(t"")

// The same, for a given selection: `Tests.main` calls this when a HOST falls back to forking
// `java -cp <classpath> fume.Tests <terms…>`, which is what a fume too old to read this jar's
// event schema does — the case whenever this repository pins a Soundness newer than the
// released fume that tests it.
def runSuite(arguments: Text): Unit =
  val passes: Atomic[Int] = Atomic(0)
  val failures: Atomic[Int] = Atomic(0)

  val status = Tests.invoke(arguments, event => event match
    case TestEvent.TestCompleted(test, _, _, outcome, _, _) =>
      if outcome.outcome == t"pass" || outcome.outcome == t"aspire-pass" then passes.since(_ + 1)
      else failures.since(_ + 1)

      Out.println(t"[${outcome.outcome}] ${test.path.join(t" / ")}")

    case TestEvent.DetailMessage(_, message) =>
      Out.println(t"    $message")

    case TestEvent.DetailCompare(_, expected, found, _) =>
      Out.println(t"    expected: $expected")
      Out.println(t"    found:    $found")

    case TestEvent.RunTerminated(error, _, _) =>
      Out.println(t"suite threw: ${error.components.map(_.message).join(t"; ")}")

    case _ => ())

  Out.println(t"${passes()} passed, ${failures()} failed")
  Exit(status).terminate()
