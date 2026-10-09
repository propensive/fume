# Fume

__Fume__ is the test runner for the [Soundness](https://soundness.dev/) ecosystem. It runs
[Probably](https://soundness.dev/probably/) tests and Sedentary benchmarks from a _prebuilt_
classpath — something else does the compiling — and it discovers suites exclusively through the
`META-INF/services/probably.Suite` index which the beneficence compiler plugin writes into every
jar it compiles.

Fume runs as an [Ethereal](https://soundness.dev/ethereal/) daemon, so invocations after the
first are fast, and uses [Exoskeleton](https://soundness.dev/exoskeleton/) for its command line,
tab-completions and manpage. It is a [Pyrocosm](https://github.com/propensive/pyrocosm) tool,
so the subcommands and configuration files every Pyrocosm tool shares are fume's too.

## Usage

```sh
fume run -c out.jar                     # run everything on the classpath
fume run -c out.jar --bench             # only benchmarks
fume run -c out.jar json/* 'N<=64'      # a path glob and an axis constraint
fume list -c out.jar                    # enumerate tests without running them
fume list -c out.jar --scan             # enumerate them by running each suite's body
fume watch -c out.jar                   # rerun whenever the jar changes
fume serve                              # serve the dashboard of runs until Ctrl+C
fume mcp                                # serve the MCP server agents query about runs until Ctrl+C
fume run -c out.jar --on linux-box      # run the selection on another machine's fume
fume listen                             # accept runs sent from other machines until Ctrl+C
fume identity                           # this machine's certificate fingerprint, for controllers
fume install                            # install tab-completions and the manpage
fume about                              # fume's version, and the daemon serving it
fume --version                          # the version alone
fume quit                               # stop the daemon, and any dashboard it serves
```

The non-flag arguments to `run`, `list` and `watch` are raw Probably selection terms — 6-hex-digit
test ids, monikers, name/path globs, and axis constraints (`parser=jacinta,merino`, `N=4..64`,
`'N<32'`) — forwarded verbatim to each suite. Note that the `<`/`>` constraint forms must be
quoted in a shell; the `=` forms need no quoting. Test kinds are restricted with the `--test`,
`--bench`, `--stress` and `--profile` switches, which union when combined; raw `kind:` terms are
rejected, since Probably's union semantics would make one silently widen a restriction rather
than narrow it.

Single-valued options such as `--classpath` and `--fail-fast` are Exoskeleton `Setting`s, read
from a cascade of sources in priority order: the command-line flag, a `fume.`-prefixed system
property, a `FUME_`-prefixed environment variable (`FUME_CLASSPATH=…`), the project's
configuration file, and finally the user's.

Each run also records what launched it: `CODEX_SANDBOX=1` in the environment marks a run as
Codex's and `CLAUDECODE=1` as Claude Code's, a run launched through the MCP server is an MCP
client's, and the dashboard shows the agent's or the protocol's icon beside such a run in its
list of runs; any other invocation is taken to be a human's.

## Listing without running

A classpath built with a recent Probably carries, beside the index of its suites, an index of
its tests: the beneficence plugin writes `META-INF/probably/tests/<source>` for every source
file, from the typed trees it compiles, recording which suite each test is declared for, the
groups it is within, and its kind, moniker, tags and declaration site. A suite is recorded with
its title and its id — given, as in `Suite("html", m"Honeycomb Tests")`, or derived from the
title, `honeycomb-tests` — and the id is a selection term like any other: `fume run html` runs
that suite, and `html/**` is a path within it. Fume reads that index, as text, for `fume
list` and for tab-completion of test ids, monikers, tags and kinds, so neither runs anything:
a suite which starts a server or reads a corpus before declaring its tests does not do so
because a shell asked for completions.

The alternative, which fume used before and still uses for a classpath with no index, is to
run each suite with every test skipped. That executes every statement *around* the tests, and
is as slow, and has the same effects, as those statements. It remains the only way to learn
three things the source does not state, and fume does it exactly when one of them is asked for:

 - a test's axes and their values: `fume list --axes`, and completing an `<axis>=` term, run
   the suites concerned (for completion, only those declaring a spread or stress test among
   the tests the other terms identify);
 - what a `--target` budget prices: each timed test's expected duration;
 - tests which only exist at runtime — those declared in an `impromptu` block, and the full
   names of tests whose names are computed, which the index holds with a `*` for each computed
   part and which `fume list` shows without an id: `fume list --scan` runs the suites to list
   them, and says so when the index has such places.

Selection terms apply to an indexed listing as they do to a run, with one difference: an axis
constraint admits every test, since which cells a test has is not in the index.

A run reads the index too, to decide which suites to invoke at all. Probably never skips a
suite: given a selection, every suite's body runs in full — everything around its tests, every
`check`, every suite it invokes — and only the assertions the selection does not admit are
skipped. So a selection naming one test would run the body of every suite on the classpath for
the sake of one. Instead fume invokes only the suites the index cannot rule out: a suite whose
tests, or whose called methods' or invoked suites' tests, the terms could admit; a suite with an
`impromptu` block, which may declare anything; and a suite the index does not cover. A test
whose name is computed is ruled out only by what the index does know of it: its kind and tags,
its monikers, and the fixed text around the holes in its name — so `json/**` rules it out when
its suite is `html`, and `case*` does not, while a hex id could be its own and always reaches
it. A suite invoked from another suite's body which is an entry point of its
own (the beneficence plugin registers every `object` extending `Suite`) is run on its own when a
term names its tests, rather than through the suite invoking it. A run with no selection terms
invokes every suite, as it always did, and `fume run` says when it reaches fewer suites than
the classpath has.

## Configuration files

A project configures fume with a [TEL](https://soundness.dev/tel/) document at
`.pyrocosm/fume/config.tel`, where the `.pyrocosm` directory sits in the project root (typically
alongside `.git`) and holds one subdirectory per tool; fume finds the file from the current
directory or any ancestor, so it can be invoked from anywhere inside the project. A user's own
defaults, for every project, go in `~/.config/fume/config.tel` (or
`$XDG_CONFIG_HOME/fume/config.tel`), which the project's file overrides. Because fume is a
daemon, the parsed files are cached, but their timestamps and sizes are checked on every
invocation, so edits take effect immediately.

The schema is exactly fume's set of `Setting`s: each setting's camelCase name is a kebab-case
TEL keyword. A keyword's atom is the setting's value; a bare keyword means `true`; a repeated
keyword joins its atoms with `:` (used for multi-entry classpaths). For example:

```
tel 1.0

classpath out/tests.jar
classpath out/util.jar
fail-fast
```

A missing file is fine (fume needs no configuration), and a file that fails to parse is treated
as absent rather than aborting the command.

Some keywords are read by the daemon itself rather than by a subcommand: `port` is the port the
dashboard serves on (8090 by default), and a bare `serve` asks the daemon to serve the dashboard
from the moment it starts, for as long as it lives, without a `fume serve` ever being run — so
`serve` in `~/.config/fume/config.tel` keeps a dashboard at `http://localhost:8090/` whenever fume
is in use. Likewise a bare `mcp` serves the MCP server from the daemon's start, on `mcp-port`
(8092 by default). `fume quit` stops them all.

## Runs are kept

Every run is persisted as it happens, under `$XDG_STATE_HOME/fume/runs/<id>/`
(`~/.local/state/fume/runs/`): `run.tel` records who ran what, where and when, and how each
suite fared, rewritten as the run progresses; `events/<n>.bintel` holds each suite's event
frames verbatim, exactly as the suite streamed them; and `captured/<n>.txt` holds whatever a
suite printed outside its report. A run's id is the UTC time it started and four hex digits of
entropy — `20261007-143512-3f9a` — so ids sort by time and survive the daemon that made them:
the daemon's journal is loaded from the directory, and a run the daemon died in the middle of is
recorded as aborted. The `retention` setting (200 by default) is how many runs are kept; the
oldest beyond it are deleted as a new run starts.

The frames are the source of truth: a run's results are never stored as such, but replayed from
them through the same model a live run folds into, whenever they are asked for.

## Querying runs from an agent

The daemon is an [MCP](https://modelcontextprotocol.io/) server, built on Soundness's
synesthesia, which an agent queries about runs: `fume mcp` serves it at
`http://localhost:8092/mcp` until Ctrl+C, `mcp` in a configuration file serves it from the
daemon's start, and it is also mounted at `/mcp` on the dashboard's port whenever the dashboard
serves. Claude Code connects with:

```sh
claude mcp add --transport http fume http://localhost:8092/mcp
```

Its tools answer in JSON: `runs` and `runsIn` list runs; `run` describes one; `results`,
`suiteResults`, `test` and `benchmarks` give per-test results with every benchmark, stress and
profile record; `failures` gives each failing test's message, stack trace, captured values and
expected-against-found comparison; `captured` gives what a suite printed; `processes` gives the
daemon's processes — the services it serves, the runs in flight with the tests they are
executing, and any run it is working for another machine; and `suites` and `tests` list what a
classpath declares, from its static index, without running anything; and `launch` starts a run
of a classpath's suites, narrowed by selection terms, which the daemon runs as it would from a
shell and the other tools follow as it goes, until `cancel` aborts it. `last` names the newest run
wherever a run id is taken. The resources `fume://schema` (the JSON Schema of every answer),
`fume://docs` (the server's documentation, also at [doc/mcp.md](/doc/mcp.md)) and
`fume://runs/latest` describe the rest.

No tool takes an optional parameter, because synesthesia lists every parameter as required and
cannot decode an absent one; its tool errors, resource MIME types and capture checking have
similar gaps, which [propensive/soundness#2187](https://github.com/propensive/soundness/issues/2187)
records, and fume designs around until they are addressed.

## Running tests on another machine

A selection can be sent to another machine whose fume daemon is listening, with `--on <machine>`.
The local fume (the *controller*) ships the classpath content-addressed — only the jars the
other machine (the *worker*) has not seen travel, and a directory of classes is bundled into a
jar first — and the worker runs the suites exactly as a local `fume run` would, relaying each
suite's event frames back verbatim. The controller's terminal board, report, journal and
dashboard then show the run as if it were local, labelled with the machine's name. Benchmark
suites need no change: Sedentary stages and measures on the worker, in a fresh measurement JVM
per cell, as it does locally. Tests that read fixture files by path will not find them on the
worker in this first version.

The worker listens on TCP with TLS. Its identity is a self-signed certificate generated on the
first `fume listen` (or the first daemon start with `listen` in a config), shared by every
Pyrocosm tool on that machine and kept under `$XDG_STATE_HOME/pyrocosm/remote/`; `fume identity`
prints its fingerprint. A controller pins that fingerprint and presents a shared token — the
SSH known-hosts model, expressed in TLS. Machines are declared once, for every Pyrocosm tool, in
`~/.config/pyrocosm/machines.tel`, or in fume's own configuration files:

```
tel 1.0

machine linux-box
  host      build.example.org
  port      8091
  identity  sha256:3f9a…                     # from `fume identity` on that machine
  token     ~/.config/pyrocosm/tokens/linux-box   # a file holding the worker's token
  capability linux x86-64 quiet
```

On the worker, `listen` in a config file starts the listener with the daemon, `listen-port` sets
its port (8091 by default), and `listen-token` names the token it expects (a file, or the token
itself); without one, the machine's own token at `~/.config/pyrocosm/token` is generated and
used. `capability` words are reported to controllers. A worker runs one selection at a time and
refuses a second controller as busy; Ctrl+C at the controller aborts the run on the worker, as
does a lost connection.

The two fumes need not be the same version: the worker never decodes a suite's events, so only
the layout of fume's own relay messages must agree, which the handshake checks by fingerprint
before anything is sent. The suite's Probably must match the controller's fume, as for a local
run.

## Status

The daemon, completions, manpage, configuration file, and the `run` and `list` subcommands are
working: suites are discovered from the classpath's `META-INF/services/probably.Suite` index
(and offered as tab-completions for `--suite`), and each selected suite is invoked IN-PROCESS
by default: it is loaded in a fresh child-first classloader (platform parent, so the suite's
own Soundness version never meets fume's) and Probably's `Streamer.stream` is called
reflectively — its erased `stream(String, String, OutputStream): int` crosses the classloader
boundary with JDK types alone. `--fork` runs each suite in a JVM of its own instead, through
Probably's `probably.Standalone` entry point, and its events reach the same board and report;
a suite built against a Probably too old for the event stream falls back to a forked JVM
automatically. `watch` is
not yet implemented and exits with status 10. Reporting is currently whatever each suite prints
(a pass/fail count per suite) plus a one-line suite-count summary; richer aggregated output is
planned.

A suite run in-process runs in the daemon's JVM, and so in the daemon's process state rather
than the invocation's: the daemon starts in `/`, with a sanitized environment, whichever
invocation happened to start it. A suite which resolves a relative path through the JVM's working
directory (`user.dir`, `javaBaseWorkingDirectory`, a relative `java.io.File`), or reads a
variable from the JVM's environment (`System.getenv`, `javaBaseEnvironment`), sees `/` and that
sanitized environment. Such a suite should be given what it needs explicitly — an absolute path
recorded at build time, say — or be run with `--fork`, which starts each suite's JVM in the
directory and with the environment `fume run` was invoked with.

Whatever a suite prints through the JVM's own streams while it runs in-process — a stray
`println`, a stack trace, a library's diagnostic — is captured rather than shown, since the
terminal is the board's while a run is up. It is appended to `$XDG_STATE_HOME/fume/output.log`
(`~/.local/state/fume/output.log`), announced in a line after the run, and shown as captured
output in the dashboard's report.

## Modules

Fume is structured as five build modules:

 - `relay`: the messages a controller and a worker exchange, and their codec; published as
   `dev.propensive:fume-relay`
 - `api`: the run record every run is persisted as, the JSON types the MCP server answers with
   and their schema, and the MCP server itself; published as `dev.propensive:fume-api`
 - `client`: all of fume's logic — the subcommands, flags, dispatch and the test-running
   machinery; published to Maven Central as `dev.propensive:fume-client`
 - `launcher`: the invocation point alone — `@main def fume() = externalize(runClient())` —
   depending on `fume-client` as a published Maven Central coordinate, so that Burdock can
   externalize it (unpublished; only buildable after a release)
 - `test`: fume's own Probably test suite (unpublished)

## Building

Before a first release (or to iterate locally), build and run fume without Maven Central:
```sh
make run    # publishLocal fume-client, assemble the launcher, run the fat jar
make test   # run fume's own test suite
make dev    # recompile fume.client on every source change
```

To build the self-fetching native launcher locally:
```sh
make fume                    # assemble, repackage with Burdock, emit the `fume` executable
make install                 # copy it to ~/.local/bin
```

A release is cut by tagging, not by make. Wait for CI to go green on the commit, and then `git tag -s X.Y.Z && git push --tags`: the tag fires
`.github/workflows/release.yml`, which publishes the `fume-client` jar and the repackaged
executables through the shared `release.sh` in
[propensive/.github](https://github.com/propensive/.github).

## Native launcher (Burdock)

The `launcher` module's entry point is wrapped in `externalize(…)`, which records the SHA-256 of
every jar on its compile classpath into `META-INF/burdock.deps`. `make fume` runs
`soundness.repackage` over the assembly, rewriting dependencies whose exact bytes are resolvable
on Maven Central (via deps.dev) into on-demand `Burdock-Require` downloads, and inlining the rest
from `~/.cache/burdock`. The result is then emitted as an Ethereal launch script — a single
self-extracting executable that starts (or attaches to) the fume daemon.

A useful side-effect of the Burdock bootstrap: fume's own classes (and its Soundness classes) are
loaded in a classloader whose parent is the _platform_ classloader, fully isolated from the
user-supplied `--classpath`, whose jars may contain different versions of the same Soundness
classes.

## License

Fume is copyright &copy; 2026 Jon Pretty & Propensive O&Uuml;, and is made available under the
[Apache 2.0 License](/.github/license.md).
