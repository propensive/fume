# Fume

__Fume__ is the test runner for the [Soundness](https://soundness.dev/) ecosystem. It runs
[Probably](https://soundness.dev/probably/) tests and Sedentary benchmarks from a _prebuilt_
classpath — something else does the compiling — and it discovers suites exclusively through the
`META-INF/services/probably.Suite` index which the beneficence compiler plugin writes into every
jar it compiles.

Fume runs as an [Ethereal](https://soundness.dev/ethereal/) daemon, so invocations after the
first are fast, and uses [Exoskeleton](https://soundness.dev/exoskeleton/) for its command line,
tab-completions and manpage.

## Usage

```sh
fume run -c out.jar                     # run everything on the classpath
fume run -c out.jar --bench             # only benchmarks
fume run -c out.jar json/* 'N<=64'      # a path glob and an axis constraint
fume list -c out.jar                    # enumerate tests without running them
fume watch -c out.jar                   # rerun whenever the jar changes
fume install                            # install tab-completions and the manpage
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
property, a `FUME_`-prefixed environment variable (`FUME_CLASSPATH=…`), and finally the
project's configuration file.

## Configuration file

A project configures fume with a [TEL](https://soundness.dev/tel/) document at
`.fume/config.tel`, where the `.fume` directory sits in the project root (typically alongside
`.git`); fume finds it from the current directory or any ancestor, so it can be invoked from
anywhere inside the project. Because fume is a daemon, the parsed file is cached, but its
timestamp and size are checked on every invocation, so edits take effect immediately.

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

Note that `Suite#invoke` (and the `systemStdio`-based suite output that lets an in-process host
redirect it) is a pending Soundness change, developed on the `fume-support` branch, built from
the worktree at `~/work/worktrees/soundness/fume`.

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

To cut a release and build the self-fetching native launcher:
```sh
make release VERSION=0.2.0   # signed publish of fume-client to Maven Central
# …wait for Central and deps.dev to index the jar…
make fume                    # assemble, repackage with Burdock, emit the `fume` executable
make install                 # copy it to ~/.local/bin
```

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
