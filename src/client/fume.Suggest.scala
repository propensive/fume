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
┃    Fume, version 0.1.0.                                                                          ┃
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

// Tab-completion for selection terms, as PURE logic over the classpath's cached schedule: the
// focused word, the other arguments' wire terms, and every scheduled test in. No I/O, so it is
// fully unit-testable. The rules:
//
//   - A bare word offers the kinds, `tag:<name>` for every tag on the classpath, the ids and
//     monikers of every test, and — once at least one test is IDENTIFIED by the other
//     arguments (an id, moniker or glob; `--suite` alone does not count) — an `<axis>=` stub for
//     each axis of the identified tests, marked incomplete so the shell stops at the `=`.
//   - A word `<axis>=…` (cursor after the `=`) offers that axis's values across the identified
//     tests: a discrete axis's labels; a numeric axis's declared values plus `lo..hi`, `lo..`
//     and `..hi` templates from the observed extremes; an emergent axis's range templates from
//     its declared bounds. After a comma (`N=4,`) the remaining values follow, with the typed
//     prefix retained. With nothing identified, an axis word offers nothing at all.
object Suggest:
  // `kind:test` selects what the event protocol calls a `check`.
  private def kindOf(name: Text): Text = if name == t"test" then t"check" else name

  // Whether a term identifies tests (an id, a moniker or a glob) rather than narrowing them.
  def identity(term: Text): Boolean =
    term != t"" && !term.starts(t"-") && !term.starts(t"kind:") && !term.starts(t"tag:")
    && !term.starts(t"not:") && axisOf(term).absent

  // The axis of an axis-constraint term (`N=4`, `N<32`, `N>=4`), or `Unset` if the term is not
  // one: a non-empty label followed by a comparison.
  def axisOf(term: Text): Optional[Text] =
    val index: Int =
      List('=', '<', '>').map { (char: Char) => term.s.indexOf(char.toInt) }
      . filter(_ >= 0).fold(-1) { (least, next) => if least < 0 || next < least then next else least }

    if index <= 0 then Unset else term.keep(index)

  // Whether an identity term identifies the test, by Probably's own rules approximated over
  // the wire ref: its id, its moniker, any segment of its path (a suite moniker or name), or a
  // glob over a segment or the slash-joined path.
  def matches(ref: TestEvent.Ref, term: Text): Boolean =
    ref.id == term || ref.moniker == term || ref.path.has(term)
    || safely(Glob.parse(term)).lay(false): glob =>
         ref.path.exists(glob.matches(_)) || glob.matches(ref.path.join(t"/"))

  // The tests the other arguments identify: those matched by any identity term, narrowed by
  // the `kind:` and `tag:` terms among them. Empty when no identity term is present.
  def identified(others: List[Text], schedule: List[Suites.Scheduled]): List[Suites.Scheduled] =
    val identities: List[Text] = others.filter(identity)

    if identities.nil then Nil else
      val kinds: List[Text] = others.filter(_.starts(t"kind:")).map { term => kindOf(term.skip(5)) }

      val tags: List[List[Text]] =
        others.filter(_.starts(t"tag:")).map { term => term.skip(4).cut(t",").filter(_ != t"") }

      schedule.filter: test =>
        identities.exists(matches(test.ref, _))
        && (kinds.nil || kinds.has(test.kind))
        && tags.all { alternatives => alternatives.exists(test.tags.has(_)) }

  def apply(word: Text, others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    axisOf(word) match
      case axis: Text if word.contains(t"=") =>
        values(axis, word.after(word.offsetOf("=").or(Prim)), identified(others, schedule))

      case _ =>
        bare(others, schedule)

  // The comma-continued names for the `--kind` and `--tag` operands: what remains after the
  // names already typed, behind the typed prefix.
  def kinds(operand: Text): List[Suggestion] =
    continue(operand, Selection.kindNames, describeKind)

  // `run only stresss` is what naive pluralisation gives; each kind has its own plural.
  private def describeKind(kind: Text): Text = kind match
    case t"test"    => t"run only unit tests"
    case t"bench"   => t"run only benchmarks"
    case t"stress"  => t"run only stress tests"
    case t"profile" => t"run only profiles"
    case other      => t"run only $other"

  def tags(operand: Text, schedule: List[Suites.Scheduled]): List[Suggestion] =
    val all: List[Text] = schedule.flatMap(_.tags).distinct
    continue(operand, all, { (tag: Text) => t"${count(tag, schedule)} tagged $tag" })

  private def count(tag: Text, schedule: List[Suites.Scheduled]): Int =
    schedule.count(_.tags.has(tag))

  // Named methods rather than lambdas throughout: an interpolation inside a lambda passed to a
  // collection combinator runs its implicit search while the element type is still
  // uninstantiated, tripping dotc's `wildApprox` assertion (scala/scala3#24824).
  private def tagged(tag: Text, schedule: List[Suites.Scheduled]): Text =
    val n: Int = count(tag, schedule)
    t"$n tagged $tag"

  private def kindSuggestion(kind: Text): Suggestion =
    Suggestion(t"kind:$kind", describeKind(kind))

  private def tagSuggestion(schedule: List[Suites.Scheduled])(tag: Text): Suggestion =
    val description: Text = tagged(tag, schedule)
    Suggestion(t"tag:$tag", description)

  private def testSuggestions(test: Suites.Scheduled): List[Suggestion] =
    val path: Text = test.ref.path.join(t"/")
    val kind: Text = if test.kind == t"check" then t"test" else test.kind
    val description: Text = t"$kind  $path"
    val hash = Suggestion(test.ref.id, description)
    test.ref.moniker.lay(List(hash)) { moniker => List(hash, Suggestion(moniker, description)) }

  private def stub(axis: Text): Suggestion =
    val description: Text = t"constrain the $axis axis"
    Suggestion(t"$axis=", description, incomplete = true)

  private def valueSuggestion(prefix: Text)(label: Text): Suggestion =
    Suggestion(label, incomplete = true, prefix = prefix)

  private def template(prefix: Text, core: Text, description: Text): Suggestion =
    Suggestion(core, description, incomplete = true, prefix = prefix)

  // The candidates not yet in the comma-separated `typed`, each completing behind the part of
  // it up to and including the last comma, and incomplete so a further comma can follow.
  private def continue(typed: Text, candidates: List[Text], describe: Text => Text)
  :   List[Suggestion] =

    val comma: Int = typed.s.lastIndexOf(',')
    val prefix: Text = if comma < 0 then t"" else typed.keep(comma + 1)
    val chosen: List[Text] = prefix.cut(t",").filter(_ != t"")

    def suggestion(candidate: Text): Suggestion =
      val description: Text = describe(candidate)
      Suggestion(candidate, description, incomplete = true, prefix = prefix)

    candidates.filter(!chosen.has(_)).map(suggestion)

  private def bare(others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    val kinds: List[Suggestion] = Selection.kindNames.map(kindSuggestion)
    val tags: List[Suggestion] = schedule.flatMap(_.tags).distinct.map(tagSuggestion(schedule))
    val tests: List[Suggestion] = schedule.flatMap(testSuggestions)

    kinds + tags + tests.distinct + stubs(others, schedule)

  // An `<axis>=` stub for each axis of the identified tests, incomplete so the shell stops
  // at the `=` and the values can follow.
  private def stubs(others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    identified(others, schedule).flatMap(_.axes).map(_.axis).distinct.map(stub)

  // The `--axis` operand: the stubs alone until an `=` is typed, then that axis's values.
  def axes(word: Text, others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    if word.contains(t"=") then apply(word, others, schedule) else stubs(others, schedule)

  private def numeric(text: Text): Optional[Double] =
    if text.s.matches("-?[0-9]+(\\.[0-9]+)?") then java.lang.Double.parseDouble(text.s) else Unset

  // A bound rendered in the axis's own domain: `4` on an integral axis, not `4.0`.
  private def render(number: Double, integral: Boolean): Text =
    if integral then number.toLong.show else java.lang.Double.toString(number).tt

  private def values(axis: Text, typed: Text, tests: List[Suites.Scheduled]): List[Suggestion] =
    val axes: List[TestEvent.AxisSchedule] = tests.flatMap(_.axes).filter(_.axis == axis)

    if axes.nil then Nil else
      val comma: Int = typed.s.lastIndexOf(',')
      val prefix: Text = if comma < 0 then t"" else typed.keep(comma + 1)
      val chosen: List[Text] = prefix.cut(t",").filter(_ != t"")
      val labels: List[Text] = axes.flatMap(_.values).distinct
      val discrete: Boolean = axes.all(_.domain == t"discrete")
      val integral: Boolean = axes.all(_.domain == t"integral")

      val remaining: List[Suggestion] =
        labels.filter(!chosen.has(_)).map(valueSuggestion(t"$axis=$prefix"))

      // Range templates only for a numeric axis, and only at the start of the word: a
      // membership list (`N=4,`) cannot continue with a range. The extremes are the least and
      // greatest of the declared values and any emergent bounds.
      val templates: List[Suggestion] =
        if discrete || prefix != t"" then Nil else
          val numbers: List[Double] =
            labels.flatMap { label => numeric(label).lay(Nil: List[Double])(List(_)) }
            + axes.flatMap { axis => axis.least.lay(Nil: List[Double])(List(_)) }
            + axes.flatMap { axis => axis.most.lay(Nil: List[Double])(List(_)) }

          if numbers.nil then Nil else
            val least: Double = numbers.fold(Double.MaxValue) { (a, b) => if b < a then b else a }
            val most: Double = numbers.fold(Double.MinValue) { (a, b) => if b > a then b else a }
            val lo: Text = render(least, integral)
            val hi: Text = render(most, integral)
            val stem: Text = t"$axis="

            List
              ( template(stem, t"$lo..$hi", t"from $lo to $hi"),
                template(stem, t"$lo..", t"at least $lo"),
                template(stem, t"..$hi", t"at most $hi") )

      remaining + templates

  // A schedule row's axes as `fume list --axes` shows them: `label=v1,v2` or, for an emergent
  // axis with bounds and no values, `label=least..most`; `;`-joined, or `-` for none.
  def axesText(axes: List[TestEvent.AxisSchedule]): Text =
    if axes.nil then t"-" else axes.map(axisText).join(t";")

  private def axisText(axis: TestEvent.AxisSchedule): Text =
    val integral: Boolean = axis.domain == t"integral"
    val least: Text = axis.least.lay(t"")(render(_, integral))
    val most: Text = axis.most.lay(t"")(render(_, integral))
    val values: Text = if !axis.values.nil then axis.values.join(t",") else t"$least..$most"
    t"${axis.axis}=$values"
