#!/usr/bin/env bash
#
# Install the Pyrocosm release this repository is built against into the local ivy repository
# (`~/.ivy2/local`, which coursier — and so Mill — consults by default), so a local build resolves
# the same RELEASED jars CI does rather than whatever `make publishLocal` in a pyrocosm checkout
# last left there. It is Soundness's own `sync_releases.py`, pointed at pyrocosm through
# `SOUNDNESS_RELEASE_REPO`, which is exactly what the shared CI workflow runs for each
# `extra_releases` pair. Each released jar carries its POM and ivy.xml under `META-INF/maven/`,
# so the jar is all that is downloaded, and a jar already present with the digest GitHub reports
# is left alone.
#
# The script is fetched from the Soundness release this repository pins, and cached, so the
# network is needed only the first time for a given Soundness version.
#
# Soundness itself is NOT synced here: a Soundness built and published from a local checkout is
# left as it is. Sync it with `make sync-releases` in the soundness repository.
#
# Usage: ./etc/ci/sync-releases.sh [X.Y.Z]    the pinned `pyrocosmVersion` when omitted
#         (or `make sync-releases [VERSION=X.Y.Z]`)
#
# To try a pyrocosm release candidate before it is tagged, run `make sync-staged` in the pyrocosm
# checkout instead: it installs the jars of its `./mill release.stage` under the same version.
#
# Environment: PYROCOSM_RELEASE_REPO=owner/repo (default propensive/pyrocosm); GITHUB_TOKEN, if
# set, lifts the unauthenticated API rate limit; IVY_LOCAL overrides the destination.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

REPO="${PYROCOSM_RELEASE_REPO:-propensive/pyrocosm}"
SOUNDNESS=$(grep 'val soundnessVersion' build.mill | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | tail -1)
PINNED=$(grep 'val pyrocosmVersion' build.mill | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | tail -1)

if [[ -z "$SOUNDNESS" || -z "$PINNED" ]]; then
  echo "sync-releases: could not read the version pins from build.mill" >&2; exit 1
fi

SCRIPT="${TMPDIR:-/tmp}/sync_releases-$SOUNDNESS.py"

if [[ ! -f "$SCRIPT" ]]; then
  url="https://raw.githubusercontent.com/propensive/soundness/$SOUNDNESS/etc/ci/sync_releases.py"
  if ! curl -fsSL -o "$SCRIPT" "$url"; then
    rm -f "$SCRIPT"
    echo "sync-releases: could not fetch $url" >&2; exit 1
  fi
fi

exec env SOUNDNESS_RELEASE_REPO="$REPO" python3 "$SCRIPT" "${1:-$PINNED}"
