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
  // empty for anything whose name, or any name above it, is only known at runtime.
  case class Link(name: Text, moniker: Optional[Text], id: Text)

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

  private def parse(file: Text, line: Text): List[Line] = line.cut(t"\t") match
    case t"suite" :: className :: topic :: title :: _ :: _ =>
      List(Line.Suite(name(className).text, name(topic).text, name(title).text))

    case t"group" :: topic :: method :: groups :: group :: moniker :: _ =>
      List
       ( Line.Group
          ( name(topic).text, name(method).text, path(groups), name(group), optional(moniker) ) )

    case t"test" :: topic :: method :: groups :: kind :: test :: moniker :: tags :: number :: rest =>
      List
       ( Line.Declaration
          ( name(topic).text,
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
      List(Line.Nest(name(topic).text, name(method).text, path(groups), name(nested).text))

    case t"call" :: topic :: method :: groups :: callee :: _ =>
      List(Line.Call(name(topic).text, name(method).text, path(groups), name(callee).text))

    case t"open" :: topic :: method :: groups :: _ =>
      List(Line.Open(name(topic).text, name(method).text, path(groups)))

    case _ =>
      Nil

  // The lines of one index file; its `# source:` header names the file its tests are in.
  def parse(document: Text): Index =
    val lines: List[Text] = document.cut(t"\n")

    val file: Text =
      lines.seek(_.starts(t"# source: ")).lay(t"") { header => header.skip(10).trim }

    Index:
      lines.filter { line => line != t"" && !line.starts(t"#") }.bind[List[Line], Line, List[Line]]:
        line => parse(file, line)

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

  // Whether a selection's terms admit the test, by Probably's own rules (`Selection#admits`)
  // for everything the index can know: identity terms (an id, a moniker, or a glob over a
  // name, the path of names, or the path of monikers) union; `kind:` and `tag:` terms
  // intersect with them; `not:` terms subtract. An axis constraint admits every test, since a
  // test's cells are not in the index, and the terms that are not selections are ignored.
  def admits(test: Test, terms: List[Text]): Boolean =
    def hex(term: Text): Boolean =
      term.length == 6 && term.s.forall { char => char.isDigit || (char >= 'a' && char <= 'f') }

    def identifier(term: Text): Boolean =
      term.length > 0 && Character.isJavaIdentifierStart(term.s.charAt(0))
      && term.s.forall(Character.isJavaIdentifierPart(_))

    def setting(term: Text): Boolean = term == t"" || term.starts(t"--")

    def narrowing(term: Text): Boolean =
      term.starts(t"kind:") || term.starts(t"tag:") || term.starts(t"not:")
      || Suggest.axisOf(term).present

    def identified(term: Text): Boolean =
      if identifier(term) || hex(term)
      then test.links.exists { link => link.id == term || link.moniker == term }
      else safely(Glob.parse(term)).lay(false): glob =>
        // A suite's id may hold a `-`, which makes it a glob rather than an identifier.
        test.links.exists { link => glob.matches(link.name) }
        || test.links.prim.let(_.moniker).lay(false)(glob.matches(_))
        || glob.matches(test.links.map(_.name).join(t"/"))
        || glob.matches(test.path.join(t"/"))

    def kind(term: Text): Text = if term.skip(5) == t"test" then t"check" else term.skip(5)

    val selections: List[Text] = terms.filter(!setting(_))
    val identities: List[Text] = selections.filter(!narrowing(_))
    val kinds: List[Text] = selections.filter(_.starts(t"kind:")).map(kind)

    val tags: List[List[Text]] =
      selections.filter(_.starts(t"tag:")).map { term => term.skip(4).cut(t",").filter(_ != t"") }

    val exclusions: List[Text] =
      selections.filter(_.starts(t"not:")).map(_.skip(4)).filter { term => !setting(term) }

    (identities.nil || identities.exists(identified))
    && (kinds.nil || kinds.has(test.kind))
    && tags.all { alternatives => alternatives.nil || alternatives.exists(test.tags.has(_)) }
    && !exclusions.exists { term => Suggest.axisOf(term).absent && admits(test, List(term)) }

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
      val link = Link(name.text, moniker, if dynamic2 then t"" else Index.id(above, name.text))
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

  // What is declared from one root — a suite's own `Testable` (`method` empty) or a method's
  // parameter — placed at `position`: its tests, those of the methods it calls with the
  // `Testable` it has, and those of the suites it invokes, which report under their own names.
  // `depth` bounds a method which calls itself.
  private def expand(topic: Text, method: Text, position: Position, depth: Int): List[Test] =
    roots.getOrElse(rootKey(topic, method), Nil).bind[List[Test], Test, List[Test]]:
      case Line.Declaration(topic2, method2, path, kind, name, moniker, tags, file, line, spread) =>
        val leaf: Position = descend(position, topic2, method2, path).enter(name, moniker)
        List(Test(leaf.links.reverse, kind, tags, spread, leaf.dynamic, file, line))

      case Line.Call(topic2, method2, path, callee) if depth < 8 =>
        expand(topic2, callee, descend(position, topic2, method2, path), depth + 1)

      case Line.Nest(_, _, _, nested) if depth < 8 =>
        topics(nested).lay(Nil: List[Test]) { topic => expand(topic, t"", root(topic), depth + 1) }

      case _ =>
        Nil

  // Every test the suite declares, in the order of its source files and of the lines in them.
  def tests(suite: Text): List[Test] =
    topics(suite).lay(Nil: List[Test]) { topic => expand(topic, t"", root(topic), 0) }

  // How many places in the suite declare tests the index cannot list: its `impromptu` blocks.
  def open(suite: Text): Int =
    topics(suite).lay(0): topic =>
      lines.count:
        case Line.Open(topic2, _, _) => topic2 == topic
        case _                       => false
