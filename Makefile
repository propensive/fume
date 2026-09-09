# Build the invocation-point `launcher` module as a plain (clean, no shell-preamble) assembly JAR.
# NOTE: `launcher` depends on fume-client as a PUBLISHED coordinate, resolved from ~/.ivy2/local
# (a `make run`/`make fume` publishes it there) and externalized against the GitHub release
# during `make release`.
assembly:
	mill fume.launcher.assembly

# Publish fume to GitHub Releases: the fume-client jar first, then — once its digest is
# indexed — the repackaged `fume` executable, added to the same release. See etc/ci/release.sh
# for the two-step ordering and its verification.
release:
	./etc/ci/release.sh $(VERSION)

# Publish the library to the local ~/.ivy2 (config sanity check only — local bytes differ from
# Central, so burdock will NOT externalize a locally-published copy).
publishLocal:
	mill fume.client.publishLocal

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

fume: fume.jar
	java -Dbuild.executable=fume -jar fume.jar

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

# Compile and run the test suite.
test:
	mill fume.test.assembly
	java -cp out/fume/test/assembly.dest/out.jar fume.runTests

dev:
	mill -w fume.client.compile

.PHONY: assembly release publishLocal run test dev install
