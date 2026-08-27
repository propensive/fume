#!/usr/bin/env bash
#
# Publish fume's library module (fume-client) as a signed release to
# Maven Central from this machine. Modelled on Soundness's etc/ci/release.sh, using the same
# `mill.javalib.SonatypeCentralPublishModule/publishAll` flow and the same four secrets read from
# the macOS keychain.
#
# Only the one PublishModule is released — `__.publishArtifacts` selects exactly it (the
# `launcher` and `test` modules are not PublishModules). The `launcher` executable is built and
# repackaged SEPARATELY, AFTER this release, once the library is indexed on Maven Central
# (see the Makefile `fume` target and the readme) — burdock can only externalize the library
# once its exact jar bytes are resolvable from Central.
#
# Usage: ./etc/ci/release.sh X.Y.Z   (or `make release VERSION=X.Y.Z`)
#
# Required secrets (read from the macOS keychain — see the SECRETS section below):
#   - soundness.sonatype.username
#   - soundness.sonatype.password
#   - soundness.pgp.secret.base64
#   - soundness.pgp.passphrase

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 X.Y.Z" >&2; exit 1
fi
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "release: VERSION must be X.Y.Z (got '$VERSION')" >&2; exit 1
fi

# The build reads the release version from FUME_VERSION (see `settings.fumeVersion` in build.mill),
# so the published coordinates match the tag being cut.
export FUME_VERSION="$VERSION"

# ---------------------------- GUARDS ----------------------------

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "release: working tree is dirty; commit or stash first" >&2; exit 1
fi

HEAD_SHA=$(git rev-parse HEAD)

if git rev-parse "refs/tags/$VERSION" >/dev/null 2>&1; then
  echo "release: tag $VERSION already exists locally" >&2; exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/$VERSION" >/dev/null 2>&1; then
  echo "release: tag $VERSION already exists on origin" >&2; exit 1
fi

# ---------------------------- SECRETS ----------------------------
#
# Default: macOS keychain (the same entries the Soundness release uses). Set them once with:
#   security add-generic-password -a soundness-release \
#     -s soundness.sonatype.username -w 'YOUR_USERNAME'
#   security add-generic-password -a soundness-release \
#     -s soundness.sonatype.password -w 'YOUR_PASSWORD'
#   security add-generic-password -a soundness-release \
#     -s soundness.pgp.secret.base64 -w 'BASE64_PGP_SECRET'
#   security add-generic-password -a soundness-release \
#     -s soundness.pgp.passphrase    -w 'YOUR_PASSPHRASE'

read_secret() {
  security find-generic-password -a soundness-release -s "$1" -w 2>/dev/null \
    || { echo "release: missing keychain entry '$1'" >&2; exit 1; }
}

export MILL_SONATYPE_USERNAME=$(read_secret soundness.sonatype.username)
export MILL_SONATYPE_PASSWORD=$(read_secret soundness.sonatype.password)
export MILL_PGP_SECRET_BASE64=$(read_secret soundness.pgp.secret.base64)
export MILL_PGP_PASSPHRASE=$(read_secret soundness.pgp.passphrase)

# ---------------------------- PUBLISH ----------------------------

# Tag locally first so PGP can sign the tag object. Don't push the tag yet — if Mill publish fails,
# we'd be left with a published tag for a non-released version.
git tag -s "$VERSION" -m "Version $VERSION"

if ! mill mill.javalib.SonatypeCentralPublishModule/publishAll \
       --publishArtifacts __.publishArtifacts \
       --shouldRelease true \
       --bundleName "dev.propensive-fume:$VERSION"; then
  echo "release: publish failed; removing local tag $VERSION" >&2
  git tag -d "$VERSION" >/dev/null
  exit 1
fi

# Publish succeeded — push the tag.
git push origin "refs/tags/$VERSION"

echo "release: fume $VERSION published to Maven Central and tag pushed to origin."
echo "release: once Central + deps.dev have indexed the jars, build the launcher with 'make fume'."
