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

// The STATIC index of tests: what the beneficence compiler plugin records, from the typed
// trees it compiles, in `META-INF/probably/tests/<source>` beside the suite index. It says which
// tests each suite declares, in which groups, with their kinds, monikers and tags, and each
// suite's id (given, or derived from its title) which heads its tests' paths — so fume
// can enumerate a classpath's tests WITHOUT running any suite. A listing made by running the
// suites (`Suites.fetch`) executes every statement around their tests, which can be slow, and
// can have effects; that is still what prices a `--target` budget and finds a test's axes, but
// completion and `fume list` read this instead.
//
// The index is exact about what is written in the source and silent about what is not: a test
// whose name is computed (`\*` in the index, `dynamic` here) is known by its place and its
// name's fixed parts alone, a test over axes (`spread`) by everything but its cells, and the
// tests of an `impromptu` block not at all, though the block is marked (`Open`). The format is
// documented with the plugin (`beneficence.TestsIndex`); a classpath built by a Probably which
// writes no index simply has none, and is listed by running it, as before.
object Index:
  private val prefix: String = "META-INF/probably/tests/"

  // One step of a test's path from its suite: a suite, a group, or the test itself. The id is
  // empty for anything whose name, or any name above it, is only known at runtime; `dynamic`
  // says whether this link's own name has such a hole, shown as `*` in it.
  case class Link(name: Text, moniker: Optional[Text], id: Text, dynamic: Boolean = false)

  case class Test
     ( links:   List[Link],
       kind:    Text,
       tags:    List[Text],
       spread:  Boolean,
       dynamic: Boolean,
       file:    Text,
       line:    Int ):

    def path: List[Text] = links.map { link => link.moniker.or(link.name) }

    // The test's own id, empty if its name or its place is only known at runtime.
    def id: Text = links.last.let(_.id).or(t"")

    // The test's line of `fume list`: its id (dots, when it has none), its kind as the
    // selection grammar spells it, and its path.
    def listing: Text =
      val shown: Text = if id == t"" then t"······" else id
      t"$shown  ${if kind == t"check" then t"test" else kind}  ${path.join(t"/")}"

    def scheduled: Suites.Scheduled =
      val leaf: Link = links.last.or(Link(t"", Unset, t""))
      Suites.Scheduled(TestEvent.Ref(leaf.id, leaf.name, leaf.moniker, path, file, line), kind, tags, Nil)

  // A name as the index holds it, with whether any part of it was a hole.
  private[fume] case class Name(text: Text, dynamic: Boolean)

  private[fume] enum Line:
    case Suite(className: Text, topic: Text, title: Text)
    case Group(topic: Text, method: Text, path: List[Name], name: Name, moniker: Optional[Text])

    case Declaration
       ( topic:   Text,
         method:  Text,
         path:    List[Name],
         kind:    Text,
         name:    Name,
         moniker: Optional[Text],
         tags:    List[Text],
         file:    Text,
         line:    Int,
         spread:  Boolean )

    case Nest(topic: Text, method: Text, path: List[Name], nested: Text)
    case Call(topic: Text, method: Text, path: List[Name], callee: Text)
    case Open(topic: Text, method: Text, path: List[Name])

  // A field, unescaped: `\\`, `\t`, `\n`, `\/`, and `\*` for a hole, shown as `*`.
  private def name(field: Text): Name =
    val builder: StringBuilder = StringBuilder()
    val source: String = field.s

    def recur(index: Int, dynamic: Boolean): Boolean =
      if index >= source.length then dynamic
      else if source.charAt(index) == '\\' && index + 1 < source.length then
        source.charAt(index + 1) match
          case 't' => builder.append('\t')
          case 'n' => builder.append('\n')
          case char => builder.append(char)

        recur(index + 2, dynamic || source.charAt(index + 1) == '*')
      else
        builder.append(source.charAt(index))
        recur(index + 1, dynamic)

    val dynamic: Boolean = recur(0, false)
    Name(builder.toString.tt, dynamic)

  // A path field's segments: split at each `/` which no backslash escapes.
  private def path(field: Text): List[Name] =
    val source: String = field.s

    def recur(index: Int, start: Int, done: List[Name]): List[Name] =
      if index >= source.length
      then (name(source.substring(start).nn.tt) :: done).reverse
      else if source.charAt(index) == '\\' then recur(index + 2, start, done)
      else if source.charAt(index) == '/'
      then recur(index + 1, index + 1, name(source.substring(start, index).nn.tt) :: done)
      else recur(index + 1, start, done)

    if source.isEmpty then Nil else recur(0, 0, Nil)

  private def optional(text: Text): Optional[Text] = if text == t"" then Unset else text

  // One line, within the document's current suite: a line placed from a suite's root (its
  // `method` empty) names the suite by its topic in the plugin's first format, and leaves it
  // empty in its second, where a declaration made lexically within the suite's body is the
  // suite's own; `within` is that suite's topic, from the `suite` line last seen. A line
  // placed from a method's parameter keeps its topic as written, which says only whether the
  // method is generic in it, and is reached through `call` lines by the method's name.
  private def parse(file: Text, within: Text, line: Text): List[Line] =
    def topicOf(topic: Text, method: Text): Text =
      val stated: Text = name(topic).text
      if stated == t"" && method == t"" then within else stated

    line.cut(t"\t") match
      case t"suite" :: className :: topic :: title :: _ :: _ =>
        List(Line.Suite(name(className).text, name(topic).text, name(title).text))

      case t"group" :: topic :: method :: groups :: group :: moniker :: _ =>
        List
         ( Line.Group
            ( topicOf(topic, method), name(method).text, path(groups), name(group), optional(moniker) ) )

      case t"test" :: topic :: method :: groups :: kind :: test :: moniker :: tags :: number :: rest =>
        List
         ( Line.Declaration
            ( topicOf(topic, method),
              name(method).text,
              path(groups),
              kind,
              name(test),
              optional(moniker),
              tags.cut(t",").filter(_ != t""),
              file,
              safely(number.as[Int]).or(0),
              rest.prim.or(t"") == t"spread" ) )

      case t"nest" :: topic :: method :: groups :: nested :: _ =>
        List(Line.Nest(topicOf(topic, method), name(method).text, path(groups), name(nested).text))

      case t"call" :: topic :: method :: groups :: callee :: _ =>
        List(Line.Call(topicOf(topic, method), name(method).text, path(groups), name(callee).text))

      case t"open" :: topic :: method :: groups :: _ =>
        List(Line.Open(topicOf(topic, method), name(method).text, path(groups)))

      case _ =>
        Nil

  // The lines of one index file; its `# source:` header names the file its tests are in. The
  // lines come in the order the plugin traversed the source, each suite's `suite` line before
  // the lines within its body, so the suite a line is within is the last one declared.
  def parse(document: Text): Index =
    val lines: List[Text] = document.cut(t"\n")

    val file: Text =
      lines.seek(_.starts(t"# source: ")).lay(t"") { header => header.skip(10).trim }

    def recur(todo: List[Text], within: Text, done: List[Line]): List[Line] = todo match
      case line :: tail =>
        if line == t"" || line.starts(t"#") then recur(tail, within, done) else
          val parsed: List[Line] = parse(file, within, line)

          val within2: Text = parsed match
            case Line.Suite(_, topic, _) :: _ => topic
            case _                            => within

          recur(tail, within2, parsed.reverse + done)

      case _ =>
        done.reverse

    Index(recur(lines, t"", Nil))

  // The index files of one classpath element, in name order. A jar is read as a zip — which,
  // like the classloader `Suites` reads the suite index through, tolerates the launcher
  // preamble an assembly may carry — and a directory from the filesystem.
  private def documents(entry: Classpath.Entry): List[Text] =
    import sortingAlgorithms.timsort

    val found: List[(Text, Text)] = entry match
      case Classpath.Entry.Jar(path) =>
        safely:
          val zip: java.util.zip.ZipFile = java.util.zip.ZipFile(path.s)

          try
            val entries = zip.entries().nn

            def recur(done: List[(Text, Text)]): List[(Text, Text)] =
              if !entries.hasMoreElements then done else
                val entry = entries.nextElement().nn
                val entryName: String = entry.getName.nn

                if entry.isDirectory || !entryName.startsWith(prefix) then recur(done) else
                  val content = String(zip.getInputStream(entry).nn.readAllBytes(), "UTF-8").tt
                  recur((entryName.tt, content) :: done)

            recur(Nil)
          finally zip.close()

        . or(Nil)

      case Classpath.Entry.Directory(path) =>
        safely:
          val directory = java.io.File(path.s, prefix)

          def read(file: java.io.File): (Text, Text) =
            ( file.getName.nn.tt,
              String(java.nio.file.Files.readAllBytes(file.toPath), "UTF-8").tt )

          Optional(directory.listFiles).lay(Nil: List[(Text, Text)]): files =>
            val listed: List[java.io.File] = files.nn.iterator.map(_.nn).filter(_.isFile).to(List)
            listed.map(read)

        . or(Nil)

      case _ =>
        Nil

    found.order { (pair: (Text, Text)) => pair(0).s }.map(_(1))

  // The classpath's index: every element's files as one. Empty if nothing on it has one.
  def read(classpath: LocalClasspath): Index =
    Index:
      classpath.entries.bind[List[Line], Line, List[Line]]: entry =>
        documents(entry).bind[List[Line], Line, List[Line]] { document => parse(document).lines }

  private def hash(name: Text): Int = name.s.hashCode

  // `probably.Test.Id#id`: the low 24 bits of the hash, in hex, where `above` is the sum of
  // the hashes of the names of everything the entry is within.
  private def id(above: Int, name: Text): Text =
    String.format("%06x", Int.box((above ^ hash(name)) & 0xffffff)).nn.tt

  // A term which is not a selection: a Probably setting such as `--workers=1`, or nothing.
  private def setting(term: Text): Boolean = term == t"" || term.starts(t"--")

  // A term which narrows a selection rather than identifying tests.
  private def narrowing(term: Text): Boolean =
    term.starts(t"kind:") || term.starts(t"tag:") || term.starts(t"not:")
    || Suggest.axisOf(term).present

  // Whether the terms select nothing in particular: every test is admitted.
  def trivial(terms: List[Text]): Boolean = terms.all(setting)

  private def hex(term: Text): Boolean =
    term.length == 6 && term.s.forall { char => char.isDigit || (char >= 'a' && char <= 'f') }

  private def identifier(term: Text): Boolean =
    term.length > 0 && Character.isJavaIdentifierStart(term.s.charAt(0))
    && term.s.forall(Character.isJavaIdentifierPart(_))

  // A selection's terms sorted by what they do: the identities union; the kinds, and each
  // `tag:` term's alternatives, intersect; the exclusions subtract.
  private case class Terms
     ( identities: List[Text], kinds: List[Text], tags: List[List[Text]], exclusions: List[Text] )

  private def sort(terms: List[Text]): Terms =
    def kind(term: Text): Text = if term.skip(5) == t"test" then t"check" else term.skip(5)
    val selections: List[Text] = terms.filter(!setting(_))

    Terms
     ( selections.filter(!narrowing(_)),
       selections.filter(_.starts(t"kind:")).map(kind),
       selections.filter(_.starts(t"tag:")).map { term => term.skip(4).cut(t",").filter(_ != t"") },
       selections.filter(_.starts(t"not:")).map(_.skip(4)).filter { term => !setting(term) } )

  // Whether a selection's terms admit the test, by Probably's own rules (`Selection#admits`)
  // for everything the index can know: identity terms (an id, a moniker, or a glob over a
  // name, the path of names, or the path of monikers) union; `kind:` and `tag:` terms
  // intersect with them; `not:` terms subtract. An axis constraint admits every test, since a
  // test's cells are not in the index, and the terms that are not selections are ignored.
  def admits(test: Test, terms: List[Text]): Boolean =
    def identified(term: Text): Boolean =
      if identifier(term) || hex(term)
      then test.links.exists { link => link.id == term || link.moniker == term }
      else safely(Glob.parse(term)).lay(false): glob =>
        // A suite's id may hold a `-`, which makes it a glob rather than an identifier.
        test.links.exists { link => glob.matches(link.name) }
        || test.links.prim.let(_.moniker).lay(false)(glob.matches(_))
        || glob.matches(test.links.map(_.name).join(t"/"))
        || glob.matches(test.path.join(t"/"))

    val sorted: Terms = sort(terms)

    (sorted.identities.nil || sorted.identities.exists(identified))
    && (sorted.kinds.nil || sorted.kinds.has(test.kind))
    && sorted.tags.all { alternatives => alternatives.nil || alternatives.exists(test.tags.has(_)) }
    && !sorted.exclusions.exists { term => Suggest.axisOf(term).absent && admits(test, List(term)) }

  // A hole in a name only known at runtime, standing in for any text, in the strings below.
  private val hole: Text = t"\u0000"

  // A dynamic link's name, with each of its `*`s a hole. (A `*` written in the name is taken
  // for a hole too: the index shows both the same way, and a hole can only widen a match.)
  private def holed(link: Link): Text =
    if link.dynamic then link.name.s.replace("*", hole.s).nn.tt else link.name

  // The text a glob must begin with: its characters up to the first wildcard, unescaped.
  private def literalPrefix(glob: Text): Text =
    val source: String = glob.s
    val builder: StringBuilder = StringBuilder()

    def recur(index: Int): Text =
      if index >= source.length then builder.toString.tt
      else source.charAt(index) match
        case '\\' if index + 1 < source.length =>
          builder.append(source.charAt(index + 1))
          recur(index + 2)

        case '*' | '?' | '[' =>
          builder.toString.tt

        case char =>
          builder.append(char)
          recur(index + 1)

    recur(0)

  // The text a glob must end with: its characters after the last character which could be
  // part of a wildcard or an escape — short of the truth where an escape precedes them, which
  // only makes the test below more permissive.
  private def literalSuffix(glob: Text): Text =
    val source: String = glob.s

    def recur(index: Int): Text =
      if index < 0 then glob
      else source.charAt(index) match
        case '*' | '?' | '[' | ']' | '\\' => source.substring(index + 1).nn.tt
        case _                            => recur(index - 1)

    recur(source.length - 1)

  // Whether the glob could match a string with holes: exactly, if there are none; otherwise
  // by what is fixed at each end — the glob's literal prefix and the text before the first
  // hole must each begin the other, and likewise the suffix and the text after the last hole —
  // which any match satisfies, so a failure here is a certain mismatch.
  private def compatible(glob: Text, text: Text): Boolean =
    val parts: List[Text] = text.cut(hole)

    if parts.size == 1 then safely(Glob.parse(glob)).lay(false)(_.matches(text))
    else safely(Glob.parse(glob)).present && {
      val first: Text = parts.prim.or(t"")
      val last: Text = parts.last.or(t"")
      val before: Text = literalPrefix(glob)
      val after: Text = literalSuffix(glob)

      (first.starts(before) || before.starts(first))
      && (last.ends(after) || after.ends(last))
    }

  // Whether an identity term could match a dynamic test once its holes are filled: a hex
  // term, if any link's id is unknown; an identifier, by the monikers, which are fixed; and a
  // glob, by what is fixed around the holes in each string Probably matches it against.
  private def possible(test: Test, term: Text): Boolean =
    if identifier(term) || hex(term) then
      test.links.exists: link =>
        link.id == term || link.moniker == term || (hex(term) && link.id == t"")
    else
      test.links.exists { link => compatible(term, holed(link)) }
      || test.links.prim.let(_.moniker).lay(false) { moniker => compatible(term, moniker) }
      || compatible(term, test.links.map(holed).join(t"/"))
      || compatible(term, test.links.map { link => link.moniker.or(holed(link)) }.join(t"/"))

  // Whether an exclusion certainly applies to a dynamic test: a kind or tag, which is fixed,
  // or an identity term matching a link above its first hole, by id, moniker or name — never
  // a glob over the whole path, whose runtime part is unknown.
  private def certain(test: Test, term: Text): Boolean =
    if narrowing(term) then admits(test, List(term)) else
      val fixed: List[Link] = test.links.filter(_.id != t"")

      if identifier(term) || hex(term)
      then fixed.exists { link => link.id == term || link.moniker == term }
      else safely(Glob.parse(term)).lay(false): glob =>
        fixed.exists { link => glob.matches(link.name) }
        || fixed.prim.let(_.moniker).lay(false)(glob.matches(_))

  // Whether the terms COULD admit the test: `admits`, except that a dynamic test — whose id
  // and full name exist only at runtime — is admitted by any identity term its fixed parts do
  // not rule out, and excluded only by one certain to apply. This is what decides whether a
  // suite must run.
  private def could(test: Test, terms: List[Text]): Boolean =
    if !test.dynamic then admits(test, terms) else
      val sorted: Terms = sort(terms)

      (sorted.identities.nil || sorted.identities.exists(possible(test, _)))
      && (sorted.kinds.nil || sorted.kinds.has(test.kind))
      && sorted.tags.all { alternatives => alternatives.nil || alternatives.exists(test.tags.has(_)) }
      && !sorted.exclusions.exists { term => Suggest.axisOf(term).absent && certain(test, term) }

final class Index private[fume] (private[fume] val lines: List[Index.Line]):
  import Index.{Line, Link, Name, Test}

  // Each suite class's id, and each id's title: the name its tests' ids are computed from.
  private val topics: Map[Text, Text] =
    lines.bind[List[(Text, Text)], (Text, Text), List[(Text, Text)]]:
      case Line.Suite(className, topic, _) => List(className -> topic)
      case _                               => Nil

    . to[Map]

  private val titles: Map[Text, Text] =
    lines.bind[List[(Text, Text)], (Text, Text), List[(Text, Text)]]:
      case Line.Suite(_, topic, title) => List(topic -> title)
      case _                           => Nil

    . to[Map]

  // A group's moniker, by where it is declared and its name.
  private def groupKey(topic: Text, method: Text, path: List[Name], name: Text): Text =
    val scope: Text = if method == t"" then topic else t""
    t"$method\t$scope\t${path.map(_.text).join(t"\t")}\t$name"

  private val monikers: Map[Text, Text] =
    lines.bind[List[(Text, Text)], (Text, Text), List[(Text, Text)]]:
      case Line.Group(topic, method, path, name, moniker) =>
        moniker.lay(Nil: List[(Text, Text)]): moniker =>
          List(groupKey(topic, method, path, name.text) -> moniker)

      case _ =>
        Nil

    . to[Map]

  // The lines declared from each root: a suite's own `Testable`, by its topic, or a method's
  // parameter, by the method. Grouped once, so that resolving a suite reads only its own lines
  // and those of the methods it calls.
  private def rootKey(topic: Text, method: Text): Text = if method == t"" then t"=$topic" else method

  private def root(line: Line): Optional[Text] = line match
    case Line.Declaration(topic, method, _, _, _, _, _, _, _, _) => rootKey(topic, method)
    case Line.Call(topic, method, _, _)                          => rootKey(topic, method)
    case Line.Nest(topic, method, _, _)                          => rootKey(topic, method)
    case Line.Open(topic, method, _)                             => rootKey(topic, method)
    case _                                                       => Unset

  private val roots: scala.collection.immutable.Map[Text, List[Line]] =
    val grouped: scala.collection.mutable.LinkedHashMap[Text, List[Line]] =
      scala.collection.mutable.LinkedHashMap()

    lines.each: line =>
      root(line).let { key => grouped(key) = line :: grouped.getOrElse(key, Nil) }

    grouped.view.mapValues(_.reverse).toMap

  def empty: Boolean = lines.nil

  // Whether the index covers the suite: its class is one the plugin saw declared.
  def knows(suite: Text): Boolean = topics.defines(suite)

  // A position in the hierarchy as the resolution descends: the links from the suite, newest
  // first, the sum of the hashes of their names, and whether any of the names was a hole.
  private case class Position(links: List[Link], above: Int, dynamic: Boolean):
    def enter(name: Name, moniker: Optional[Text]): Position =
      val dynamic2: Boolean = dynamic || name.dynamic
      val link =
        Link(name.text, moniker, if dynamic2 then t"" else Index.id(above, name.text), name.dynamic)
      Position(link :: links, above + Index.hash(name.text), dynamic2)

  // A suite's own position: named by its title, and known in paths by its id.
  private def root(topic: Text): Position =
    Position(Nil, 0, false).enter(Name(titles(topic).or(topic), false), topic)

  private def descend(position: Position, topic: Text, method: Text, path: List[Name]): Position =
    def recur(position: Position, done: List[Name], todo: List[Name]): Position = todo match
      case head :: tail =>
        val moniker: Optional[Text] = monikers(groupKey(topic, method, done.reverse, head.text))
        recur(position.enter(head, moniker), head :: done, tail)

      case _ =>
        position

    recur(position, Nil, path)

  // What resolving a root finds: a test, or a place whose tests the index cannot list — an
  // `impromptu` block, or the invocation of a suite the plugin wrote no `suite` line for.
  private enum Found:
    case Declared(test: Test)
    case Hole

  // What is declared from one root — a suite's own `Testable` (`method` empty) or a method's
  // parameter — placed at `position`: its tests, those of the methods it calls with the
  // `Testable` it has, and those of the suites it invokes, which report under their own names.
  // `depth` bounds a method which calls itself. A suite invoked from the body which is among
  // `entries` is not followed: it is an entry point of its own, to be run — or not — on its
  // own account.
  private def expand(topic: Text, method: Text, position: Position, depth: Int, entries: List[Text])
  :   List[Found] =

    roots.getOrElse(rootKey(topic, method), Nil).bind[List[Found], Found, List[Found]]:
      case Line.Declaration(topic2, method2, path, kind, name, moniker, tags, file, line, spread) =>
        val leaf: Position = descend(position, topic2, method2, path).enter(name, moniker)
        List(Found.Declared(Test(leaf.links.reverse, kind, tags, spread, leaf.dynamic, file, line)))

      case Line.Call(topic2, method2, path, callee) if depth < 8 =>
        expand(topic2, callee, descend(position, topic2, method2, path), depth + 1, entries)

      case Line.Nest(_, _, _, nested) if depth < 8 =>
        if entries.has(nested) then Nil
        else topics(nested).lay(List(Found.Hole)): topic =>
          expand(topic, t"", root(topic), depth + 1, entries)

      case Line.Open(_, _, _) =>
        List(Found.Hole)

      case _ =>
        Nil

  private def found(suite: Text, entries: List[Text]): List[Found] =
    topics(suite).lay(Nil: List[Found]) { topic => expand(topic, t"", root(topic), 0, entries) }

  // Every test the suite declares, in the order of its source files and of the lines in them.
  def tests(suite: Text): List[Test] =
    found(suite, Nil).bind[List[Test], Test, List[Test]]:
      case Found.Declared(test) => List(test)
      case _                    => Nil

  // How many places in the suite declare tests the index cannot list: its `impromptu` blocks,
  // and its invocations of suites the index does not cover.
  def open(suite: Text): Int = found(suite, Nil).count(_ == Found.Hole)

  // Which of the classpath's entry suites must run for the terms to reach every test they
  // admit: a suite the index does not cover; one whose tests, or those of the methods it calls
  // or the suites it invokes which are not entry points themselves, the terms could admit; and
  // one with a place the index cannot list, which may declare anything. Trivial terms reach
  // every suite, so a run with no selection is the run it always was.
  def entries(suites: List[Text], terms: List[Text]): List[Text] =
    if Index.trivial(terms) then suites else
      suites.filter: suite =>
        !knows(suite) || found(suite, suites).exists:
          case Found.Hole           => true
          case Found.Declared(test) => Index.could(test, terms)
