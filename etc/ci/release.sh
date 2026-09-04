#!/usr/bin/env bash
#
# Publish fume to GitHub Releases: the fume-client library jar, then the self-fetching `fume`
# executable built against it. Maven Central is no longer involved (Soundness #1929 switched the
# whole ecosystem to GitHub Releases).
#
# The two assets have a strict order between them — the launcher's repackaged form externalizes
# fume-client by matching its SHA-256 digest against the release's PUBLISHED assets — so the
# release is made in two steps, exactly as it must be consumed:
#
#   1. The release is created (tagging HEAD) with `fume-client-X.Y.Z.jar` alone: the exact bytes
#      the local publish put on the launcher's compile classpath, so the digest GitHub records
#      is the hash Burdock computed at compile time.
#   2. Once GitHub reports the asset's digest, the launcher is assembled and repackaged with
#      `--github propensive/fume` among its hints, the script VERIFIES that fume-client really
#      externalized to this release's URL (aborting before upload if not), and the resulting
#      `fume` executable is added to the same release.
#
# A PUBLISHED release, not a draft: a draft's asset URLs live under an `untagged-…` path that
# changes when the draft is published, which would bake dead URLs into the launcher.
#
# The brief window in which the release exists without its executable is the cost of not needing
# Burdock to accept unverified, anticipated URLs; the release notes are amended at the end.
#
# Usage: ./etc/ci/release.sh X.Y.Z   (or `make release VERSION=X.Y.Z`)
# Requires: `gh` authenticated with push access to propensive/fume.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

REPO="propensive/fume"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 X.Y.Z" >&2; exit 1
fi
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "fatal: $VERSION is not of the form X.Y.Z" >&2; exit 1
fi

# The version is compiled into the client (`fumeVersion` in build.mill and fume_client.scala) and
# is the coordinate the launcher resolves, so a release of anything else would disagree with
# itself. The FUME_VERSION environment override cannot help here: the Mill daemon freezes the
# build script's `sys.env` (see the note in build.mill), so the pin must be edited and committed.
PINNED=$(sed -n 's/.*val fumeVersion = sys.env.getOrElse("FUME_VERSION", "\(.*\)").*/\1/p' build.mill)
if [[ "$PINNED" != "$VERSION" ]]; then
  echo "fatal: build.mill pins fumeVersion=$PINNED, not $VERSION; bump and commit first" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "fatal: the working tree is not clean" >&2; exit 1
fi

# Build the library from scratch and publish it locally: the launcher's compile classpath will
# hold exactly these bytes, so these are the bytes that must be released.
./mill clean fume >/dev/null
./mill fume.client.publishLocal
CLIENT_JAR="$HOME/.ivy2/local/dev.propensive/fume-client/$VERSION/jars/fume-client.jar"
if [[ ! -f "$CLIENT_JAR" ]]; then
  echo "fatal: $CLIENT_JAR was not produced" >&2; exit 1
fi
LOCAL_DIGEST=$(shasum -a 256 "$CLIENT_JAR" | cut -d' ' -f1)

# Step 1: the release, with the library alone. `--target` ties the tag to the commit being
# released even if main moves while the launcher builds.
ASSET_NAME="fume-client-$VERSION.jar"
STAGED=$(mktemp -d)/"$ASSET_NAME"
cp "$CLIENT_JAR" "$STAGED"
gh release create "$VERSION" --repo "$REPO" --target "$(git rev-parse HEAD)" \
  --title "fume $VERSION" --notes "Uploading…" "$STAGED"

# GitHub computes each asset's SHA-256 shortly after upload; Burdock indexes by that digest, so
# wait for it and confirm it matches the local bytes.
for i in $(seq 1 60); do
  DIGEST=$(gh api "repos/$REPO/releases/tags/$VERSION" \
    --jq ".assets[] | select(.name == \"$ASSET_NAME\") | .digest // \"\"" 2>/dev/null || true)
  [[ -n "$DIGEST" ]] && break
  sleep 5
done
if [[ "$DIGEST" != "sha256:$LOCAL_DIGEST" ]]; then
  echo "fatal: released digest '$DIGEST' does not match local sha256:$LOCAL_DIGEST" >&2
  exit 1
fi
echo "released $ASSET_NAME ($DIGEST)"

# Step 2: the launcher, repackaged against the now-published library. `clean` first: the
# launcher's dependency is a fixed coordinate, and Mill's cached resolution would not notice a
# fresh publishLocal under the same version.
./mill clean fume.launcher >/dev/null
./mill fume.launcher.assembly
cp out/fume/launcher/assembly.dest/out.jar fume.jar
java -cp fume.jar soundness.repackage \
  --github propensive/fume,propensive/soundness,propensive/proscala | tee /tmp/fume-release-repackage.log

# The whole point of the two-step dance: refuse to ship an executable that quietly inlined the
# library instead of referring to the release.
if ! grep -q "propensive/fume/releases/download/$VERSION/$ASSET_NAME" /tmp/fume-release-repackage.log
then
  echo "fatal: fume-client did not externalize against this release; not uploading" >&2
  exit 1
fi

# One executable per supported platform, all assembled here: producing an Ethereal executable
# is byte-patching a bare runner stub and appending the (platform-independent) repackaged JAR,
# not compilation, so `-Dbuild.target` cross-"builds" every platform from this machine. The
# stubs are fetched from the Soundness `runners` release and digest-verified by the assembler.
PLATFORMS="linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64"
DIST=$(mktemp -d)
for platform in $PLATFORMS; do
  ext=""; [[ "$platform" == windows-* ]] && ext=".exe"
  java "-Dbuild.executable=$DIST/fume-$platform$ext" "-Dbuild.target=$platform" -jar fume.jar
done
gh release upload "$VERSION" --repo "$REPO" "$DIST"/fume-*

# The `fume` polyglot bootstrap: a small any-shell script (rename to fume.bat/fume.ps1 on
# Windows) embedding each executable's URL and checksum, which downloads the right one,
# verifies it, replaces itself and re-invokes. Built by ziggurat's `Xeq.dispatcher`, run from
# the fat launcher assembly (ziggurat-core is among fume's dependencies for exactly this).
# The manifest needs the executables' digests, which GitHub computes asynchronously — poll as
# for the library. If the pinned Soundness predates `dispatcher`, warn and release without.
MANIFEST=$(mktemp)
for i in $(seq 1 60); do
  gh api "repos/$REPO/releases/tags/$VERSION" --jq \
    '.assets[] | select((.name | startswith("fume-")) and (.name | endswith(".jar") | not))
     | .name + "\t" + .browser_download_url + "\t" + (.digest // "" | sub("sha256:"; ""))' \
    > "$MANIFEST"
  grep -qv $'\t$' "$MANIFEST" && ! grep -q $'\t$' "$MANIFEST" && break
  sleep 5
done
sed -i.bak 's/^fume-//; s/\.exe\t/\t/' "$MANIFEST"

SNIPPET=""
if java -cp out/fume/launcher/assembly.dest/out.jar ziggurat.Xeq dispatcher "$DIST/fume" "$MANIFEST"
then
  gh release upload "$VERSION" --repo "$REPO" "$DIST/fume"

  # The install one-liner for the notes: ziggurat's minimal bootstrap (lib/ziggurat/etc/launch
  # — 106 bytes of POSIX shell, base64-armored), pointed at the polyglot script above, so the
  # three layers compose: one-liner -> dispatcher script -> native executable, each download
  # SHA-256-verified. The armored payload is constant; only the URL and hash vary per release.
  SCRIPT_DIGEST=""
  for i in $(seq 1 60); do
    SCRIPT_DIGEST=$(gh api "repos/$REPO/releases/tags/$VERSION" \
      --jq '.assets[] | select(.name == "fume") | .digest // ""' | sed 's/^sha256://')
    [[ -n "$SCRIPT_DIGEST" ]] && break
    sleep 5
  done
  if [[ -n "$SCRIPT_DIGEST" ]]; then
    SNIPPET=$(printf 'Install (any POSIX shell):\n\n```sh\nopenssl base64 -d <<EOF | sh -s -- https://github.com/%s/releases/download/%s/fume %s\nZj1gbWt0ZW1wYDtjdXJsIC1zTG8gJGYgJDF8fHdnZXQgLXFPICRmICQxO2Nhc2UgYG9wZW5zc2wg\nZGdzdCAtc2hhMjU2ICRmYCBpbiAqJDIpY2htb2QgK3ggJGY7ZXhlYyAkZjtlc2Fj\nEOF\n```\n\n' "$REPO" "$VERSION" "$SCRIPT_DIGEST")
  fi
else echo "warning: ziggurat.Xeq has no dispatcher at the pinned Soundness; released without the bootstrap script" >&2
fi

# The installer served from https://fume.propensive.dev/ (`curl -fsSL … | sh`): plain POSIX
# shell, embedding this release's per-platform digests, generated once they are all known and
# attached to the release as `install.sh` — the domain serves (or redirects to) that asset.
etc/ci/generate-install.sh "$VERSION" > "$DIST/install.sh"
gh release upload "$VERSION" --repo "$REPO" "$DIST/install.sh"

gh release edit "$VERSION" --repo "$REPO" --notes \
  "${SNIPPET}The \`fume\` polyglot bootstrap (a small any-shell script — rename to \`fume.bat\` or \`fume.ps1\` on Windows — which downloads the right executable below, verifies its checksum, replaces itself and re-invokes), one \`fume\` executable per platform, and the \`fume-client\` library each externalizes, resolving further dependencies from the Soundness and proscala releases and Maven Central on first run."
echo "release $VERSION complete: $ASSET_NAME + $(cd "$DIST" && echo fume*)"
