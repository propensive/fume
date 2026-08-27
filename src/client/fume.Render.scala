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

import scala.collection.immutable as sci

import soundness.*

import probably.TestEvent

import fume.Figures.measurable
import columnAttenuation.ignoreAttenuation
import decimalConverters.javaDecimalConverter

// Fume's terminal renderer: the consumer-side replacement for probably's `AnsiRenderer` and
// `TerseRenderer`, drawing the `Doc.Document` that `Documenting` derives from the event
// stream. Colour output renders EVERY table at the full terminal width (the `Stretch` column
// absorbs the spare space); terse output — for CI logs and machine-adjacent environments —
// uses word statuses and minimal rules. The PASS/FAIL banner renders once, at the end of the
// whole run, over the aggregate totals of every suite.
object Render:
  import Doc.{Block, Column, Datum, Document, Group, Spark, Status, SummaryRow, Totals}

  private given decimalizer: Decimalizer = Decimalizer(4)

  // The column which absorbs a table's spare width: elastic from its natural content width
  // with NO maximum, so `Flex.solve` hands it everything the rigid columns leave and the
  // table spans the full line. Exactly one column of each fume table uses it.
  private[fume] object Stretch extends Columnar:
    def flex[text: Textual { type Result = Char }](lines: Array[text]^{}, maxWidth: Int)
       (using Text is Measurable)
    :   Flex =

      var metrics = Metrics(0, 0)
      lines.each { line => metrics = metrics.max(Flow.metrics(line)) }
      Flex(metrics, 1.0, Unset)

    def fit[text: Textual { type Result = Char }]
       (lines: Array[text]^{}, width: Int, textAlign: TextAlignment)
       (using Text is Measurable, Hyphenation)
    :   Sequence[text] =

      Sequence.from:
        lines.readable.to(sci.IndexedSeq).flatMap { line => Flow.wrap(line, width).stdlib }.toVector

  // A rigid column: exactly its natural content width, never shrunk when the table is
  // squeezed — numeric figures (a duration and its unit) must not wrap.
  private[fume] object Rigid extends Columnar:
    def flex[text: Textual { type Result = Char }](lines: Array[text]^{}, maxWidth: Int)
       (using Text is Measurable)
    :   Flex =

      var metrics = Metrics(0, 0)
      lines.each { line => metrics = metrics.max(Flow.metrics(line)) }
      Flex(Metrics(metrics.natural, metrics.natural), 0.0, metrics.natural)

    def fit[text: Textual { type Result = Char }]
       (lines: Array[text]^{}, width: Int, textAlign: TextAlignment)
       (using Text is Measurable, Hyphenation)
    :   Sequence[text] =

      Sequence.from:
        lines.readable.to(sci.IndexedSeq).flatMap { line => Flow.wrap(line, width).stdlib }.toVector

  // ---------------------------------------------------------------- data rendering

  private def figure(figure: Figures.Figure, units: List[Text], terse: Boolean): Teletype =
    if figure.unit >= 3 then figure.whole.teletype
    else if terse then e"${figure.whole}.${figure.fraction} ${units.stdlib(figure.unit)}"
    else
      val color = figure.unit match
        case 0 => Palette.cold
        case 1 => Palette.warm
        case _ => Palette.hot

      val unit = units.stdlib(figure.unit)
      e"${Fg(Palette.foreground)}(${figure.whole}.${figure.fraction}) ${Fg(color)}($unit)"

  private def time(n: Long, terse: Boolean): Teletype =
    figure(Figures.scaled(n), Figures.timeUnits, terse)

  private def memory(n: Long, terse: Boolean): Teletype =
    figure(Figures.scaled(n), Figures.memoryUnits, terse)

  private def datum(value: Datum, terse: Boolean): Teletype = value match
    case Datum.Blank              => e""
    case Datum.Gap                => if terse then e"–" else e"${Fg(Palette.subdued)}(–)"
    case Datum.Str(text)          => e"$text"
    case Datum.Title(name, depth) => e"${t"  "*depth}$name"
    case Datum.Num(number)        => number.show.teletype
    case Datum.Time(nanos)        => time(nanos, terse)
    case Datum.Memory(bytes)      => memory(bytes, terse)

    case Datum.Hash(id) =>
      if terse then e"$id" else e"${Fg(Palette.informative)}($id)"

    case Datum.Mark(status) =>
      if terse then e"${status.word}" else status.symbol

    case Datum.Rate(perSecond) =>
      if terse then e"$perSecond op/s" else
        val units = e"${Fg(Palette.accented)}(op${Fg(Palette.subdued)}(·)s¯¹)"
        e"${Fg(Palette.foreground)}($perSecond) $units"

    case Datum.Percent(fraction) =>
      if terse then e"${(fraction*100).show}%"
      else e"${Fg(Palette.foreground)}(${(fraction*100).show})%"

    case Datum.Conf(percentile, basisPoints) =>
      if terse then e"P$percentile ±${Figures.percent(basisPoints)}%" else
        val interval = e"${Fg(Palette.foreground)}(${Figures.percent(basisPoints)})"
        e"P$percentile ${Fg(Palette.accented)}(±)$interval%"

    case Datum.Ratio(factor) =>
      if factor == 1.0 then e"★"
      else if terse then e"${factor.show}"
      else e"${Fg(Palette.foreground)}(${factor.show})"

    case Datum.Delta(inner, negative) =>
      val sign =
        if terse then (if negative then e"-" else e"+")
        else if negative then e"${Fg(Palette.accented)}(-)"
        else e"${Fg(Palette.accented)}(+)"

      e"$sign${datum(inner, terse)}"

  // ---------------------------------------------------------------- tables

  private def gridLines(tabulation: Tabulation[Teletype], width: Int, terse: Boolean)
  :   Chain[Teletype] =

    if terse then
      given style: TableStyle = tableStyles.minimalTableStyle
      tabulation.grid(width).render
    else
      given style: TableStyle = tableStyles.defaultTableStyle
      tabulation.grid(width).render

  private def printTable
     ( columns: List[Column],
       rows: List[List[Datum]],
       width: Int,
       terse: Boolean )
     (using Stdio)
  :   Unit =

    val defs: List[escritoire.Column[List[Datum], Teletype]] =
      columns.indexed.map: (column, index) =>
        val align = if column.numeric then TextAlignment.Right else TextAlignment.Left
        val sizing: Columnar =
          if column.stretch && !terse then Stretch
          else if column.numeric then Rigid
          else columnar.Paragraph
        val title: Teletype = if terse then column.title.teletype else e"$Bold(${column.title})"

        escritoire.Column[List[Datum], Teletype, Teletype](title, align, sizing = sizing):
          row => datum(row.stdlib(index.n0), terse)

    gridLines(Scaffold[List[Datum]](defs*).tabulate(rows), width, terse).each(Out.println(_))

  // ---------------------------------------------------------------- the results table

  private def resultsTable(document: Document, width: Int, terse: Boolean)(using Stdio): Unit =
    val results = document.results
    if !results.nil then
      if !terse then Out.println(e"$Bold($Underline(Test results))")

      val showStats = !results.all(_.count < 2)
      val timeTitle = if showStats then t"Avg" else t"Time"

      def title(row: SummaryRow): Teletype =
        val depth = Documenting.depth(row.ref)
        val name = row.ref.name

        if row.status == Status.Suite then
          if terse then e"${t"  "*depth}$name" else e"${t"  "*depth}${Fg(Palette.foreground)}($Bold($name))"
        else e"${t"  "*depth}$name"

      val mark: escritoire.Column[SummaryRow, Teletype] =
        escritoire.Column[SummaryRow, Teletype, Teletype](e""): row =>
          if terse then e"${if row.status == Status.Suite then t"" else row.status.word}"
          else row.status.symbol

      val defs: List[escritoire.Column[SummaryRow, Teletype]] =
        List
          ( mark,
            escritoire.Column[SummaryRow, Teletype, Teletype](if terse then e"Hash" else e"$Bold(Hash)"):
              row => if terse then e"${row.ref.id}" else e"${Fg(Palette.informative)}(${row.ref.id})",

            escritoire.Column[SummaryRow, Teletype, Teletype]
               (if terse then e"Test" else e"$Bold(Test)",
                sizing = if terse then columnar.Paragraph else Stretch)(title),

            escritoire.Column[SummaryRow, Teletype, Teletype]
               (if terse then e"Count" else e"$Bold(Count)", TextAlignment.Right,
                sizing = Rigid): row =>
              if row.count == 0 then e""
              else if terse then e"${row.count}"
              else e"${Fg(Palette.informative)}(${row.count})",

            escritoire.Column[SummaryRow, Teletype, Teletype]
               (if terse then e"Min" else e"$Bold(Min)", TextAlignment.Right,
                sizing = Rigid): row =>
              if row.count < 2 || row.min == 0L then e"" else time(row.min, terse),

            escritoire.Column[SummaryRow, Teletype, Teletype]
               (if terse then e"$timeTitle" else e"$Bold($timeTitle)", TextAlignment.Right,
                sizing = Rigid): row =>
              if row.avg == 0L then e"" else time(row.avg, terse),

            escritoire.Column[SummaryRow, Teletype, Teletype]
               (if terse then e"Max" else e"$Bold(Max)", TextAlignment.Right,
                sizing = Rigid): row =>
              if row.count < 2 || row.max == 0L then e"" else time(row.max, terse) )

      gridLines(Scaffold[SummaryRow](defs*).tabulate(results), width, terse)
      . each(Out.println(_))

  // ---------------------------------------------------------------- groups

  private def kindTitle(kind: Text): Text = kind match
    case t"bench"   => t"Benchmarks"
    case t"stress"  => t"Stress"
    case t"profile" => t"Profile"
    case _          => t"Tests"

  private def renderGroup(group: Group, width: Int, terse: Boolean)(using Stdio): Unit =
    if terse then
      Out.println(t"")
      val suiteName = group.suite.let(_.name).or(t"")
      Out.println(t"${kindTitle(group.kind)}: $suiteName")
    else
      val ribbon =
        Ribbon
          ( Bg(Palette.subdue(Palette.detail, 0.3)),
            Bg(Palette.subdue(Palette.detail, 0.6)),
            Bg(Palette.subdue(Palette.detail, 0.9)) )

      Out.println:
        ribbon.fill
          ( e"${group.suite.let(_.id).or(t"")}",
            kindTitle(group.kind).teletype,
            group.suite.let(_.name.teletype).or(e"") )

    group.blocks.each(renderBlock(_, width, terse))

  private def renderBlock(block: Block, width: Int, terse: Boolean)(using Stdio): Unit =
    block match
      case Block.Table(title, columns, rows) =>
        title.let: ref =>
          if terse then Out.println(t"${ref.id}  ${ref.name}")
          else Out.println(e"$Bold(${Fg(Palette.informative)}(${ref.id})) $Bold(${ref.name})")

        printTable(columns, rows, width, terse)

      case Block.Sparkline(steps, sequence) =>
        val labelWidth = sequence.map(_.label.length).stdlib.foldLeft(1)(_.max(_))
        val stepWidth = steps.map(_.show.length).stdlib.foldLeft(1)(_.max(_)) + 2

        Out.println:
          val headings: Text = steps.map { (step: Long) => step.show.pad(stepWidth, Rtl) }.join
          if terse then e"  ${t"N".pad(labelWidth)}$headings"
          else e"  ${Fg(Palette.subdued)}(${t"N".pad(labelWidth)}$headings)"

        sequence.indexed.each: (spark: Doc.Spark, index) =>
          val cells: Teletype =
            spark.cells.map: (cell: Optional[(Int, Boolean)]) =>
              cell.lay(if terse then t"·".pad(stepWidth, Rtl).teletype
                       else e"${Fg(Palette.subdued)}(${t"·".pad(stepWidth, Rtl)})"):
                (level: Int, subduedCell: Boolean) =>
                  val text = Figures.sparkBlocks.stdlib(level - 1).pad(stepWidth, Rtl)
                  if terse then text.teletype
                  else if subduedCell then e"${Fg(Palette.subdued)}($text)"
                  else e"${Fg(Palette.accented)}($text)"

            . stdlib.foldLeft(e"")(_ + _)

          val summary: Teletype = spark.sustained.lay(e""): (n: Long, throughput: Long) =>
            if terse then e"  sustained $n @ $throughput op/s" else
              val rate = e"${Fg(Palette.foreground)}($throughput)"
              val units = e"${Fg(Palette.accented)}(op${Fg(Palette.subdued)}(·)s¯¹)"
              e"  sustained $Bold(${Fg(Palette.foreground)}($n)) @ $rate $units"

          val label =
            if terse then spark.label.pad(labelWidth).teletype
            else e"${Fg(Palette.accented)}(${spark.label.pad(labelWidth)})"

          Out.println(e"  $label$cells$summary")

        Out.println(e"")

      case Block.Histogram(title, total, frames) =>
        title.let: ref =>
          if terse then Out.println(t"${ref.id}  ${ref.name}")
          else Out.println(e"$Bold(${Fg(Palette.foreground)}(${ref.name}))")

        val maxSamples: Long = frames.map(_.samples).stdlib.foldLeft(0L)(_.max(_))

        def name(frame: TestEvent.Hotspot): Text = t"${frame.className}#${frame.method}"

        val nameWidth: Int = frames.map(name(_).length).stdlib.foldLeft(0)(_.max(_))

        frames.each: (frame: TestEvent.Hotspot) =>
          val percent = Figures.percent(Figures.basisPoints(frame.samples.toDouble, total.toDouble))
          val bar = Figures.bar(frame.samples, maxSamples)

          if terse
          then Out.println(t"  ${name(frame).pad(nameWidth, Rtl)} ${percent.pad(6, Rtl)}% $bar")
          else
            val share = e"${Fg(Palette.foreground)}(${percent.pad(6, Rtl)}%)"
            Out.println(e"  ${name(frame).pad(nameWidth, Rtl)} $share ${Fg(Palette.accented)}($bar)")

        Out.println(e"")

  // ---------------------------------------------------------------- failures

  private def location(ref: TestEvent.Ref): Text = t"${ref.file}:${ref.line}"

  private def renderTrace(trace: TestEvent.Trace, terse: Boolean, crop: Boolean = false)
     (using Stdio)
  :   Unit =

    trace.components.each: component =>
      // A failure's trace ends where the test framework begins: everything from the first
      // `probably.` frame down is the runner and transport machinery, not the test.
      val frames: List[TestEvent.Frame] =
        if !crop then component.frames
        else (component.frames.stdlib.takeWhile(!_.className.starts(t"probably."))).to(List)

      if terse then
        Out.println(t"  ${component.className}: ${Figures.abbreviate(component.message)}")
        frames.stdlib.take(3).foreach: frame =>
          val line = frame.line.let(_.show).or(t"?")
          Out.println(t"    at ${frame.className}.${frame.method} (${frame.file}:$line)")
      else
        Out.println:
          e"  ${Fg(Palette.fail)}($Bold(${component.className})) ${Figures.abbreviate(component.message)}"

        frames.each: frame =>
          val line = frame.line.let(_.show).or(t"?")
          val at = e"${Fg(Palette.subdued)}(at)"
          val loc = e"${Fg(Palette.informative)}(${frame.file}:$line)"
          Out.println(e"    $at ${frame.className}${Fg(Palette.subdued)}(.)${frame.method} $loc")

  private def renderCompare
     ( expected: Text, found: Text, rows: List[TestEvent.CompareRow],
       width: Int, terse: Boolean )
     (using Stdio)
  :   Unit =

    def indent(text: Text): Text =
      if text.contains(t"\n") then text.trim.cut(t"\n").join(t"\n│ ", t"\n│ ", t"\n") else text

    if terse then
      Out.println(t"  expected: ${Figures.abbreviate(expected.sub(t"\n", t" "))}")
      Out.println(t"  observed: ${Figures.abbreviate(found.sub(t"\n", t" "))}")
    else
      val expected2 = e"$Italic(${Fg(Palette.foreground)}(${indent(expected)}))"
      val observed2 = e"$Italic(${Fg(Palette.foreground)}(${indent(found)}))"
      Out.println(e"${Fg(Palette.foreground)}(Expected:) $expected2")
      Out.println(e"${Fg(Palette.foreground)}(Observed:) $observed2")

    // The flattened `Juxtaposition`: depth-indexed rows re-indented as a tree, differing
    // rows highlighted.
    val interesting = rows.filter(_.kind != t"same")

    if !interesting.nil && rows.stdlib.length > 1 then
      val defs =
        List
          ( escritoire.Column[TestEvent.CompareRow, Teletype, Teletype](e""): row =>
              val label = if row.label == t"" then t"•" else row.label
              e"${t"  "*row.depth}$label",

            escritoire.Column[TestEvent.CompareRow, Teletype, Teletype]
               (if terse then e"Expected" else e"$Bold(Expected)", sizing = if terse then columnar.Paragraph else Stretch):
              row =>
                if terse then e"${row.left}"
                else if row.kind == t"different" then e"${Fg(Palette.pass)}(${row.left})"
                else e"${Fg(Palette.subdued)}(${row.left})",

            escritoire.Column[TestEvent.CompareRow, Teletype, Teletype]
               (if terse then e"Observed" else e"$Bold(Observed)"):
              row =>
                if terse then e"${row.right}"
                else if row.kind == t"different" then e"${Fg(Palette.fail)}(${row.right})"
                else e"${Fg(Palette.subdued)}(${row.right})" )

      gridLines(Scaffold[TestEvent.CompareRow](defs*).tabulate(rows), width, terse)
      . each(Out.println(_))

  private def renderFailures(document: Document, width: Int, terse: Boolean, github: Boolean)
     (using Stdio)
  :   Unit =

    // Every FAILED test gets a section — header and location always, details when the run
    // recorded any (a plain `assert` records none; `check`s and throws carry them).
    val failed: List[(TestEvent.Ref, List[TestEvent])] =
      document.results.bind[List[(TestEvent.Ref, List[TestEvent])],
          (TestEvent.Ref, List[TestEvent]), List[(TestEvent.Ref, List[TestEvent])]]:
        row =>
          if !row.status.failed then Nil else
            List(row.ref -> document.failures.seek(_(0).id == row.ref.id).lay(Nil)(_(1)))

    failed.each: (ref, details) =>
      if github then GithubActions.group(t"Failure: ${ref.name} (${ref.id})")

      if terse then Out.println(t"${ref.id}  ${ref.name} @ ${location(ref)}")
      else
        val ribbon =
          Ribbon
            ( Bg(Palette.fail),
              Bg(Palette.subdue(Palette.fail, 0.5)),
              Bg(Palette.subdue(Palette.fail, 0.75)) )

        Out.println(ribbon.fill(e"$Bold(${ref.id})", location(ref).teletype, ref.name.teletype))

      details.each: detail =>
        if !terse then Out.println(t"")

        detail match
          case TestEvent.DetailThrows(_, check, trace) =>
            val explanation =
              if check then t"An exception was thrown while checking the test predicate:"
              else t"An exception was thrown while running test:"

            if terse then Out.println(t"  $explanation")
            else Out.println(e"${Fg(Palette.foreground)}($explanation)")
            renderTrace(trace, terse, crop = true)

          case TestEvent.DetailCompare(_, expected, found, rows) =>
            renderCompare(expected, found, rows, width, terse)

          case TestEvent.DetailCaptures(_, values) =>
            val pairs: List[(Text, Text)] = values.to[List]

            val defs =
              List
                ( escritoire.Column[(Text, Text), Teletype, Teletype]
                     (e"Expression", TextAlignment.Right)(_(0).teletype),
                  escritoire.Column[(Text, Text), Teletype, Teletype](e"Value")(_(1).teletype) )

            gridLines(Scaffold[(Text, Text)](defs*).tabulate(pairs), width, terse)
            . each(Out.println(_))

          case TestEvent.DetailMessage(_, message) =>
            Out.println(if terse then t"  ${Figures.abbreviate(message)}" else message)

          case _ =>
            ()

      Out.println(t"")
      if github then GithubActions.endGroup()

  private def renderFatal(document: Document, terse: Boolean, github: Boolean)
     (using Stdio, Environment)
  :   Unit =

    document.fatal.let: (trace, active) =>
      val activeNames = active.map(_.name).join(t", ")

      val explanation =
        if active.nil then t"No tests were active when a fatal error occurred."
        else t"A fatal error occurred while $activeNames ${if active.stdlib.length == 1 then t"was" else t"were"} running."

      if github then
        val className = trace.components.prim.let(_.className).or(t"")
        val message = trace.components.prim.let(_.message).or(t"")

        GithubActions.error
          ( message = Figures.abbreviate(t"Fatal error: $className: $message"),
            title = t"Fatal error" )

        GithubActions.group(t"Fatal error stack trace")

      Out.println(t"")

      if terse then Out.println(t"FATAL: $explanation")
      else
        val ribbon = Ribbon(Bg(Palette.fail), Bg(Palette.subdue(Palette.fail, 0.5)))
        Out.println(ribbon.fill(e"$Bold(FATAL)", explanation.teletype))

      renderTrace(trace, terse)
      if github then GithubActions.endGroup()

  // ---------------------------------------------------------------- annotations

  private def describeFailure(details: List[TestEvent]): Text =
    details.prim.lay(t"Test failed"):
      case TestEvent.DetailThrows(_, check, trace) =>
        val className = trace.components.prim.let(_.className).or(t"")
        val message = trace.components.prim.let(_.message).or(t"")
        if check then Figures.abbreviate(t"Check threw $className: $message")
        else Figures.abbreviate(t"Threw $className: $message")

      case TestEvent.DetailCompare(_, expected, found, _) =>
        val found2 = found.sub(t"\n", t" ")
        Figures.abbreviate(t"Expected: ${expected.sub(t"\n", t" ")}; observed: $found2")

      case TestEvent.DetailMessage(_, message) =>
        Figures.abbreviate(message)

      case _ =>
        t"Test failed"

  private def annotations(document: Document)(using Stdio, Environment): Unit =
    document.results.each: row =>
      if row.status.failed then
        val details: List[TestEvent] =
          document.failures.seek(_(0).id == row.ref.id).lay(Nil)(_(1))

        GithubActions.error
          ( message = describeFailure(details),
            file    = row.ref.file,
            line    = row.ref.line,
            title   = row.ref.name )

  // ---------------------------------------------------------------- entry points

  // One suite's report: the results table, the measurement groups, the failures and any
  // fatal error — everything except the banner and aggregate totals, which belong to the
  // whole run.
  def suite(document: Document, width: Int, terse: Boolean)(using Stdio, Environment): Unit =
    val github = GithubActions.enabled

    resultsTable(document, width, terse)
    document.groups.each(renderGroup(_, width, terse))
    if github then annotations(document)
    renderFailures(document, width, terse, github)
    renderFatal(document, terse, github)
    if document.nothingMatched then Out.println(t"No tests matched the selection.")

  // The end of the whole run: aggregate totals, the PASS/FAIL banner, and the status legend.
  def finale(totals: Totals, terse: Boolean)(using Stdio): Unit =
    if totals.total == 0 then Out.println(if terse then t"No tests were run." else t"")
    else if terse then
      val summary = t"${totals.passed} passed, ${totals.failed} failed, "
      val aspires = t"${totals.aspirePassed} aspire-passed, ${totals.aspireFailed} aspire-failed"
      Out.println(t"$summary$aspires, ${totals.total} total")
    else
      val pass = totals.pass
      val color = Bg(if pass then Palette.pass else Palette.fail)

      val text1 =
        if !pass then t"┳┳━━━┓ ┏┳━━━┳┓   ┳┳   ┳┳    " else t"┳┳━━━┳┓  ┏┳━━━┳┓  ┏┳━━━┓  ┏┳━━━┓"

      val text2 =
        if !pass then t"┃┃     ┃┃   ┃┃   ┃┃   ┃┃    " else t"┃┃   ┃┃  ┃┃   ┃┃  ┃┃      ┃┃    "

      val text3 =
        if !pass then t"┃┣━━   ┃┣━━━┫┃   ┃┃   ┃┃    " else t"┃┣━━━┻┛  ┃┣━━━┫┃  ┗┻━━┳┓  ┗┻━━┳┓"

      val text4 =
        if !pass then t"┃┃     ┃┃   ┃┃   ┃┃   ┃┃    " else t"┃┃       ┃┃   ┃┃      ┃┃      ┃┃"

      val text5 =
        if !pass then t"┻┻     ┻┻   ┻┻   ┻┻   ┻┻━━━┛" else t"┻┻       ┻┻   ┻┻  ┗━━━┻┛  ┗━━━┻┛"

      val width = if pass then 38 else 34
      val fg = Fg(if pass then Palette.pass else Palette.fail)

      Out.println(e"$fg(╭${t"─"*width}╮)")

      def banner(text: Text): Teletype =
        e"$fg(│) $Bold($color(${Fg(Palette.black)}(  $text  ))) $fg(│)"

      Out.println(banner(text1))
      Out.println(banner(text2))
      Out.println(banner(text3))
      Out.println(banner(text4))
      Out.println(banner(text5))
      Out.println(e"$fg(╰${t"─"*width}╯)")

      given decimalizer: Decimalizer = Decimalizer(decimalPlaces = 1)
      val fgText = Fg(Palette.foreground)
      val total = totals.total
      val passText = e"$Bold($fgText(${totals.passed})) passed (${100.0*totals.passed/total}%)"
      val failText = e"$Bold($fgText(${totals.failed})) failed (${100.0*totals.failed/total}%)"

      val aspirePassText =
        e"$Bold($fgText(${totals.aspirePassed})) aspire-passed (${100.0*totals.aspirePassed/total}%)"

      val aspireFailText =
        e"$Bold($fgText(${totals.aspireFailed})) aspire-failed (${100.0*totals.aspireFailed/total}%)"

      val allText = e"$Bold($fgText($total)) total"

      if totals.aspirePassed + totals.aspireFailed == 0
      then Out.println(e" $passText, $failText, $allText")
      else Out.println(e" $passText, $failText, $aspirePassText, $aspireFailText, $allText")

      Out.println(t"─"*72)

      val allStatuses: List[Status] =
        List
          ( Status.Pass, Status.Bench, Status.Stress, Status.Profile, Status.AspirePass,
            Status.Throws, Status.Fail, Status.AspireFail, Status.Mixed, Status.CheckThrows )

      // Printed with index loops rather than `grouped(_).each` closures: a lambda whose
      // parameter is a `List` and whose body uses the `Stdio` capability leaks the list's
      // reach capability into the surrounding scope under capture checking.
      val cells: sci.IndexedSeq[Teletype] =
        allStatuses.stdlib.map: status =>
          (e"  ${status.symbol} ${status.describe}": Teletype).pad(20)
        . toIndexedSeq

      var cell = 0

      while cell < cells.length do
        Out.println(cells.slice(cell, cell + 4).foldLeft(e"")(_ + e" " + _): Teletype)
        cell += 4

      Out.println(t"─"*72)

// GitHub Actions workflow annotations: `::error` lines against the failing test's file and
// line, and log groups around each section — a port of probably's `GithubActions`.
object GithubActions:
  def enabled(using Environment): Boolean =
    safely(Environment.githubActions[Text]) == t"true"

  def terse(using Environment): Boolean =
    safely(Environment.claudecode[Text]).present || enabled

  private def workspaceRelative(path: Text)(using Environment): Text =
    safely(Environment.githubWorkspace[Text]).let: workspace =>
      if path.starts(workspace) && path.length > workspace.length
          && path(workspace.length.z) == '/'
      then path.skip(workspace.length + 1)
      else path
    . or(path)

  private def escape(text: Text): Text =
    text.sub(t"%", t"%25").sub(t"\r", t"%0D").sub(t"\n", t"%0A")

  private def escapeProperty(text: Text): Text =
    escape(text).sub(t",", t"%2C").sub(t":", t"%3A")

  def group(title: Text)(using Stdio): Unit = Out.println(t"::group::${escape(title)}")
  def endGroup()(using Stdio): Unit = Out.println(t"::endgroup::")

  def error
     ( message: Text,
       file:    Optional[Text] = Unset,
       line:    Optional[Int]  = Unset,
       title:   Optional[Text] = Unset )
     (using Stdio, Environment)
  :   Unit =

    val props: List[Text] =
      List
        ( file.let(workspaceRelative(_)).let { path => t"file=${escapeProperty(path)}" }.option,
          line.let { value => t"line=${value.show}" }.option,
          title.let { value => t"title=${escapeProperty(value)}" }.option )
      . bind[List[Text], Text, List[Text]](_.to(List))

    val header = if props.nil then t"" else t" ${props.join(t",")}"
    Out.println(t"::error$header::${escape(message)}")
