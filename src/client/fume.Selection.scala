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

import denominative.dysasymptotics.linearSize

// Every operand of a REPEATABLE flag (`--tag a --tag b`), where the `Text` topic reads only
// the first occurrence's.
case class Repeated(values: List[Text])

object Repeated:
  given interpretable: Repeated is Interpretable = arguments =>
    Repeated(arguments.map { (argument: Argument) => argument() })

// The selection vocabulary fume adds over Probably's positional terms: flag spellings that
// give tab-completion a clear anchor, each LOWERED to the positional wire term the suite
// parses, so that suites see one grammar however the user spelt it. Pure, and unit-tested.
object Selection:
  val kindNames: List[Text] = List(t"test", t"bench", t"stress", t"profile")

  // The wire terms for a run: `kind:` terms (from the kind switches and `--kind`, which may be
  // comma-separated), `tag:` terms (one per `--tag`, so repeats intersect), axis constraints
  // verbatim, `not:` terms (one per `--exclude`), then the positional terms as written.
  // Identity terms union; kinds, tags and constraints intersect; exclusions subtract last.
  def lower
     ( kinds:    List[Text],
       tags:     List[Text],
       axes:     List[Text],
       excludes: List[Text],
       terms:    List[Text] )
  :   List[Text] =

    val kindTerms: List[Text] =
      kinds.flatMap(_.cut(t",")).filter(_ != t"").distinct.map { (kind: Text) => t"kind:$kind" }

    val tagTerms: List[Text] = tags.filter(_ != t"").map { (tag: Text) => t"tag:$tag" }
    val axisTerms: List[Text] = axes.filter(_ != t"")
    val exclusions: List[Text] = excludes.filter(_ != t"").map { (term: Text) => t"not:$term" }

    kindTerms + tagTerms + axisTerms + exclusions + terms

  // The same lowering read straight off the raw words after the subcommand — for completion,
  // which needs the OTHER arguments' meaning without a parsed command line: which tests are
  // identified, and which kinds and tags narrow them. Flags fume's grammar does not know
  // consume nothing; its own value-taking flags consume the following word (or their `=`
  // suffix), and the operands of `--classpath`, `--suite` and the settings are skipped.
  def words(arguments: List[Text]): List[Text] =
    def value(flag: Text, next: List[Text]): (Optional[Text], List[Text]) =
      if flag.contains(t"=") then (flag.after(flag.offsetOf("=").or(Prim)), next)
      else next match
        case operand :: tail => (operand, tail)
        case _               => (Unset, Nil)

    def named(flag: Text, long: Text, short: Text): Boolean =
      flag == t"--$long" || flag.starts(t"--$long=") || flag == t"-$short"

    def recur
       ( remaining: List[Text],
         kinds:     List[Text],
         tags:      List[Text],
         axes:      List[Text],
         excludes:  List[Text],
         terms:     List[Text] )
    :   List[Text] =

      remaining match
        case flag :: tail if flag.starts(t"-") =>
          if kindNames.exists { (kind: Text) => flag == t"--$kind" }
          then recur(tail, kinds :+ flag.skip(2), tags, axes, excludes, terms)
          else if named(flag, t"kind", t"k") then
            val (operand, rest) = value(flag, tail)
            recur(rest, kinds + operand.lay(Nil: List[Text])(List(_)), tags, axes, excludes, terms)
          else if named(flag, t"tag", t"t") then
            val (operand, rest) = value(flag, tail)
            recur(rest, kinds, tags + operand.lay(Nil: List[Text])(List(_)), axes, excludes, terms)
          else if named(flag, t"axis", t"a") then
            val (operand, rest) = value(flag, tail)
            recur(rest, kinds, tags, axes + operand.lay(Nil: List[Text])(List(_)), excludes, terms)
          else if named(flag, t"exclude", t"x") then
            val (operand, rest) = value(flag, tail)
            recur(rest, kinds, tags, axes, excludes + operand.lay(Nil: List[Text])(List(_)), terms)
          else if skipped.has(flag)
          then recur(tail match { case _ :: rest => rest; case _ => Nil }, kinds, tags, axes, excludes, terms)
          else recur(tail, kinds, tags, axes, excludes, terms)

        case term :: tail =>
          recur(tail, kinds, tags, axes, excludes, terms :+ term)

        case _ =>
          lower(kinds, tags, axes, excludes, terms)

    recur(arguments, Nil, Nil, Nil, Nil, Nil)

  // The value-taking flags whose operands are NOT selection terms, in the spellings that take
  // the following word (an `--flag=value` spelling is one word, and consumes nothing).
  private val skipped: List[Text] =
    List(t"--classpath", t"-c", t"--suite", t"-s", t"--fail-fast", t"--max-load",
         t"--duration-scale", t"--target")
