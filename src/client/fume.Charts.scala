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

import pyrocosm.Inline

import fontMetrics.averageFontMetric

// The dashboard's charts: a tasseomancy drawing above each measurement table of a run, kept
// current as its records arrive. Each is a Pyrocosm `Figure`, so it rides the web frontend's
// socket; when new data still fits the axes, only the parts that changed are revised, and the
// axes hold still. Every chart is keyed by the table it stands above (`Charts.key`), which is
// how `Blocks` places it; the terminal, which builds its blocks without figures, never sees
// one.
//
// A benchmark chart is grouped bars, as tasseomancy draws them: a category per bar group and
// a series per bar within the group, coloured by series. A plain group of benchmarks has a
// category per test and one series; a benchmark over one axis, a category per axis value; and
// over two, a category per value of the first axis with a series per value of the second — the
// crosstab's rows and columns — so the i-th bar of every group is the same colour.
object Charts:
  // The dashboard's chart palette: a white ground with black axes and lettering, a faint grid,
  // and a ramp from pale lime to deep blue for the series.
  private def rgb(hex: Int): Color in Srgb =
    Srgb(((hex >> 16) & 0xff)/255.0, ((hex >> 8) & 0xff)/255.0, (hex & 0xff)/255.0)

  given palette: ChartPalette = new ChartPalette:
    val series: Sequence[Color in Srgb] =
      Sequence(rgb(0xcceeaa), rgb(0xa5d08a), rgb(0x74aaa9), rgb(0x568d9f), rgb(0x4d7298))

    def background: Color in Srgb = rgb(0xffffff)
    def foreground: Color in Srgb = rgb(0x000000)
    def axis: Color in Srgb = rgb(0x000000)
    def grid: Color in Srgb = rgb(0xe0e0e0)
    def text: Color in Srgb = rgb(0x000000)

  // The time unit a benchmark chart is drawn in, chosen for the chart's largest mean by the
  // thresholds `Figures.scaled` applies to a figure: µs, then ms past a tenth of a millisecond,
  // then seconds past a tenth of a second.
  case class Timebase(label: Text, nanos: Double)

  def timebase(maxMeanNanos: Double): Timebase =
    if maxMeanNanos > 1e8 then Timebase(t"s", 1e9)
    else if maxMeanNanos > 1e5 then Timebase(t"ms", 1e6)
    else Timebase(t"µs", 1e3)

  // One bar: its category, and the mean with its confidence interval, in the chart's timebase.
  // Plain values, so two snapshots compare by content.
  case class Bar(category: Text, mean: Double, lower: Double, upper: Double)

  // One series of a bar chart: the bars of one colour, one per category at most.
  case class Slice(name: Text, bars: List[Bar])

  case class BarData(slices: List[Slice], base: Timebase):
    def empty: Boolean = slices.all(_.bars.nil)

  // A point of a stress curve: concurrency and the throughput measured there.
  case class Curve(name: Text, points: List[(Int, Double)])

  // The key of the chart above a table: the group's suite and kind, then the table — the
  // group's plain table, an axial entry's own, or the stress group's sparkline.
  def key(suite: Optional[TestEvent.Ref], kind: Text, table: Text): Text =
    t"$kind:${suite.let(_.path.join(t"/")).or(t"")}#$table"

  // The half-width of a benchmark's confidence interval in nanoseconds, from its raw statistics
  // exactly as the report's `Confidence` column recomputes it; zero for a single run.
  def interval(bench: TestEvent.BenchmarkRecorded): Double =
    if bench.runs < 2 then 0.0
    else Figures.tQuantile(bench.confidence, bench.runs - 1)*bench.sd/bench.runs.toDouble.sqrt

  private def bar(category: Text, bench: TestEvent.BenchmarkRecorded, base: Timebase): Bar =
    val ci: Double = interval(bench)
    Bar(category, bench.mean/base.nanos, (bench.mean - ci).max(0.0)/base.nanos, (bench.mean + ci)/base.nanos)

  private def baseOf(benches: List[TestEvent.BenchmarkRecorded]): Timebase =
    timebase(benches.map(_.mean).fold(0.0)(_.max(_)))

  // The plain benchmarks of a group as one series: a bar per test, in declaration order — the
  // order the table lists them. A test yet to record has an empty bar, so the category axis
  // is fixed from the schedule and a result arriving changes only its bar's height.
  def plain(members: List[Model.Entry]): BarData =
    val entries: List[Model.Entry] = members.filter(Documenting.axesOf(_).nil)
    val base: Timebase = baseOf(entries.bind[List[TestEvent.BenchmarkRecorded], TestEvent.BenchmarkRecorded, List[TestEvent.BenchmarkRecorded]](_.benches))

    val bars: List[Bar] =
      entries.map: entry =>
        entry.benches.prim.lay(Bar(entry.ref.name, 0.0, 0.0, 0.0))(bar(entry.ref.name, _, base))

    BarData(List(Slice(t"mean", bars)), base)

  // An axial benchmark's bars: over one axis, a bar per value; over two, a group per value of
  // the first axis holding a bar per value of the second; over more, a bar per coordinate
  // address. Values are in the order the table lists them, the scheduled ones first, and a
  // scheduled cell yet to record is an empty bar, so the bars are all in place from the start
  // and a record only raises its own.
  def axial(entry: Model.Entry): BarData =
    val axes: List[Text] = Documenting.axesOf(entry)

    val base: Timebase = baseOf(entry.benches)

    def benchAt(pairs: List[(Text, Text)]): Optional[TestEvent.BenchmarkRecorded] =
      entry.benches.seek: bench =>
        pairs.all { (axis, value) => bench.coordinates.exists { c => c.axis == axis && Documenting.coordText(c) == value } }

    def values(axis: Text): List[Text] = Documenting.axisValues(entry, axis).map(Documenting.coordText(_))

    axes match
      case axis :: Nil =>
        val bars: List[Bar] =
          values(axis).map { value => benchAt(List((axis, value))).lay(Bar(value, 0.0, 0.0, 0.0))(bar(value, _, base)) }

        BarData(List(Slice(entry.ref.name, bars)), base)

      case first :: second :: Nil =>
        val slices: List[Slice] =
          values(second).map: column =>
            val bars: List[Bar] =
              values(first).map { row => benchAt(List((first, row), (second, column))).lay(Bar(row, 0.0, 0.0, 0.0))(bar(row, _, base)) }

            Slice(column, bars)

        BarData(slices, base)

      case _ =>
        val bars: List[Bar] =
          entry.benches.map { bench => bar(bench.coordinates.map(Documenting.coordText(_)).join(t", "), bench, base) }

        BarData(List(Slice(entry.ref.name, bars)), base)

  // A curve per stress entry that has recorded: throughput against concurrency, each step
  // measured once.
  def curves(members: List[Model.Entry]): List[Curve] =
    members.filter(!_.strains.nil).map: entry =>
      Curve(entry.ref.name, Documenting.curve(entry).map { strain => (strain.concurrency, Documenting.strainThroughput(strain).toDouble) })

  def benchSeries(data: BarData): List[Series[Text, Estimate]] =
    data.slices.map: slice =>
      Series(slice.name, slice.bars.map { bar => (bar.category, Estimate(bar.mean, bar.lower, bar.upper)) }.to[Sequence])

  def stressSeries(curves: List[Curve]): List[Series[Int, Double]] =
    curves.map { curve => Series(curve.name, curve.points.to[Sequence]) }

  // The chart's lettering: Sono, from Google Fonts, at variable width — the face and axis the
  // web front-end sets its labels in — and at the size those labels have on the page, since a
  // drawing is shown at its own size.
  private case class Link(text: Text)
  private given Link is Abstractable across Urls to Text = _.text

  private given (Typeface of "Sono") is Typesettable in Web =
    Web.imported(Link(t"https://fonts.googleapis.com/css2?family=Sono:wght,MONO@200..800,0..1&display=swap"), Coverage.Unknown)

  private val sono: Font in Web =
    unsafely(Web.font(Typeface["Sono"].face.varying(Variation.Axis(t"MONO"), 0.0)))

  private val labelSize: Double = 11.5
  private val height: Double = 260.0

  // Fume's chart style: an ordinate label's baseline sits a little above its tick, and an
  // abscissa label starts a little to the right of its tick, rather than being centred on it.
  final class Style
    ( width:         Double,
      height:        Double,
      legend:        Chart.Legend,
      abscissaTitle: Optional[Text] = Unset,
      ordinateTitle: Optional[Text] = Unset,
      markers:       Boolean        = false,
      barGap:        Double         = leastGap,
      smoothing:     Double         = 0.0 )
  extends Chart.Standard
    ( width = width, height = height, font = sono, fontSize = labelSize, legend = legend,
      abscissaTitle = abscissaTitle, ordinateTitle = ordinateTitle, markers = markers,
      markerRadius = 2.1, smoothing = smoothing, barGap = barGap ):

    override def tickLabel(at: Chart.Anchoring, text: Text, axis: Chart.Axis, color: Color in Srgb)
    :   List[Figure] =

      axis match
        case Chart.Axis.Ordinate =>
          val position = Point(at.point.x, (at.point.y - fontSize*0.15).toFloat)
          List(lettering(position, text, at.anchor, Lettering.Baseline.Alphabetic, color))

        case Chart.Axis.Abscissa =>
          val position = Point((at.point.x + fontSize*0.3).toFloat, at.point.y)
          List(lettering(position, text, Lettering.Anchor.Start, at.baseline, color))

  // A chart is as wide as its data needs and no wider: a bar of a single series is thirty
  // pixels wide, a bar within a group twelve, and the width follows from that, the gap between
  // groups, the room the ordinate's labels and title take, and a legend's when there is one —
  // reckoned as tasseomancy's framing reckons them, with the average font metric it measures
  // text by. The page scrolls a chart wider than the matter.
  private val leastGap: Double = 0.35
  private val singleBar: Double = 30.0
  private val groupedBar: Double = 12.0
  private val inset: Double = 12.0
  private val tickLength: Double = 5.0

  // Room beyond the reckoning, since the framing's own is not known until it has measured
  // the axis's labels: an excess is whitespace, a shortfall a clipped legend.
  private val slack: Double = 24.0

  private def textWidth(text: Text): Double = text.length*0.6*labelSize
  private def gap: Double = labelSize*0.4

  // The widest label the ordinate may show: the largest mean's integer digits and a decimal.
  private def ordinateLabel(data: BarData): Double =
    val most: Double = data.slices.bind(_.bars).map(_.upper).fold(0.0)(_.max(_))
    val digits: Int = if most < 1.0 then 1 else (log10(most).double.toInt + 1)
    (digits + 2)*0.6*labelSize

  // A single series needs no legend; the series of a crosstab are named in one.
  def benchStyle(data: BarData): Style =
    val grouped: Boolean = data.slices.size > 1
    val groups: Int = data.slices.map(_.bars.size).fold(0)(_.max(_))
    val widestCategory: Double = data.slices.bind(_.bars).map { bar => textWidth(bar.category) }.fold(0.0)(_.max(_))
    val widestName: Double = data.slices.map { slice => textWidth(slice.name) }.fold(0.0)(_.max(_))

    // A band is what its bars need at the least gap, or wider when its label is; the gap then
    // grows to keep the bars at their width.
    val bars: Double = if grouped then groupedBar*data.slices.size else singleBar
    val band: Double = (bars/(1.0 - leastGap)).max(widestCategory + 8.0)
    val barGap: Double = 1.0 - bars/band

    val left: Double = inset + tickLength + gap + ordinateLabel(data) + labelSize*1.6
    val legendRoom: Double = if grouped then widestName + labelSize + gap + gap*2.0 else 0.0
    val right: Double = inset + labelSize*0.6 + legendRoom + slack
    val width: Double = (left + right + groups*band).max(240.0)

    val legend: Chart.Legend = if grouped then Chart.Legend.Right else Chart.Legend.Hidden
    Style(width, height, legend, ordinateTitle = t"mean / ${data.base.label}", barGap = barGap)

  // A stress chart's plot is wide — a sweep may run to hundreds of concurrency steps — and
  // its width does not depend on its points, which arrive one step at a time and revise the
  // chart in place; only its legend's names, which a new curve adds to, bear on the width.
  private val stressPlot: Double = 580.0

  def stressStyle(curves: List[Curve]): Style =
    val widestName: Double = curves.map { curve => textWidth(curve.name) }.fold(0.0)(_.max(_))
    val left: Double = inset + tickLength + gap + 7*0.6*labelSize + labelSize*1.6
    val right: Double = inset + labelSize*0.6 + widestName + labelSize + gap + gap*2.0 + slack

    Style
      ( left + right + stressPlot, height, Chart.Legend.Right, abscissaTitle = t"concurrency",
        ordinateTitle = t"throughput / op·s¯¹", markers = true, smoothing = 4.0 )

  // Concurrency on a logarithmic abscissa, since a sweep doubles it step by step; throughput
  // on an exponential ordinate, which opens up the small differences among high values, with
  // the gradations still at regular intervals. The line through the measured points is
  // Kalman-smoothed, the markers at the points themselves.
  private val stressLines: Lines =
    Lines
      ( abscissa = Calibration[Int](Calibration.Policy.Logarithmic),
        ordinate = Calibration[Double](Calibration.Policy.Exponential()) )

  private def show(svg: Svg): Text = svg.xml.show

  private def partIds(drawing: Chart.Drawing): List[Text] =
    drawing.parts.to[List].map { (pair: (Svg.Id, Figure)) => pair(0).text }

  // Applies a chart's revisions to its figure: each changed part with the whole drawing
  // alongside, for a tab yet to show it — or the whole drawing when the axes moved, or when a
  // part is new (a series that has just appeared), since a page can only replace what it has.
  // A named method rather than a block lambda, which crashes the 3.9.0-p16 compiler inside
  // implicit search.
  private def revise(figure: pyrocosm.Figure, revisions: List[Chart.Revision], known: List[Text], whole: => Svg): Unit =
    lazy val drawing: Text = show(whole)

    val fresh: Boolean = revisions.exists:
      case Chart.Revision.Replace(id, _) => !known.has(id.text)
      case _                             => false

    if fresh then figure.redraw(drawing)
    else revisions.each:
      case Chart.Revision.Redraw(svg)         => figure.redraw(show(svg))
      case Chart.Revision.Replace(id, figure0) => figure.replace(id.text, figure0.xml.show, drawing)

  // A chart in hand: its figure, the drawing it was last revised from, and the data that
  // drawing shows, so a refresh which brings nothing new sends nothing.
  private enum Held:
    case Bench
      ( figure: pyrocosm.Figure,
        chart:  Chart[List[Series[Text, Estimate]], Bars, Bars.Fit, Bars.Style],
        data:   BarData )

    case Stress
      ( figure: pyrocosm.Figure,
        chart:  Chart[List[Series[Int, Double]], Lines, Lines.Fit, Lines.Style],
        curves: List[Curve] )

  private def figureOf(held: Held): pyrocosm.Figure = held match
    case Held.Bench(figure, _, _)  => figure
    case Held.Stress(figure, _, _) => figure

final class Charts:
  import Charts.*

  @scala.caps.unsafe.untrackedCaptures
  private var held: Ledger[Text, Held] = Ledger()

  @scala.caps.unsafe.untrackedCaptures
  private var figures0: Ledger[Text, pyrocosm.Figure] = Ledger()

  // The figures as of the last refresh, by the key of the table each stands above.
  def figures: Ledger[Text, pyrocosm.Figure] = figures0

  private def existing[held <: Held](key: Text)(select: PartialFunction[Held, held]): Optional[held] =
    held(key).let { held => if select.isDefinedAt(held) then select(held) else Unset }

  // A benchmark chart: bars are added, or their values revised, as records arrive; a change of
  // timebase (a chart's first record was quick, a later one slow) redraws with a new scale.
  private def bench(key: Text, data: BarData, alt: List[Inline]): Optional[pyrocosm.Figure] =
    if data.empty then Unset else
      val style: Style = benchStyle(data)
      given Chart.Standard = style

      // A chart keeps its axes while the timebase, the grouping and the width it needs hold.
      val next: Held.Bench = existing(key) { case held: Held.Bench => held } match
        case Held.Bench(figure, chart, data0) if data0.base == data.base && benchStyle(data0).width == style.width && benchStyle(data0).legend == style.legend =>
          if data0 == data then Held.Bench(figure, chart, data) else
            val known = partIds(chart.drawing)
            val (chart2, revisions) = chart.revise(benchSeries(data))
            Charts.revise(figure, revisions, known, chart2.svg)
            Held.Bench(figure, chart2, data)

        case other =>
          val chart = benchSeries(data).chart(Bars())
          val drawing: Text = chart.svg.xml.show
          val figure: pyrocosm.Figure = other.let(figureOf(_)).or(pyrocosm.Figure(alt, drawing))
          if other.present then figure.redraw(drawing)
          Held.Bench(figure, chart, data)

      held = held.define(key, next)
      next.figure

  // A stress chart: a point joins its test's curve at each concurrency step; a step beyond the
  // axes redraws them.
  private def stress(key: Text, curves: List[Curve], alt: List[Inline]): Optional[pyrocosm.Figure] =
    if curves.nil then Unset else
      val style: Style = stressStyle(curves)
      given Chart.Standard = style

      // A chart keeps its axes while its legend, and so its width, holds; a new curve redraws.
      val next: Held.Stress = existing(key) { case held: Held.Stress => held } match
        case Held.Stress(figure, chart, curves0) if stressStyle(curves0).width == style.width =>
          if curves0 == curves then Held.Stress(figure, chart, curves) else
            val known = partIds(chart.drawing)
            val (chart2, revisions) = chart.revise(stressSeries(curves))
            Charts.revise(figure, revisions, known, chart2.svg)
            Held.Stress(figure, chart2, curves)

        case other =>
          val chart = stressSeries(curves).chart(stressLines)
          val drawing: Text = chart.svg.xml.show
          val figure: pyrocosm.Figure = other.let(figureOf(_)).or(pyrocosm.Figure(alt, drawing))
          if other.present then figure.redraw(drawing)
          Held.Stress(figure, chart, curves)

      held = held.define(key, next)
      next.figure

  // Every measurement table of the run with data, charted, by key. Charts persist across
  // refreshes, so their figures are the same objects each time: a panel re-rendered from them
  // shows the current drawing, and their revisions travel by the figures themselves.
  def refresh(state: Model.State): Ledger[Text, pyrocosm.Figure] = synchronized:
    var figures1: Ledger[Text, pyrocosm.Figure] = Ledger()

    def record(key: Text, figure: Optional[pyrocosm.Figure]): Unit =
      figure.let { figure => figures1 = figures1.define(key, figure) }

    Documenting.measurements(state).each: (suite, kind, members) =>
      val suiteName: Text = suite.let(_.name).or(t"")

      kind match
        case t"bench" =>
          record(key(suite, kind, t"plain"), bench(key(suite, kind, t"plain"), plain(members),
              Inline.text(t"Mean time of each benchmark in $suiteName, with its confidence interval")))

          members.filter(!Documenting.axesOf(_).nil).each: entry =>
            val axes: Text = Documenting.axesOf(entry).join(t" and ")

            record(key(suite, kind, entry.ref.id), bench(key(suite, kind, entry.ref.id), axial(entry),
                Inline.text(t"Mean time of ${entry.ref.name} by $axes, with its confidence interval")))

        case t"stress" =>
          record(key(suite, kind, t"stress"), stress(key(suite, kind, t"stress"), curves(members),
              Inline.text(t"Throughput of each stress test in $suiteName against concurrency")))

        case _ =>
          ()

    figures0 = figures1
    figures1
