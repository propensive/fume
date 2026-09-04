# Build the invocation-point `launcher` module as a plain (clean, no shell-preamble) assembly JAR.
# NOTE: `launcher` depends on fume-client as a PUBLISHED Maven Central coordinate, so this only
# resolves once `make release VERSION=X.Y.Z` (below) has put that jar on Central. Until then,
# build/run the library directly with `make run`.
assembly:
	mill fume.launcher.assembly

# Publish fume's library module (fume-client) to Maven Central. Signed, via the same Sonatype
# Central flow as Soundness — see etc/ci/release.sh. Run this BEFORE `make fume`, and wait for
# Central + deps.dev to index the jar, so the repackager can externalize it.
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
# Two publication homes are consulted: Maven Central (hashes resolved via deps.dev) for the
# third-party dependencies, and — via the `--github` hint — the release assets of the Soundness
# repository, whose per-component jars carry SHA-256 digests the repackager matches against the
# classpath. The Soundness jars synced into `~/.ivy2/local` are the release assets byte-for-byte,
# so every component externalizes; fume-client (until it has a published home of its own) and the
# proscala toolchain jars (released only inside a tarball, which carries no per-jar digest) are
# inlined. Set GITHUB_TOKEN to lift the API rate limit; the requests are otherwise anonymous.
fume.jar: assembly
	cp out/fume/launcher/assembly.dest/out.jar fume.jar
	java -cp fume.jar soundness.repackage --github propensive/soundness

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
# (0.1.0), so Mill's cached resolution does not notice a fresh publishLocal under the same
# version, and the assembly silently bundles the previous jar.
run: publishLocal
	mill clean fume.launcher
	mill show fume.launcher.assembly
	java -jar out/fume/launcher/assembly.dest/out.jar

# Compile and run the test suite.
test:
	mill fume.test.assembly
	java -cp out/fume/test/assembly.dest/out.jar fume.Tests

dev:
	mill -w fume.client.compile

.PHONY: assembly release publishLocal run test dev install
