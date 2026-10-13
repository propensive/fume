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

import denominative.dysasymptotics.linearSize

import probably.TestEvent

// Tab-completion for selection terms, as PURE logic over the classpath's cached index and
// schedule. No I/O, so it is fully unit-testable. The rules:
//
//   - A positional word (`terms`) offers what somebody NAMED, and little else. An empty word
//     offers the first moniker on the way to each test, which is its suite's wherever the suite
//     has one. A word with something typed offers every moniker beginning with it, and the ids
//     which do too: a suite's derived id, and the hex id of a test or a group.
//   - A word with a scope (`threads/`, `threads/errors/`) offers what is below the scope: for
//     each test, the first moniker on the way to it, or its hex id if nothing on the way is
//     named; and, once more is typed, the monikers and hex ids below which begin with it.
//   - Each is described by the names from the scope down to it, so nothing typed is repeated.
//   - The `--axis` operand (`axes`) offers an `<axis>=` stub for each axis of the tests the
//     other arguments IDENTIFY (an id, moniker or glob; `--suite` alone does not count), marked
//     incomplete so the shell stops at the `=`; then, after the `=`, that axis's values: a
//     discrete axis's labels; a numeric axis's declared values plus `lo..hi`, `lo..` and `..hi`
//     templates from the observed extremes; an emergent axis's range templates from its
//     declared bounds. After a comma (`N=4,`) the remaining values follow, with the typed
//     prefix retained. With nothing identified, it offers nothing at all.
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

  // One candidate for a term: the handle to type for a link, and where the link is among
  // those below the scope, which is what describes it.
  private case class Candidate(handle: Text, depth: Int, links: List[Index.Link])

  // The handles by which the link at `depth` answers to the typed text: its moniker, and its
  // ids — a suite's derived one, at the top level, and anything's hex.
  private def handles(link: Index.Link, depth: Int, links: List[Index.Link], typed: Text, top: Boolean)
  :   List[Candidate] =

    val written: List[Text] = link.written.lay(Nil: List[Text])(List(_))
    val hex: List[Text] = if link.id == t"" then Nil else List(link.id)

    val derived: List[Text] =
      if top && link.derived then link.moniker.lay(Nil: List[Text])(List(_)) else Nil

    val names: List[Text] = written + derived + hex

    names.filter(_.starts(typed)).map(Candidate(_, depth, links))

  // The one candidate on the way to a test when nothing is typed: the first link somebody
  // named; failing that, within a scope, the test by its hex id; and at the top level nothing.
  private def nearest(links: List[Index.Link], top: Boolean): List[Candidate] =
    def recur(todo: List[Index.Link], depth: Int): List[Candidate] = todo match
      case link :: tail =>
        link.written.lay(recur(tail, depth + 1)) { moniker => List(Candidate(moniker, depth, links)) }

      case _ =>
        links.last.lay(Nil: List[Candidate]): leaf =>
          if top || leaf.id == t"" then Nil else List(Candidate(leaf.id, links.size - 1, links))

    recur(links, 0)

  private def matching(links: List[Index.Link], typed: Text, top: Boolean): List[Candidate] =
    def recur(todo: List[Index.Link], depth: Int): List[Candidate] = todo match
      case link :: tail => handles(link, depth, links, typed, top) + recur(tail, depth + 1)
      case _            => Nil

    recur(links, 0)

  // A candidate's suggestion: completed behind the scope, described by the names down to it,
  // and left incomplete unless it is a test, so that a `/` and what is below can follow.
  private def suggestion(scope: Text)(candidate: Candidate): Suggestion =
    val description: Text = candidate.links.keep(candidate.depth + 1).map(_.name).join(t" → ")
    val group: Boolean = candidate.depth + 1 < candidate.links.size

    Suggestion
      ( candidate.handle,
        description,
        incomplete = group,
        prefix     = scope,
        operand    = true,
        display    = if scope == t"" then Unset else candidate.handle )

  // The completions of a positional term: see the rules above. Every one is an operand VALUE,
  // not a subcommand: the help tree (the manpage that `fume install` writes) is built by
  // probing the suggestions at each prefix, and descending into terms would enumerate every
  // ordering of the tests on the classpath.
  def terms(word: Text, tests: List[Index.Test]): List[Suggestion] =
    val slash: Int = word.s.lastIndexOf('/')
    val scope: Text = if slash < 0 then t"" else word.keep(slash + 1)
    val typed: Text = if slash < 0 then word else word.skip(slash + 1)
    val path: List[Text] = Index.segments(scope)
    val top: Boolean = path.nil

    def below(test: Index.Test): List[List[Index.Link]] =
      if top then List(test.links)
      else Index.locate(test.links, path).lay(Nil: List[List[Index.Link]]) { found => List(found.skip(1)) }

    def candidates(links: List[Index.Link]): List[Candidate] =
      if typed == t"" then nearest(links, top) else matching(links, typed, top)

    val seen: scala.collection.mutable.HashSet[Text] = scala.collection.mutable.HashSet()

    tests.flatMap(below).flatMap(candidates).filter { candidate => seen.add(candidate.handle) }
    . map(suggestion(scope))

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

  // An `<axis>=` stub for each axis of the identified tests, incomplete so the shell stops
  // at the `=` and the values can follow.
  private def stubs(others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    identified(others, schedule).flatMap(_.axes).map(_.axis).distinct.map(stub)

  // The `--axis` operand: the stubs alone until an `=` is typed, then that axis's values.
  def axes(word: Text, others: List[Text], schedule: List[Suites.Scheduled]): List[Suggestion] =
    axisOf(word) match
      case axis: Text if word.contains(t"=") =>
        values(axis, word.after(word.offsetOf("=").or(Prim)), identified(others, schedule))

      case _ =>
        if word.contains(t"=") then Nil else stubs(others, schedule)

  private def numeric(text: Text): Optional[Double] = text.absolve match
    case r"-?[0-9]+" | r"-?[0-9]+\.[0-9]+" => safely(text.as[Double])
    case _                                 => Unset

  // A bound rendered in the axis's own domain: `4` on an integral axis, not `4.0`. A decimal is
  // rendered as it was typed, which is the shortest form that reads back as itself.
  private def render(number: Double, integral: Boolean): Text =
    if integral then number.toLong.show else number.toString.tt

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
