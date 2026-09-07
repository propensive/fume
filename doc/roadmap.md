# Fume roadmap

Fume runs Probably suites from a prebuilt classpath and renders their event streams as a
live terminal board or a terse CI log. Today its journal of runs lives only in daemon
memory, results are never written to disk, and every renderer works from an in-memory
`Doc.Document`. This roadmap turns fume into a test *service*: one persisted, serializable
run record, and a family of surfaces over it (terminal, CI, LLM, static reports, browser,
MCP, git notes, attestations).

Phases are ordered foundations first: the run record and its persistence come before every
surface that reads it. Items within a phase are roughly independent unless they say
otherwise.

## Where things stand

- `src/client/fume_client.scala`: subcommands `run`, `list`, `install`, and a stubbed
  `watch`. Settings cascade flag → `fume.*` property → `FUME_*` environment variable →
  `.pyrocosm/fume/config.tel` (`Workspace`).
- `src/client/fume.Journal.scala`: in-memory, daemon-lifetime, last 64 runs. Not persisted.
- `fume.Model` → `fume.Documenting` → `fume.Doc.Document`: the fold of `probably.TestEvent`s
  and the derived report. `fume.Render` (TUI/terse) and `fume.Live` (Ultimatum board) are
  the only renderers. `fume.GithubActions` gives partial CI output.
- `fume.EventStream`: BinTEL frames with a schema fingerprint, so the raw event stream is
  already a versioned wire format worth persisting verbatim.
- Probably: `Axis`/`Spread`/`Spread2`, `Baseline`/`Anchor`, `Selection` (ids, globs,
  `kind:`, axis constraints, `--scale`), `Ci` detection, and a `coverage` component
  (`Coverage`, `Juncture`, `Surface` over scoverage measurement files).
- Probably diagnostics already emitted but only partly rendered: `capture`d values
  (`DetailCaptures`), structural expected/found diffs (`DetailCompare`), `Autopsy` contrast
  analysis, and stack traces (`DetailThrows`).
- Sedentary: `BenchmarkDevice` with `LocalhostDevice` and `NetworkDevice` (an SSH
  ControlMaster session; deploy, invoke and undeploy of a staged tree) for remote benchmark
  measurement. Specialised to benchmarks today.
- Soundness attestations: `etc/ci/attest.sh` builds an in-toto statement, signs it with
  `ssh-keygen -Y`, and stores it as a git note under `refs/notes/ci-attestation` keyed by a
  *filtered tree hash*. This is the "input sources → tree hash" idea, in shell.
- Reusable Soundness libraries: `perihelion` (HTTP and `Websocket`, as flame's web module
  uses), `honeycomb` (HTML), `savagery` (SVG), `octogenarian` (git notes, `revParse`,
  worktrees), `synesthesia` (MCP server), `jacinta` (JSON), TEL/BinTEL, `capricious`
  (random). Nothing yet for golden tests or property generators.

## Principles

1. **One run record.** Everything fume shows, stores, serves or signs derives from a single
   serializable `Run` value. Renderers never own data.
2. **Probably owns semantics, fume owns presentation and persistence.** New test kinds
   (golden, property, soak) are Probably features that emit new `TestEvent`s; fume folds
   and renders them.
3. **The raw event log is the source of truth.** Documents, charts, reports and notes are
   derived and can be regenerated from stored events.
4. **Every surface shares the CLI's selection and settings.** Web, MCP and reports take the
   same selection terms and `Setting`s as `fume run`.

## Phase 0: Foundations

- **0.1 Run record.** Define `fume.Run` (or promote `Journal.Run`): identity, timestamps,
  outcome, selection terms, *effective* settings with their sources, classpath entries with
  content digests, environment capture (JVM, OS, cores, load at start), per-suite
  `Doc.Document`s and totals. Derive TEL and JSON codecs; BinTEL for the event log.
- **0.2 Persist the journal.** `.pyrocosm/fume/runs/<id>/` holding `run.tel` plus `events.bintel`
  per suite; the daemon's `Journal` becomes a cache over disk, surviving restarts. Add a
  `retention` setting, `fume runs` (list), `fume show <id|last>` (re-render a stored run)
  and `fume cancel <id>`. Run ids must be stable across daemons (timestamp plus counter).
- **0.3 Input sources and tree hash.** `input` entries in `config.tel` (globs, like
  `classpath`). Fume computes the git tree hash of exactly those paths (the filtered-tree
  scheme from `attest.sh`, via `octogenarian`), and records HEAD, a dirty flag and the tree
  hash in the run record. External dependencies are covered by the classpath digests
  recorded in 0.1: the classpath *is* the resolved closure of dependencies, so the pair
  (input tree, classpath digest) identifies a run's inputs fully.
- **0.4 Full execution configurability and user-defined commands.** Every variable of a
  run reachable from the cascade: per-kind defaults, JVM options and environment for forked
  suites, suite parallelism, per-test timeouts, random seed, retry/flaky policy, tags
  (`--tag nightly`). A `command` block in `config.tel` names a set of flags, settings and
  selection terms, so `fume quick` or `fume nightly` runs it directly; built-in subcommands
  win on a name clash, and user commands appear in tab-completion and the manpage like
  built-ins. Add `fume config` to print effective settings and where each came from, and
  `fume commands` to list the defined ones.
  *Landed (selection):* the selection grammar is complete and tab-completable. Positional
  terms are Probably's (`id`, `moniker`, `jacinta/**`, `kind:bench`, `tag:slow`, `N=4,8`,
  `N=4..64`, `N=4..`, `N=..64`, `not:TERM`), and the flags `-k/--kind`, `-t/--tag`,
  `-a/--axis` and `-x/--exclude` lower to them. Tests carry typed tags (`test(m"…", n"slow")`),
  `fume list --axes` shows each test's tags and axes, `fume list --tags` counts the tags,
  and completion offers ids, monikers, kinds, tags and — once a test is identified — its
  axes and their values, from a schedule cached in the daemon per classpath.
- **0.5 Machine identity.** A `machine` block in configuration (name, capabilities such as
  `gpu`, `32-core`, `quiet`; defaulting to the hostname) identifies the host a run executes
  on. The identity is recorded in the run record, so notes and trends (3.1) compare like
  with like, and it is the basis of machine gating (3.4) and remote execution (5.4).

## Phase 1: Output modes and static reports

- **1.1 `--output` setting** (`tui | plain | ci | llm | json`), autodetected from
  `probably.Ci`, the `CLAUDECODE` variable and whether stdout is a tty; `FUME_OUTPUT` in
  the environment.
- **1.2 CI mode.** Extend `GithubActions` into a `Ci` renderer: annotations and groups for
  GitHub, equivalents for GitLab, a Markdown job summary to `GITHUB_STEP_SUMMARY`, JUnit
  XML for systems that ingest it, no live board, deterministic ordering.
- **1.3 LLM mode.** Compact, ANSI-free, failures first with `file:line` and captured
  values, stable test ids and the exact rerun command per failure, a token budget that
  truncates passes before failures, and a machine-readable trailer (`fume rerun --failed`
  id list).
- **1.4 Static reports.** `fume report <id|last> --format json|html|tel|markdown [--to path]`
  and `--report <path>` on `run`. HTML is a single self-contained file (honeycomb) sharing
  its renderer with the web UI; TEL is the canonical form; the JSON schema is documented.
- **1.5 Display controls** shared by all modes: `--failures-only`, `--quiet`, column
  choice, `--show-passed`.
- **1.6 Failure diagnostics.** Audit what Probably already emits (captured values via
  `capture`, structural `DetailCompare` diffs, `Autopsy` contrast analysis, `DetailThrows`
  stack traces) and make sure every output mode renders all of it, with source context
  around the failing line. Then add **expression breakdowns**: an inline macro on
  `assert`/`check` decomposes the predicate into its sub-expressions, so
  `x => x.foo || !x.bar` reports `x`, `x.foo`, `x.bar`, `!x.bar` and the whole; each is
  evaluated once, and short-circuited branches are marked as not evaluated. A new
  `DetailBreakdown(ref, rows)` event carries the tree; TUI, LLM and HTML render it indented.

## Phase 2: Web front-end

- **2.1 `fume serve`.** A perihelion server hosted by the daemon (localhost, chosen port,
  optional token). Job list mirroring the TUI: active and completed runs from the persisted
  journal. Selecting an active run opens a WebSocket that replays the stored event log and
  then tails live events (the same `Model` fold, run in the page's server session); a
  completed run renders its stored document. Push-based, like flame's `/socket`.
- **2.2 Live SVG charts** (savagery) for multi-axis benchmarks and stress tests: numeric
  axes as line/area with confidence bands, discrete axes as grouped bars, stress as
  throughput and p50/p90/p99 against concurrency, updating as `BenchmarkRecorded` and
  `StrainRecorded` arrive. The same charts embed in HTML reports.
- **2.3 Coverage view.** Per-file source with covered and uncovered junctures highlighted,
  a package/file summary tree, and a trend against earlier runs (see 3.1).
- **2.4 Run comparison.** Two runs side by side: benchmark deltas against `Anchor`
  baselines, new and removed tests, regressions flagged.
- **2.5 Golden review** (depends on 4.1): show expected against actual renderings, accept
  or reject.

## Phase 3: History, git notes and attestations

- **3.1 Git notes.** `fume notes write [<id>]` stores the run's summary (benchmarks,
  stress, totals, coverage figures) under `refs/notes/fume`, keyed by the input tree hash
  *and* the commit; `fume notes read <range>` reads them back; `fume trend <test-id>`
  renders an SVG chart or terminal sparkline over the commit history. `notes` settings
  choose the ref and whether `run` writes automatically (for example on CI or under
  `--tag nightly`).
- **3.2 Attestations by fume.** `fume attest` produces the in-toto statement natively:
  subject = input tree hash plus classpath digests; predicate = the run record's digest,
  outcome, commands and tooling; signed with the user's SSH key (or an enigmatic-backed
  signer) and stored under `refs/notes/ci-attestation`. `fume verify` checks one.
  Soundness's `attest.sh` then reduces to `fume attest`.
- **3.3 Regression gates.** `regression 5%`-style settings compare the current run to the
  nearest note on the input tree's ancestry (from the same machine, per 0.5) and fail the
  run on a benchmark or coverage regression beyond the threshold.
- **3.4 Machine-gated tests.** A test declares requirements (`requires gpu`,
  `requires quiet`) matched against the running machine's capabilities from 0.5. Unmet
  requirements yield a distinct "not run here" outcome rather than a skip or failure, a
  `machine:` selection term filters explicitly, and with remote execution (5.4) fume routes
  gated tests to a machine that satisfies them.
- **3.5 SVG badges.** Generated with savagery from notes and the latest run: pass state,
  coverage, a benchmark headline with an embedded sparkline of its trend; light and dark
  variants, gradient and glow styling so they look distinctive. Served by `fume serve`,
  written by `fume badge`, and embeddable in READMEs.

## Phase 4: New test kinds (Probably work, rendered by fume)

- **4.1 Golden tests.** A `golden` kind whose result must be *serializable* (TEL via
  derivation) and *renderable* (text and HTML typeclasses). Expected values live in the
  repository under a configurable `golden` directory keyed by test id; outcomes are match,
  mismatch or new. `--accept-golden` accepts in batch; `fume golden review` walks
  mismatches in the TUI; the web UI does the same (2.5). New event:
  `GoldenRecorded(ref, expected, actual, status)`.
- **4.2 Property-based tests.** A generator typeclass (`Arbitrary`-style, built on
  capricious) with shrinking and a recorded seed, so any failure replays exactly.
  Properties are represented as tests *over an emergent axis* (`case` or `seed`), reusing
  the existing `Axis`/`Spread` machinery for selection, coordinates, tables and charts;
  classification labels become a second discrete axis, giving distribution tables for free.
- **4.3 Coverage integration.** A `coverage` setting runs suites against a
  scoverage-instrumented classpath, collects measurement files, builds `Surface`s and adds
  coverage to the run record; thresholds fail the run; terminal, HTML and web renderings
  (2.3).
- **4.4 Soak tests.** A `soak` kind runs a workload for a long, budgeted duration with a
  health probe, recording every failure and recovery as a timestamped event. From the
  timeline fume derives MTTF, MTBF, MTTR, availability and failure rate with confidence
  intervals, renders a timeline chart (live in the web UI), stores the figures in notes for
  trends, and fits the duration under `--target` like benchmarks. Soaks are natural
  candidates for remote execution (5.4) and machine gating (3.4).

## Phase 5: Integration surfaces

- **5.1 MCP server.** `fume mcp` (stdio transport, synesthesia): tools `run`, `list`,
  `rerun_failed`, `report`, `golden_accept`, `trend`; resources for runs, the latest report
  and coverage; progress notifications while a run is in flight. Reuses the LLM renderer
  from 1.3 for tool results.
- **5.2 `watch`.** Implement the existing stub over the classpath jars, with the web UI and
  MCP receiving each rerun as a new job.
- **5.3 Change-driven selection.** `--changed` selects suites whose inputs differ from the
  last attested tree (uses 0.3 and 3.1).
- **5.4 Remote execution.** Generalise sedentary's `BenchmarkDevice`/`NetworkDevice` (an
  SSH ControlMaster session with deploy, invoke and undeploy of a staged tree) into a
  fume-level device for whole runs. Machines are declared in configuration with their
  identity from 0.5; `--on <machine>` runs the selection there. The classpath is shipped
  content-addressed using the digests from 0.1, so only jars the remote lacks are
  transferred, and the remote suite's BinTEL event stream is relayed back over the SSH
  channel. The local journal, TUI, web UI and notes then see a remote run exactly as a local
  one. Sharding (below) and machine-gated routing (3.4) build on this.
- **5.5 Notifications.** Desktop first: native notifiers on macOS and Linux, plus the
  terminal OSC 9/777 escape so a notification reaches the user's desktop even when fume
  runs over SSH. Then phone and watch, as an experiment: Web Push from `fume serve` to the
  browser UI installed as a PWA (a watch mirrors the phone), with a relay such as ntfy as
  the simpler fallback. Configurable per command (`notify on-failure`,
  `notify on-finish`) so a long soak or remote run reports back without the user watching.

## Further candidates

- Flaky-test detection: retry policy, a quarantine list in `config.tel`, flakiness surfaced
  in the run record and trend charts.
- Hang detection and per-test timeouts with a stack dump in the event stream.
- Daemon lifecycle commands: `fume status`, `fume jobs`, `fume cancel`, `fume clean`.
- Benchmark stability score from environment capture (load, CPU frequency, JVM flags) so
  unstable measurements are marked before they enter notes.
- Sharding: split a selection across machines by suite or by axis value (on 5.4), merging
  run records afterwards.
- Localhost-only binding and a token for `fume serve`; no remote exposure by default.
- Rerun-until-fails and `--repeat n` for chasing intermittent failures, feeding flakiness.

## Soundness support needed

- Probably: `golden` and `soak` kinds and their events; the expression-breakdown macro and
  `DetailBreakdown` event; a generator typeclass, shrinking and seed events; emergent-axis
  coordinates for property cases; `requires` declarations for machine gating; coverage hooks
  reachable from a host; optional `TestEvent` additions (timeouts, flakiness, environment).
- Sedentary: factor `BenchmarkDevice`/`NetworkDevice` so fume can reuse the SSH session,
  staging and relay for whole-run remote execution.
- `octogenarian`: tree hashing of a filtered path set (`ls-files`/`mktree` equivalents).
- `synesthesia`, `perihelion`, `savagery`: confirm the APIs fume needs are published in the
  local release bundles.
