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
fume watch -c out.jar                   # rerun whenever the jar changes
fume serve                              # serve the dashboard of runs until Ctrl+C
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
Codex's and `CLAUDECODE=1` as Claude Code's, and the dashboard shows the agent's icon beside such
a run in its list of runs; any other invocation is taken to be a human's.

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

Two keywords are read by the daemon itself rather than by a subcommand: `port` is the port the
dashboard serves on (8090 by default), and a bare `serve` asks the daemon to serve the dashboard
from the moment it starts, for as long as it lives, without a `fume serve` ever being run — so
`serve` in `~/.config/fume/config.tel` keeps a dashboard at `http://localhost:8090/` whenever fume
is in use. `fume quit` stops both.

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
own Soundness version never meets fume's) and its non-exiting `Suite#invoke` is called
reflectively — the erased `invoke(String[]): int` crosses the classloader boundary with JDK
types alone. `--fork` runs each suite in a separate JVM instead, and a suite built against a
Probably too old to have `Suite#invoke` falls back to a forked JVM automatically. `watch` is
not yet implemented and exits with status 10. Reporting is currently whatever each suite prints
(a pass/fail count per suite) plus a one-line suite-count summary; richer aggregated output is
planned.

Whatever a suite prints through the JVM's own streams while it runs in-process — a stray
`println`, a stack trace, a library's diagnostic — is captured rather than shown, since the
terminal is the board's while a run is up. It is appended to `$XDG_STATE_HOME/fume/output.log`
(`~/.local/state/fume/output.log`), announced in a line after the run, and shown as captured
output in the dashboard's report.

## Modules

Fume is structured as three build modules:

 - `client`: all of fume's logic — the subcommands, flags, dispatch and (in time) the
   test-running machinery; published to Maven Central as `dev.propensive:fume-client`
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

A release is cut by tagging, not by make. Bump `fumeVersion` in `build.mill`, merge it, wait for
CI to go green on that commit, and then `git tag -s X.Y.Z && git push --tags`: the tag fires
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
