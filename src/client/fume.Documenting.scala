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

import scala.math

import soundness.*

import probably.TestEvent

// The single structural pass over an accumulated event `Model`: builds the renderer-agnostic
// `Doc.Document` consumed by every output mode. All decisions about WHAT appears in a report
// are made here; the renderers decide only how it looks. A reimplementation, not a port, of
// probably's `Documenting`: the event payloads arrive already flattened (a benchmark's
// statistics are fields, not a metrics ledger), so the derivations work directly on them.
object Documenting:
  import Doc.{Block, Column, Datum, Document, Group, Spark, Status, SummaryRow, Totals}
  import Model.{Entry, Line, State}

  def document(state: State): Document =
    val counted: List[SummaryRow] = summaries(state, measurements = true)
    val results: List[SummaryRow] = summaries(state, measurements = false)

    val totals: Totals =
      def add(totals: Totals, status: Status): Totals = status match
        case Status.Suite      => totals
        case Status.Pass       => totals.copy(passed = totals.passed + 1)
        case Status.Bench      => totals.copy(passed = totals.passed + 1)
        case Status.Stress     => totals.copy(passed = totals.passed + 1)
        case Status.Profile    => totals.copy(passed = totals.passed + 1)
        case Status.AspirePass => totals.copy(aspirePassed = totals.aspirePassed + 1)
        case Status.AspireFail => totals.copy(aspireFailed = totals.aspireFailed + 1)
        case _                 => totals.copy(failed = totals.failed + 1)

      counted.stdlib.foldLeft(Totals.zero) { (totals, row) => add(totals, row.status) }

    Document(results, totals, groups(state), state.details, state.fatal, state.nothingMatched)

  private def parent(path: List[Text]): List[Text] = (path.stdlib.dropRight(1)).to(List)

  def depth(ref: TestEvent.Ref): Int = (ref.path.stdlib.length - 1).max(0)

  // The collective status of one entry's outcomes.
  def entryStatus(entry: Entry): Status = entry.kind.or(t"check") match
    case t"bench"   => Status.Bench
    case t"stress"  => Status.Stress
    case t"profile" => Status.Profile

    case _ =>
      Status.collective:
        entry.completions.map { completion => Status.of(completion(1).outcome) }

  // One row per suite and per entry, in declaration order; a `check` entry's runs are
  // aggregated across all its cells into a single status and duration statistics.
  //
  // A measurement entry has no count and no timing statistics, so with `measurements` unset
  // such entries are omitted, as are the suites their omission leaves without any test to
  // show. The totals still count them, so the caller asks for them included when counting
  // and excluded when rendering.
  private def summaries(state: State, measurements: Boolean): List[SummaryRow] =
    def measurement(entry: Entry): Boolean = entry.kind.or(t"check") != t"check"

    // The suites which retain at least one rendered row: those with a non-measurement test
    // (at any depth) beneath them.
    val populated: List[List[Text]] =
      state.lines.bind[List[List[Text]], List[Text], List[List[Text]]]:
        case Line.EntryLine(entry) =>
          if measurement(entry) then Nil else
            val path = entry.ref.path
            // Every proper prefix of the test's path is an ancestor suite's path.
            def prefixes(n: Int, acc: List[List[Text]]): List[List[Text]] =
              if n >= path.stdlib.length then acc else prefixes(n + 1, (path.stdlib.take(n)).to(List) :: acc)
            prefixes(1, Nil)

        case _ =>
          Nil

    state.lines.bind[List[SummaryRow], SummaryRow, List[SummaryRow]]:
      case Line.SuiteLine(ref) =>
        if !measurements && !populated.has(ref.path) then Nil
        else List(SummaryRow(Status.Suite, ref, 0, 0L, 0L, 0L))

      case Line.EntryLine(entry) =>
        if measurement(entry) then
          // A SCHEDULED measurement that never recorded anything (an aborted or filtered
          // run) is not a result and counts towards nothing.
          val ran =
            !entry.benches.nil || !entry.strains.nil || entry.hotspots.present

          if measurements && ran
          then List(SummaryRow(entryStatus(entry), entry.ref, 0, 0L, 0L, 0L))
          else Nil
        else
          val durations: List[Long] =
            entry.completions.map { completion => completion(1).duration }

          if durations.nil then Nil else
            val avg: Long = durations.stdlib.foldLeft(0L)(_ + _)/durations.stdlib.length
            val min: Long = durations.stdlib.foldLeft(Long.MaxValue)(_.min(_))
            val max: Long = durations.stdlib.foldLeft(0L)(_.max(_))

            List(SummaryRow(entryStatus(entry), entry.ref, durations.stdlib.length, min, max, avg))

  // The display text and numeric value of one coordinate.
  def coordText(coordinate: TestEvent.Coordinate): Text =
    coordinate.discrete.or:
      coordinate.integral.let(_.show).or(coordinate.decimal.let(_.toString.tt))
    . or(t"")

  private def coordNumeric(coordinate: TestEvent.Coordinate): Double =
    coordinate.integral.let(_.toDouble).or(coordinate.decimal).or(0.0)

  // Measurement entries group by their immediate suite, one `Group` per suite and kind, in
  // kind-major order (`check` grids, then benchmarks, stress and profiles), suites in
  // declaration order within each kind.
  private def groups(state: State): List[Group] =
    val suiteRefs: List[TestEvent.Ref] =
      state.lines.bind[List[TestEvent.Ref], TestEvent.Ref, List[TestEvent.Ref]]:
        case Line.SuiteLine(ref) => List(ref)
        case _                   => Nil

    def suiteOf(entry: Entry): Optional[TestEvent.Ref] =
      suiteRefs.seek(_.path == parent(entry.ref.path))

    val entries: List[Entry] =
      state.lines.bind[List[Entry], Entry, List[Entry]]:
        case Line.EntryLine(entry) => List(entry)
        case _                     => Nil

    List(t"check", t"bench", t"stress", t"profile").bind[List[Group], Group, List[Group]]: (kind: Text) =>
      val ofKind: List[Entry] = entries.filter(_.kind.or(t"check") == kind)

      val relevant: List[Entry] = kind match
        // Only axial unit tests need their own blocks (a table or grid of per-cell
        // statuses); ordinary tests are fully described by the results table.
        case t"check" => ofKind.filter(_.completions.exists(!_(0).nil))
        case _        => ofKind

      // Group by immediate suite, preserving declaration order of suites and entries.
      val paths: List[List[Text]] =
        relevant.map { entry => parent(entry.ref.path) }.distinct

      paths.bind[List[Group], Group, List[Group]]: (path: List[Text]) =>
        val members: List[Entry] = relevant.filter { entry => parent(entry.ref.path) == path }
        val suite: Optional[TestEvent.Ref] = suiteRefs.seek(_.path == path)

        // A group where nothing has recorded and nothing is running collapses to one line:
        // its members stay elided until the group starts evaluating.
        val started: Boolean =
          members.exists: entry =>
            !entry.completions.nil || !entry.benches.nil || !entry.strains.nil
              || entry.hotspots.present || state.active.exists(_.id == entry.ref.id)

        if !started then List(Group(suite, kind, Nil, pending = true))
        else
          val blocks: List[Block] = kind match
            case t"check"  => members.map(axialCheck(_))
            case t"bench"  => benchBlocks(members)
            case t"stress" => stressBlocks(members)
            case _         => members.map(histogram(_))

          if blocks.nil then Nil else List(Group(suite, kind, blocks))

  // ---------------------------------------------------------------- unit-test grids

  private def outcomeStatus(outcomes: List[TestEvent.Outcome]): Status =
    Status.collective(outcomes.map { outcome => Status.of(outcome.outcome) })

  // An axial unit test: one axis renders as a table of per-value statuses and timings; two
  // axes render as a grid of statuses with gaps at undefined combinations; more render as a
  // flat listing.
  private def axialCheck(entry: Entry): Block =
    val axes: List[Text] =
      entry.completions.bind[List[Text], Text, List[Text]]: completion =>
        completion(0).map(_.axis)
      . distinct

    axes match
      case axis :: Nil =>
        val values: List[Text] = axisValues(entry, axis).map(coordText(_))

        val rows: List[List[Datum]] =
          values.map: value =>
            val outcomes: List[TestEvent.Outcome] =
              entry.completions.filter(_(0).exists { c => coordText(c) == value }).map(_(1))

            val durations: List[Long] = outcomes.map(_.duration)

            val time: Datum =
              if durations.nil then Datum.Blank
              else Datum.Time(durations.stdlib.foldLeft(0L)(_ + _)/durations.stdlib.length)

            List(Datum.Str(value), Datum.Mark(outcomeStatus(outcomes)), time)

        Block.Table
          ( entry.ref,
            List(Column(axis, stretch = true), Column(t"Status"), Column(t"Time", numeric = true)),
            rows )

      case first :: second :: Nil =>
        crosstab(entry, first, second): completions =>
          if completions.nil then Datum.Gap
          else Datum.Mark(outcomeStatus(completions.map(_(1))))

      case _ =>
        val rows: List[List[Datum]] =
          entry.completions.map: completion =>
            val address: Text = completion(0).map(coordText(_)).join(t", ")
            List(Datum.Str(address), Datum.Mark(Status.of(completion(1).outcome)))

        Block.Table
          ( entry.ref,
            List(Column(axes.join(t", "), stretch = true), Column(t"Status")),
            rows )

  // The values of one axis across an entry's cells, in first-appearance order for discrete
  // axes and numeric order otherwise.
  private def axisValues(entry: Entry, axis: Text): List[TestEvent.Coordinate] =
    val all: List[TestEvent.Coordinate] =
      entry.completions.bind[List[TestEvent.Coordinate], TestEvent.Coordinate,
          List[TestEvent.Coordinate]]: completion =>
        completion(0).filter(_.axis == axis)

    val benchCoords: List[TestEvent.Coordinate] =
      entry.benches.bind[List[TestEvent.Coordinate], TestEvent.Coordinate,
          List[TestEvent.Coordinate]]: bench =>
        bench.coordinates.filter(_.axis == axis)

    val combined: List[TestEvent.Coordinate] = all + benchCoords

    val distinct: List[TestEvent.Coordinate] =
      def recur(rest: List[TestEvent.Coordinate], seen: List[Text],
          acc: List[TestEvent.Coordinate]): List[TestEvent.Coordinate] =
        rest match
          case head :: tail =>
            val text = coordText(head)
            if seen.has(text) then recur(tail, seen, acc)
            else recur(tail, text :: seen, head :: acc)
          case _ => acc.reverse

      recur(combined, Nil, Nil)

    if distinct.exists(_.domain == t"discrete") then distinct
    else (distinct.stdlib.sortBy(coordNumeric(_))).to(List)

  // The biaxial grid: the first axis's values are rows, the second's are columns, and each
  // cell holds only the headline datum; absent combinations render as gaps.
  private def crosstab(entry: Entry, first: Text, second: Text)
     (cell: List[(List[TestEvent.Coordinate], TestEvent.Outcome)] => Datum)
  :   Block =

    val rowValues: List[Text] = axisValues(entry, first).map(coordText(_))
    val columnValues: List[Text] = axisValues(entry, second).map(coordText(_))

    val rows: List[List[Datum]] =
      rowValues.map: row =>
        val cells: List[Datum] =
          columnValues.map: column =>
            val matching = entry.completions.filter: completion =>
              completion(0).exists { c => c.axis == first && coordText(c) == row }
                && completion(0).exists { c => c.axis == second && coordText(c) == column }

            cell(matching)

        Datum.Str(row) :: cells

    val columns: List[Column] =
      Column(first, stretch = true) :: columnValues.map { value => Column(value, numeric = true) }

    Block.Table(entry.ref, columns, rows)

  // ---------------------------------------------------------------- benchmarks

  private def confidence(bench: TestEvent.BenchmarkRecorded): Datum =
    if bench.mean == 0.0 || bench.runs < 2 then Datum.Blank else
      val interval: Double =
        Figures.tQuantile(bench.confidence, bench.runs - 1)*bench.sd/math.sqrt(bench.runs.toDouble)

      val basisPoints: Long = (interval/bench.mean*10000.0).toLong
      if basisPoints == 0L then Datum.Blank else Datum.Conf(bench.confidence, basisPoints)

  private def throughput(bench: TestEvent.BenchmarkRecorded): Long =
    if bench.mean == 0.0 then 0L else (1000000000.0/bench.mean).toLong

  private def rate(bench: TestEvent.BenchmarkRecorded): Datum =
    if throughput(bench) == 0L then Datum.Blank else Datum.Rate(throughput(bench))

  private def blankCells(count: Int): List[Datum] =
    (scala.List.fill(count)(Datum.Blank: Datum)).to(List)

  private def benchMetricColumns(sized: Boolean): List[Column] =
    val sizes: List[Column] =
      if sized then List(Column(t"Size", numeric = true), Column(t"Rate", numeric = true))
      else Nil

    List
      ( Column(t"n", numeric = true),
        Column(t"μ", numeric = true),
        Column(t"σ", numeric = true),
        Column(t"Confidence", numeric = true),
        Column(t"Throughput", numeric = true),
        Column(t"Alloc·op¯¹", numeric = true) )
    + sizes

  private def benchMetricCells(bench: TestEvent.BenchmarkRecorded, sized: Boolean): List[Datum] =
    val sizes: List[Datum] =
      if sized
      then
        List
          ( bench.operationSize.lay(Datum.Blank)(Datum.Str(_)),
            bench.operationRate.lay(Datum.Blank)(Datum.Str(_)) )
      else Nil

    // Bytes allocated per operation, when the harness measured it (a suite built against a
    // Soundness whose `Bench` predates the field never reaches here: its fingerprint differs).
    val allocation: Optional[Long] = bench.allocation

    List
      ( Datum.Num(bench.iterations),
        Datum.Time(bench.mean.toLong),
        Datum.Time(bench.sd.toLong),
        confidence(bench),
        rate(bench),
        allocation.lay(Datum.Blank)(Datum.Memory(_)) )
    + sizes

  // The baseline-relative datum of one benchmark against the anchor's, following the
  // anchor's settings: the compared statistic (min/mean/max of the timing distribution), as
  // a ratio (`Geometric`, the anchor's own row showing ★) or a signed difference
  // (`Arithmetic`), inverted to a rate for a non-temporal baseline.
  private def relative
     ( anchor: TestEvent.AnchorRecorded,
       anchorBench: TestEvent.BenchmarkRecorded,
       bench: TestEvent.BenchmarkRecorded )
  :   Datum =

    def stat(bench: TestEvent.BenchmarkRecorded): Double = anchor.compare match
      case t"Min" => bench.min
      case t"Max" => bench.max
      case _      => bench.mean

    val value: Double = stat(bench)
    val anchorValue: Double = stat(anchorBench)

    if value == 0.0 || anchorValue == 0.0 then Datum.Blank else
      val temporal: Boolean = anchor.metric == t"Temporal"
      def side(value: Double): Double = if temporal then value else 1.0/value

      anchor.mode match
        case t"Arithmetic" =>
          val difference: Double = side(value) - side(anchorValue)

          if difference == 0.0 then Datum.Ratio(1.0) else
            val magnitude: Datum =
              if temporal then Datum.Time(math.abs(difference).toLong)
              else Datum.Rate(math.abs(difference).toLong)

            Datum.Delta(magnitude, difference < 0.0)

        case _ =>
          Datum.Ratio(side(value)/side(anchorValue))

  private def benchBlocks(entries: List[Entry]): List[Block] =
    // Once its group has started, a benchmark without results yet occupies its named row
    // with BLANK cells, filling in when the data arrives. (An entry that turns out to be
    // axial leaves the plain table for its own titled table with its first record.)
    val plain: List[Entry] = entries.filter(_.benches.all(_.coordinates.nil))
    val axial: List[Entry] = entries.filter(_.benches.exists(!_.coordinates.nil))

    val sized: Boolean =
      entries.exists(_.benches.exists { bench =>
        bench.operationSize.present || bench.operationRate.present })

    val table: List[Block] =
      // DECLARATION order, not throughput order: a live board pre-lists the scheduled rows
      // and fills each in place as its result arrives, so rows must not move.
      val rows: List[List[Datum]] =
        plain.map: entry =>
          val lead: List[Datum] =
            List(Datum.Hash(entry.ref.id), Datum.Title(entry.ref.name, 0))

          // As many blanks as there are metric columns, so a placeholder row is never shorter
          // than the header it sits under.
          entry.benches.prim.lay(lead + blankCells(benchMetricColumns(sized).stdlib.length)): bench =>
            lead + benchMetricCells(bench, sized)

      // The winner is marked only once EVERY scheduled row has its result: a leader among
      // stragglers is not yet the best.
      val highlight: List[Int] =
        if plain.nil || plain.exists(_.benches.nil) then Nil else
          val rates: scala.List[Long] =
            plain.map { entry => entry.benches.prim.lay(0L)(throughput(_)) }.stdlib

          val best = rates.max
          if best == 0L then Nil else List(rates.indexOf(best))

      if rows.nil then Nil else
        List(Block.Table
          ( Unset,
            List(Column(t"Hash"), Column(t"Test", stretch = true)) + benchMetricColumns(sized),
            rows,
            highlight ))

    table + axial.bind[List[Block], Block, List[Block]] { entry => axialBench(entry, sized) }

  // An entry with one axis renders as a table of its runs; with two, as a crosstab of
  // headline data; with more, as a flat listing of coordinates and headlines.
  private def axialBench(entry: Entry, sized: Boolean): List[Block] =
    val axes: List[Text] =
      entry.benches.bind[List[Text], Text, List[Text]] { bench => bench.coordinates.map(_.axis) }
      . distinct

    def benchAt(axis: Text, value: Text): Optional[TestEvent.BenchmarkRecorded] =
      entry.benches.seek(_.coordinates.exists { c => c.axis == axis && coordText(c) == value })

    def headline(bench: TestEvent.BenchmarkRecorded): Datum =
      if sized then rate(bench) else Datum.Time(bench.mean.toLong)

    axes match
      case axis :: Nil =>
        val anchored: Optional[(TestEvent.AnchorRecorded, TestEvent.BenchmarkRecorded)] =
          entry.anchor.let: anchor =>
            val value: Text =
              anchor.discrete.or(anchor.integral.let(_.show)).or(anchor.decimal.let(_.toString.tt)).or(t"")

            benchAt(axis, value).let(anchor -> _)

        val comparisonColumns: List[Column] = anchored.lay(Nil): (anchor, _) =>
          val value: Text =
            anchor.discrete.or(anchor.integral.let(_.show)).or(anchor.decimal.let(_.toString.tt)).or(t"")

          List(Column(t"×$value", numeric = true))

        val rows: List[List[Datum]] =
          axisValues(entry, axis).bind[List[List[Datum]], List[Datum], List[List[Datum]]]:
            coordinate =>
            benchAt(axis, coordText(coordinate)).lay(Nil): bench =>
              val comparison: List[Datum] = anchored.lay(Nil): (anchor, anchorBench) =>
                List(relative(anchor, anchorBench, bench))

              List(Datum.Str(coordText(coordinate)) :: benchMetricCells(bench, sized) + comparison)

        List(Block.Table
          ( entry.ref,
            Column(axis, stretch = true) :: benchMetricColumns(sized) + comparisonColumns,
            rows ))

      case first :: second :: Nil =>
        val rowValues: List[Text] = axisValues(entry, first).map(coordText(_))
        val columnValues: List[Text] = axisValues(entry, second).map(coordText(_))

        val rows: List[List[Datum]] =
          rowValues.map: row =>
            val cells: List[Datum] =
              columnValues.map: column =>
                entry.benches.seek: bench =>
                  bench.coordinates.exists { c => c.axis == first && coordText(c) == row }
                    && bench.coordinates.exists { c => c.axis == second && coordText(c) == column }
                . lay(Datum.Gap)(headline(_))

            Datum.Str(row) :: cells

        val columns: List[Column] =
          Column(first, stretch = true) :: columnValues.map { value => Column(value, numeric = true) }

        List(Block.Table(entry.ref, columns, rows))

      case _ =>
        val rows: List[List[Datum]] =
          entry.benches.map: bench =>
            val address: Text = bench.coordinates.map(coordText(_)).join(t", ")
            List(Datum.Str(address), headline(bench))

        List(Block.Table
          ( entry.ref,
            List(Column(axes.join(t", "), stretch = true), Column(t"Headline", numeric = true)),
            rows ))

  // ---------------------------------------------------------------- stress tests

  private def strainThroughput(strain: TestEvent.StrainRecorded): Long =
    if strain.nanoseconds == 0L then 0L
    else (strain.operations.toDouble*1000000000.0/strain.nanoseconds).toLong

  private def allocationRate(strain: TestEvent.StrainRecorded): Long =
    if strain.operations == 0L then 0L else strain.allocation/strain.operations

  // A stress group renders as the sparkline of every curve, then one row per implementation
  // at its best point, ranked. (The per-step detail table upstream was reserved for a
  // verbose mode that was never reachable; it is not reproduced.)
  private def stressBlocks(entries0: List[Entry]): List[Block] =
    val pending: List[Entry] = entries0.filter(_.strains.nil)
    val entries: List[Entry] = entries0.filter(!_.strains.nil)

    // Each stress entry's strains form its scaling curve: concurrency against the strain
    // measured there; a repeated concurrency keeps its first measurement.
    val curves: List[(Entry, List[TestEvent.StrainRecorded])] =
      entries.map: entry =>
        def recur(rest: List[TestEvent.StrainRecorded], seen: List[Int],
            acc: List[TestEvent.StrainRecorded]): List[TestEvent.StrainRecorded] =
          rest match
            case head :: tail =>
              if seen.has(head.concurrency) then recur(tail, seen, acc)
              else recur(tail, head.concurrency :: seen, head :: acc)
            case _ => acc.reverse

        entry -> recur(entry.strains, Nil, Nil)

    val steps: List[Long] =
      val all: List[Long] =
        curves.bind[List[Long], Long, List[Long]] { curve => curve(1).map(_.concurrency.toLong) }

      val shared: List[Long] =
        if curves.stdlib.length < 2 then all.distinct
        else
          (all.stdlib.groupBy { step => step }.filter(_(1).length > 1).keys.toList).to(List)

      val chosen: List[Long] = if shared.stdlib.length > 1 then shared else all.distinct
      (chosen.stdlib.sorted).to(List)

    val sparkline: List[Block] =
      if steps.stdlib.length < 2 then Nil else
        val peakRate: Long =
          val rates: List[Long] =
            curves.bind[List[Long], Long, List[Long]]: curve =>
              curve(1).map(strainThroughput(_))

          rates.stdlib.foldLeft(1L)(_.max(_))

        val sequence: List[Spark] =
          curves.map: (entry, curve) =>
            val sustained: Optional[(Long, Long)] =
              curve.seek(_.sustained).let: strain =>
                (strain.concurrency.toLong, strainThroughput(strain))

            val limit: Long = sustained.lay(Long.MaxValue)(_(0))

            val cells: List[Optional[(Int, Boolean)]] =
              steps.map: step =>
                curve.seek(_.concurrency.toLong == step).let: strain =>
                  val level: Int =
                    ((strainThroughput(strain)*8L + peakRate - 1L)/peakRate).toInt.min(8).max(1)

                  (level, step > limit)

            Spark(entry.ref.name, cells, sustained)

        List(Block.Sparkline(steps, sequence))

    val latencies: Boolean = entries.exists(_.strains.exists(_.p99.present))
    val slo: Boolean = entries.exists(_.strains.exists(_.compliance.present))

    // The point that stands for a whole scaling curve: the confirmed row of a capacity
    // search when there is one, and otherwise the step at which throughput peaked.
    val peaks: List[(Entry, TestEvent.StrainRecorded)] =
      curves.bind[List[(Entry, TestEvent.StrainRecorded)], (Entry, TestEvent.StrainRecorded),
          List[(Entry, TestEvent.StrainRecorded)]]:
        (entry, curve) =>
          val best: Optional[TestEvent.StrainRecorded] =
            curve.seek(_.sustained).or:
              curve.stdlib.maxByOption(strainThroughput(_)).optional

          best.lay(Nil) { strain => List(entry -> strain) }

    val bestRate: Long =
      peaks.map { peak => strainThroughput(peak(1)) }.stdlib.foldLeft(0L)(_.max(_))

    val ranked: Boolean = peaks.stdlib.length > 1 && bestRate > 0L

    val summary: List[Block] =
      if peaks.nil && pending.nil then Nil else
        val columns: List[Column] =
          List
            ( Column(t"Hash"),
              Column(t"Test", stretch = true),
              Column(t"N", numeric = true),
              Column(t"Throughput", numeric = true) )
          + (if ranked then List(Column(t"×best", numeric = true)) else Nil: List[Column])
          + List(Column(t"Alloc·op¯¹", numeric = true))
          + (if latencies then List(Column(t"p99", numeric = true)) else Nil: List[Column])
          + (if slo then List(Column(t"SLO", numeric = true)) else Nil: List[Column])

        val rows: List[List[Datum]] =
          val sorted: List[(Entry, TestEvent.StrainRecorded)] =
            (peaks.stdlib.sortBy { peak => -strainThroughput(peak(1)) }).to(List)

          sorted.map: (entry, strain) =>
            val rate: Long = strainThroughput(strain)

            val lead: List[Datum] =
              List
                ( Datum.Hash(entry.ref.id),
                  Datum.Title(entry.ref.name, 0),
                  Datum.Num(strain.concurrency.toLong),
                  if rate == 0L then Datum.Blank else Datum.Rate(rate) )

            val ratio: List[Datum] =
              if ranked then List(Datum.Ratio(rate.toDouble/bestRate)) else Nil

            val alloc: List[Datum] = List(Datum.Memory(allocationRate(strain)))

            val latency: List[Datum] =
              if latencies then List(strain.p99.lay(Datum.Blank)(Datum.Time(_))) else Nil

            val sloCell: List[Datum] =
              if slo then List(strain.compliance.lay(Datum.Blank)(Datum.Percent(_))) else Nil

            lead + ratio + alloc + latency + sloCell

        // Scheduled stress tests that have not begun occupy their named rows, blank.
        val blanks: List[List[Datum]] =
          pending.map: entry =>
            val cells =
              2 + (if ranked then 1 else 0) + 1 + (if latencies then 1 else 0)
                + (if slo then 1 else 0)

            List(Datum.Hash(entry.ref.id), Datum.Title(entry.ref.name, 0)) + blankCells(cells)

        // Rows are sorted by descending throughput, so once nothing remains pending the
        // winner is the first row.
        val highlight: List[Int] =
          if pending.nil && !peaks.nil && bestRate > 0L then List(0) else Nil

        List(Block.Table(Unset, columns, rows + blanks, highlight))

    sparkline + summary

  // ---------------------------------------------------------------- profiles

  private def histogram(entry: Entry): Block =
    entry.hotspots.lay(Block.Pending(List(entry.ref))): hotspots =>
      Block.Histogram(entry.ref, hotspots.total, hotspots.frames)
