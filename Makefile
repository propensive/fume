# Build the invocation-point `launcher` module as a plain (clean, no shell-preamble) assembly JAR.
# NOTE: `launcher` depends on fume-client as a PUBLISHED coordinate, resolved from ~/.ivy2/local
# (a `make run`/`make fume` publishes it there) and externalized against the GitHub release
# when it is released.
assembly:
	mill fume.launcher.assembly

# Releases are cut by tagging, not by make. Bump `fumeVersion`, merge it, and then `git tag -s
# X.Y.Z && git push --tags`: the tag fires .github/workflows/release.yml, which runs the shared
# release.sh in propensive/.github. That gates on a signed tag, on CI already being green on that
# very commit, and on every pin being a release; publishes the library jars; repackages the
# executables against them; and generates the notes. If anything fails, the release and the tag
# are both deleted, so a retry is `git tag -d X.Y.Z && git tag -s X.Y.Z && git push --tags`. What
# this repository needs beyond the common path is declared in etc/release. This target survives
# only to say so.
release:
	@echo "Releases are triggered by tags, not by make. Bump fumeVersion, merge it, then:" >&2
	@echo "" >&2
	@echo "    git tag -s X.Y.Z && git push --tags" >&2
	@echo "" >&2
	@echo "See propensive/.github." >&2
	@exit 1

# Publish the library to the local ~/.ivy2 (config sanity check only — local bytes differ from
# Central, so burdock will NOT externalize a locally-published copy).
publishLocal:
	mill fume.relay.publishLocal fume.client.publishLocal

# Repackage the launcher assembly into a self-fetching launcher with Burdock. The
# `burdock.externalize` macro wrapping `fume.fume` (in src/launcher/fume_launcher.scala) has
# already embedded `META-INF/burdock.deps` at compile time; running the repackager rewrites the JAR
# in place so published dependencies become on-demand `Burdock-Require` URLs and unpublished ones
# are inlined from `~/.cache/burdock`.
#
# Three publication homes are consulted: Maven Central (hashes resolved via deps.dev) for the
# third-party dependencies, and — via the `--github` hints — the release assets of the Soundness
# and proscala repositories, whose per-jar SHA-256 digests the repackager matches against the
# classpath. The Soundness jars synced into `~/.ivy2/local` are the release assets byte-for-byte,
# and the proscala release publishes the same jars its tarball carries, so both the components
# and the fork toolchain externalize; only fume-client (until it has a published home of its own)
# is inlined. Set GITHUB_TOKEN to lift the API rate limit; the requests are otherwise anonymous.
fume.jar: assembly
	cp out/fume/launcher/assembly.dest/out.jar fume.jar
	java -cp fume.jar soundness.repackage --github propensive/fume,propensive/pyrocosm,propensive/soundness,propensive/proscala

# Package the repackaged JAR as a native executable for this machine with the pinned `xeq` builder
# script (fetched into dist/xeq and verified against etc/xeq.tsv).
fume: fume.jar xeq-fetch
	dist/xeq build --jar fume.jar --out fume

# Fetch the pinned `xeq` builder script into dist/xeq.
xeq-fetch:
	./etc/shared xeq-fetch.sh

install: fume
	cp fume ${HOME}/.local/bin/

# Run fume locally WITHOUT a Central release: publish the library to ~/.ivy2 so the launcher's
# published-coordinate dep resolves locally, assemble the launcher, and run it directly (no burdock
# repackage, so the local library jar is simply bundled — externalization needs the real Central
# jar and happens only in `make fume`).
#
# `clean fume.launcher` first: the launcher's dependency on fume-client is a fixed COORDINATE
# (e.g. 0.2.0), so Mill's cached resolution does not notice a fresh publishLocal under the same
# version, and the assembly silently bundles the previous jar.
run: publishLocal
	mill clean fume.launcher
	mill show fume.launcher.assembly
	java -jar out/fume/launcher/assembly.dest/out.jar

# Compile and run the test suite with the RELEASED fume pinned in etc/tools (`make tools`
# installs it), which discovers the suite from the assembly's META-INF/services/probably.Suite
# index. CI runs the same command. fume testing fume with a release of itself is the tool
# rule at work: what a repository runs is a release, never the build under test. `make
# test-plain` drives `Tests.invoke` in-process without fume.
test:
	mill fume.test.assembly
	fume run -c out/fume/test/assembly.dest/out.jar $(TESTS)

test-plain:
	mill fume.test.assembly
	java -cp out/fume/test/assembly.dest/out.jar fume.runTests

# Install every library pinned in etc/refs — releases and snapshots alike, transitively —
# into the local ivy repository, as CI does, so the build resolves exactly the pinned jars rather
# than whatever a sibling checkout's `publishLocal` last installed under the same version. A
# snapshot not yet on GitHub is built from the sibling checkout named by the pin's commit.
sync-deps:
	./etc/shared sync-deps.sh

# Check every source against Consequent Style and the project's own rules with flair (the
# release pinned in etc/tools; `make tools` installs it), as configured in
# .pyrocosm/flair/config.tel. Findings are warnings and the count is not yet zero, so CI does
# not run this; PATHS restricts the check to files beneath them.
check:
	flair check $(PATHS)

# Install the commands pinned in etc/tools (fume) through their releases' installers.
tools:
	./etc/shared tools.sh

# Publish HEAD's libraries as a snapshot — a `snapshot-<hex>` pre-release named by the filtered
# tree of the commit, at version `<fumeVersion>-<hex>` — for a dependent repository to pin in
# its etc/refs before the next release. `LOCAL=1` stages and installs without publishing.
# The last line printed is the pin. See snapshot.sh in propensive/.github.
snapshot:
	./etc/shared snapshot.sh fume "$$(sed -n 's/.*val fumeVersion = "\(.*\)".*/\1/p' build.mill)"

# Delete snapshot pre-releases older than DAYS (default 60) days.
snapshot-prune:
	./etc/shared snapshot-prune.sh fume $(DAYS)

dev:
	mill -w fume.client.compile

.PHONY: check xeq-fetch sync-deps tools snapshot snapshot-prune assembly release publishLocal run test test-plain dev install
