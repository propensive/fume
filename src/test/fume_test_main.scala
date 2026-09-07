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
┃    Fume, version 0.2.0.                                                                          ┃
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

import java.lang as jl
import java.util.concurrent.atomic as juca

import soundness.*
import probably.TestEvent

// Fume's own suite, run WITHOUT fume: a `Suite` has no `main` (the host — normally fume —
// drives it through `invoke`), so this is the plain-`java` entry point `make test` and CI use,
// printing one line per completed test and exiting with the suite's status (0 = passed,
// 1 = failures, 2 = the suite threw). `fume run -c <test jar>` remains the full experience.
@main
def runTests(): Unit =
  val passes = juca.AtomicInteger(0)
  val failures = juca.AtomicInteger(0)
  val out = jl.System.out.nn

  val status = Tests.invoke(t"", event => event match
    case TestEvent.TestCompleted(test, _, _, outcome, _, _) =>
      if outcome.outcome == t"pass" || outcome.outcome == t"aspire-pass" then passes.incrementAndGet()
      else failures.incrementAndGet()
      out.println(t"[${outcome.outcome}] ${test.path.join(t" / ")}".s)

    case TestEvent.DetailMessage(_, message) =>
      out.println(t"    $message".s)

    case TestEvent.DetailCompare(_, expected, found, _) =>
      out.println(t"    expected: $expected".s)
      out.println(t"    found:    $found".s)

    case TestEvent.RunTerminated(error, _, _) =>
      out.println(t"suite threw: ${error.components.map(_.message).join(t"; ")}".s)

    case _ => ())

  out.println(t"${passes.get} passed, ${failures.get} failed".s)
  jl.System.exit(status)
