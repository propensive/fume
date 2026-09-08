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

import soundness.*

import probably.TestEvent

import pyrocosm.{Block, Glyph, Inline, Tone}

import Doc.{Column, Datum, Document, Group, Spark, Status, SummaryRow}

// Fume's report as Pyrocosm's semantic blocks: what each datum, table and group *is*, with the
// appearance left to whichever renderer shows it. The static report (`Render`) still draws its
// own teletype for now; the live board is built from these.
object Blocks:
  private def toned(tone: Tone, text: Text): Inline = Inline.Toned(tone, Inline.text(text))
  private def glyph(tone: Tone, glyph: Glyph): Inline = Inline.Toned(tone, List(Inline.Symbol(glyph)))

  def mark(status: Status): List[Inline] = status match
    case Status.Pass        => List(glyph(Tone.Success, Glyph.Check))
    case Status.Fail        => List(glyph(Tone.Failure, Glyph.Cross))
    case Status.Throws      => List(glyph(Tone.Warning, Glyph.Warning))
    case Status.CheckThrows => List(toned(Tone.Failure, t"‼"))
    case Status.Mixed       => List(toned(Tone.Info, t"?"))
    case Status.Suite       => Nil
    case Status.Bench       => List(toned(Tone.Accent, t"*"))
    case Status.Stress      => List(toned(Tone.Accent, t"≈"))
    case Status.Profile     => List(toned(Tone.Accent, t"%"))
    case Status.AspirePass  => List(glyph(Tone.Success, Glyph.ArrowUp))
    case Status.AspireFail  => List(glyph(Tone.Warning, Glyph.ArrowDown))

  def time(nanos: Long): Inline = Inline.Amount(nanos.toDouble/1e9, t"s")
  def memory(bytes: Long): Inline = Inline.Amount(bytes.toDouble, t"B")
  def rate(perSecond: Long): List[Inline] = List(Inline.Figure(perSecond.toDouble, 0), toned(Tone.Accent, t" op/s"))

  def datum(value: Datum): List[Inline] = value match
    case Datum.Blank                 => Nil
    case Datum.Gap                   => List(toned(Tone.Muted, t"–"))
    case Datum.Str(text)             => Inline.text(text)
    case Datum.Title(name, depth)    => Inline.text(t"${t"  "*depth}$name")
    case Datum.Num(number)           => List(Inline.Figure(number.toDouble, 0))
    case Datum.Time(nanos)           => List(time(nanos))
    case Datum.Memory(bytes)         => List(memory(bytes))
    case Datum.Hash(id)              => List(Inline.Reference(id))
    case Datum.Mark(status)          => mark(status)
    case Datum.Rate(perSecond)       => rate(perSecond)
    case Datum.Percent(fraction)     => List(Inline.Figure(fraction*100, 1), Inline.Textual(t"%"))
    case Datum.Conf(percentile, bp)  => Inline.text(t"P$percentile ±${Figures.percent(bp)}%")
    case Datum.Ratio(factor)         => if factor == 1.0 then List(Inline.Symbol(Glyph.Star)) else List(Inline.Figure(factor, 2))
    case Datum.Delta(inner, negative) => toned(Tone.Accent, if negative then t"-" else t"+") :: datum(inner)

  private def column(column: Column): Block.Column =
    val sizing = if column.stretch then Block.Sizing.Stretch else if column.numeric then Block.Sizing.Rigid else Block.Sizing.Paragraph
    val alignment = if column.numeric then Block.Alignment.End else Block.Alignment.Start
    Block.Column(Inline.text(column.title), alignment, sizing, column.numeric)

  private def caption(ref: TestEvent.Ref): List[Inline] =
    List(Inline.Reference(ref.id), Inline.Textual(t" "), Inline.Emphasis(Inline.text(ref.name)))

  def kindTitle(kind: Text): Text = kind match
    case t"bench"   => t"Benchmarks"
    case t"stress"  => t"Stress"
    case t"profile" => t"Profile"
    case _          => t"Tests"

  def block(block: Doc.Block): List[Block] = block match
    case Doc.Block.Table(title, columns, rows, highlight) =>
      val rows0: List[Block.Row] = rows.indexed.map: (cells, index) =>
        Block.Row(cells.map { (cell: Datum) => Block.Cell(datum(cell)) }, if highlight.has(index.n0) then Tone.Success else Unset)

      title.lay(List(Block.Table(columns.map(column), rows0))): ref =>
        List(Block.Paragraph(caption(ref)), Block.Table(columns.map(column), rows0))

    case Doc.Block.Sparkline(steps, sequence) =>
      val series: List[Block.Series] = sequence.map: (spark: Spark) =>
        Block.Series(Inline.text(spark.label), spark.cells.map { (cell: Optional[(Int, Boolean)]) => cell.lay(0.0) { (level, _) => level.toDouble } })

      val legend: Block = Block.Paragraph(List(toned(Tone.Muted, t"N: ${steps.map(_.show).join(t", ")}")))

      val sustained: List[Block] = sequence.bind: (spark: Spark) =>
        spark.sustained.lay(Nil: List[Block]): (n, throughput) =>
          List(Block.Paragraph(List(Inline.Textual(t"${spark.label}: sustained "), Inline.Figure(n.toDouble, 0), Inline.Textual(t" @ ")) + rate(throughput)))

      legend :: Block.Chart(Block.Chart.Kind.Sparkline, series) :: sustained

    case Doc.Block.Histogram(title, total, frames) =>
      val series: List[Block.Series] = frames.map: (frame: TestEvent.Hotspot) =>
        val percent = Figures.percent(Figures.basisPoints(frame.samples.toDouble, total.toDouble))
        Block.Series(List(Inline.Textual(t"${frame.className}#${frame.method}"), toned(Tone.Muted, t" $percent%")), List(frame.samples.toDouble))

      val chart = Block.Chart(Block.Chart.Kind.Histogram, series)
      title.lay(List(chart)) { ref => List(Block.Paragraph(caption(ref)), chart) }

    case Doc.Block.Pending(refs) =>
      List(Block.Listing(false, refs.map { (ref: TestEvent.Ref) =>
        Block.Item(List(Block.Paragraph(List(glyph(Tone.Muted, Glyph.Pending), Inline.Textual(t" "), Inline.Reference(ref.id), Inline.Textual(t" "), toned(Tone.Muted, ref.name))))) }))

  def group(group: Group): Block =
    val suiteName: Text = group.suite.let(_.name).or(t"")

    if group.pending then
      Block.Paragraph(List(glyph(Tone.Muted, Glyph.Pending), toned(Tone.Muted, t" ${kindTitle(group.kind)}: "), Inline.Textual(suiteName)))
    else
      val heading = Block.Heading(3, group.suite.let { ref => List(Inline.Reference(ref.id), Inline.Textual(t" ")) }.or(Nil)
          + List(Inline.Textual(kindTitle(group.kind)), Inline.Textual(t" "), Inline.Emphasis(Inline.text(suiteName))))

      Block.Group(heading :: group.blocks.bind(block))

  // The global results table, one row per test.
  def results(rows: List[SummaryRow]): Optional[Block] =
    if rows.nil then Unset else
      val showStats = !rows.all(_.count < 2)
      val timeTitle = if showStats then t"Avg" else t"Time"

      def title(row: SummaryRow): List[Inline] =
        val text = t"${t"  "*Documenting.depth(row.ref)}${row.ref.name}"
        if row.status == Status.Suite then List(Inline.Emphasis(Inline.text(text))) else Inline.text(text)

      def timing(nanos: Long, shown: Boolean): List[Inline] = if !shown || nanos == 0L then Nil else List(time(nanos))

      Block.Table
        ( List
            ( Block.Column(Nil, sizing = Block.Sizing.Rigid),
              Block.Column(Inline.text(t"Hash"), sizing = Block.Sizing.Rigid),
              Block.Column(Inline.text(t"Test"), sizing = Block.Sizing.Stretch),
              Block.Column(Inline.text(t"Count"), Block.Alignment.End, Block.Sizing.Rigid, true),
              Block.Column(Inline.text(t"Min"), Block.Alignment.End, Block.Sizing.Rigid, true),
              Block.Column(Inline.text(timeTitle), Block.Alignment.End, Block.Sizing.Rigid, true),
              Block.Column(Inline.text(t"Max"), Block.Alignment.End, Block.Sizing.Rigid, true) ),
          rows.map: (row: SummaryRow) =>
            Block.Row(List
              ( Block.Cell(mark(row.status)),
                Block.Cell(List(Inline.Reference(row.ref.id))),
                Block.Cell(title(row)),
                Block.Cell(if row.count == 0 then Nil else List(Inline.Figure(row.count.toDouble, 0))),
                Block.Cell(timing(row.min, row.count >= 2)),
                Block.Cell(timing(row.avg, true)),
                Block.Cell(timing(row.max, row.count >= 2)) )) )

  // The live table of scheduled checks: what is running, what has finished, what waits.
  def live(state: Model.State): Optional[Block] =
    val active: List[Text] = state.active.map(_.id)

    val checks: Boolean =
      state.lines.exists:
        case Model.Line.EntryLine(entry) => entry.kind.or(t"check") == t"check"
        case _                           => false

    if !checks then Unset else
      val rows: List[Block.Row] = state.lines.map:
        case Model.Line.SuiteLine(ref) =>
          val text = t"${t"  "*Documenting.depth(ref)}${ref.name}"
          Block.Row(List(Block.Cell(Nil), Block.Cell(List(Inline.Reference(ref.id))), Block.Cell(List(Inline.Emphasis(Inline.text(text)))), Block.Cell(Nil), Block.Cell(Nil)))

        case Model.Line.EntryLine(entry) =>
          val running = active.has(entry.ref.id)
          val idle = entry.completions.nil && entry.benches.nil && entry.strains.nil && entry.hotspots.absent

          val mark0: List[Inline] =
            if running then List(glyph(Tone.Accent, Glyph.Running))
            else if idle then List(glyph(Tone.Muted, Glyph.Pending))
            else mark(Documenting.entryStatus(entry))

          val count: List[Inline] = if entry.completions.nil then Nil else List(Inline.Figure(entry.completions.stdlib.length.toDouble, 0))

          val timing: List[Inline] = entry.benches.prim.lay(averageTime(entry)) { bench => List(time(bench.mean.toLong)) }
          val text = t"${t"  "*Documenting.depth(entry.ref)}${entry.ref.name}"

          Block.Row(List(Block.Cell(mark0), Block.Cell(List(Inline.Reference(entry.ref.id))), Block.Cell(Inline.text(text)), Block.Cell(count), Block.Cell(timing)),
              if running then Tone.Accent else Unset)

      Block.Table
        ( List
            ( Block.Column(Nil, sizing = Block.Sizing.Rigid),
              Block.Column(Inline.text(t"Hash"), sizing = Block.Sizing.Rigid),
              Block.Column(Inline.text(t"Test"), sizing = Block.Sizing.Stretch),
              Block.Column(Inline.text(t"Count"), Block.Alignment.End, Block.Sizing.Rigid, true),
              Block.Column(Inline.text(t"Time"), Block.Alignment.End, Block.Sizing.Rigid, true) ),
          rows )

  private def averageTime(entry: Model.Entry): List[Inline] =
    val durations: List[Long] = entry.completions.map { completion => completion(1).duration }
    if durations.nil then Nil else List(time(durations.stdlib.foldLeft(0L)(_ + _)/durations.stdlib.length))

  // The whole run's progress: scheduled tests finished, of the total.
  def progress(state: Model.State): Block =
    val entries: List[Model.Entry] = state.lines.bind[List[Model.Entry], Model.Entry, List[Model.Entry]]:
      case Model.Line.EntryLine(entry) => List(entry)
      case _                           => Nil

    val active: List[Text] = state.active.map(_.id)

    val done: Int = entries.stdlib.count: entry =>
      val recorded = !entry.completions.nil || !entry.benches.nil || !entry.strains.nil || entry.hotspots.present
      recorded && !active.has(entry.ref.id)

    val total: Int = entries.stdlib.length
    val fraction: Double = if total == 0 then 0.0 else done.toDouble/total
    Block.Gauge(pyrocosm.Status.Fraction(fraction), Inline.text(t"$done/$total"))

  // The board: the live table of checks, then every measurement group.
  def board(state: Model.State): List[Block] =
    val document: Document = Documenting.document(state)
    live(state).lay(Nil: List[Block])(List(_)) + document.groups.map(group)

  // The finished report's blocks: the results table and the groups.
  def document(document: Document): List[Block] =
    results(document.results).lay(Nil: List[Block])(List(_)) + document.groups.map(group)
