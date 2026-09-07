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

import java.util.concurrent.atomic as juca

import soundness.*

import fume.Figures.measurable
import probably.TestEvent

// The arithmetic behind `--target`: a whole run's measurement time, budgeted. The schedule
// pre-pass (`--list` on the event protocol) streams one `TestScheduled` per admitted test,
// and since the timed kinds carry their expected measuring time — declared metadata, scaled
// by nothing at this point — the factor that makes the run fit the budget is simply
// `target/expected`. Only MEASUREMENT time is budgeted: staging, compilation and the unit
// tests around the benchmarks cost what they cost, on top.
object Budget:
  // `90` (seconds when bare), `90s`, `10m`, `2h`, decimals allowed (`1.5m`); nanoseconds out.
  // Anything else — including zero and negative — is `Unset`.
  def parse(text: Text): Optional[Long] =
    if text.length == 0 then Unset else
      val (digits, multiplier): (Text, Double) = text.s.charAt(text.length - 1) match
        case 's' => (text.keep(text.length - 1), 1.0)
        case 'm' => (text.keep(text.length - 1), 60.0)
        case 'h' => (text.keep(text.length - 1), 3600.0)
        case _   => (text, 1.0)

      safely(digits.as[Double]).lay(Unset: Optional[Long]): value =>
        if value > 0.0 then (value*multiplier*1e9).toLong else Unset

  // The sum, in nanoseconds, of every admitted timed test's expected measuring time, across
  // all the suites of the run. A suite that cannot stream (pre-event, or an incompatible
  // Soundness) contributes nothing — its measurements will simply run at their declared
  // lengths — and untimed checks carry no estimate to add.
  def expected(classpath: LocalClasspath, suites: List[Text], args: List[Text])
     (using Stdio, Monitor)
  :   Long =

    val total: juca.AtomicLong = juca.AtomicLong(0L)

    suites.each: suite =>
      // The abort thunk is passed explicitly: the DEFAULT argument's root capability cannot
      // flow into `safely`'s enclosing function under capture checking (as in `runSuite`).
      safely:
        EventStream.stream(classpath, suite, t"--list" :: args)(
          { case TestEvent.TestScheduled(_, _, expected, _, _) => expected.let(total.addAndGet(_)).unit
            case _                                       => () },
          () => false)
      . unit

    total.get

  // The factor, rendered to nine decimal places by hand: `Double.toString` falls into
  // exponent notation for small values, which probably's number parser does not read, and
  // `String.format` follows the locale's decimal separator, which it does not read either.
  // Floored at a nanofactor so a colossal expectation cannot round the factor to zero, which
  // the suite would ignore.
  def factor(target: Long, expected: Long): Text =
    val nanofactor: Long = ((target.toDouble/expected.toDouble)*1e9).toLong.max(1L)
    t"${nanofactor/1_000_000_000L}.${(nanofactor%1_000_000_000L).show.pad(9, Rtl, '0')}"

  // `12.3s` for sub-minute budgets, `4m06s` above: enough precision to confirm what was asked.
  def show(nanos: Long): Text =
    val tenths: Long = nanos/100_000_000L
    if tenths < 600L then t"${tenths/10L}.${tenths%10L}s"
    else t"${tenths/600L}m${(tenths%600L/10L).show.pad(2, Rtl, '0')}s"
