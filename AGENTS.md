# Agent instructions for fume

Read `.github/readme.md` first for what fume is and how it is built. This file adds the rules an
agent must follow when working in this repository.

## Dependencies are pinned in `etc/refs`

Fume compiles against Soundness and Pyrocosm, both pinned in `etc/refs`. Its own library
(`fume-client`) is versioned by `fumeVersion` in `build.mill`, which the launcher resolves as a
published coordinate; that value is not a pin and stays where it is.

`etc/refs` is tab-separated, one upstream per line: `repository`, `version`, and for a snapshot
the `commit` it was built from. A version `X.Y.Z` is a GitHub Release. A version
`X.Y.Z-<12 hex>` is a **snapshot**: an unreleased upstream build, published from that repository
by `make snapshot` as the pre-release tagged `snapshot-<hex>`, where the hex is the start of the
filtered tree hash of its commit (its tree minus what `.dockerignore` excludes) and `X.Y.Z` the
version it declares for its next release. The build reads the file through the `deps` object in
`build.mill`; there is no version to edit in `build.mill` or in `.github/workflows/ci.yml`.

### Rules

1. **Never install an upstream by hand into `~/.ivy2/local`** (`publishLocal` in a sibling
   checkout) and leave the pin pointing at a release: the build then compiles against bytes CI
   cannot see. If a change needs an unreleased upstream, publish a snapshot there (`make
   snapshot` in a *pushed*, clean checkout of the commit) and pin the line it prints.
2. Run `make sync-deps` after editing `etc/refs`, and whenever a build fails to resolve a
   `dev.propensive` coordinate. It installs every pin, transitively, from GitHub Releases —
   exactly what the shared CI workflow does — and repairs a jar whose digest differs. For a
   snapshot nobody has published, it builds the pinned commit from a sibling checkout at
   `../<name>` (or `$PROPENSIVE_WORK/<name>`).
3. A snapshot pin is a **debt** the PR description should mention: the upstream has to be
   released, and the pin bumped to that release, before this repository can be released.
   `make release` runs `deps.py check` and refuses while any pin, transitively, is a snapshot.
4. When bumping a pin, bump only `etc/refs`. If the new version breaks the build, the PR that
   fixes the breakage carries the bump; do not split them.
5. Do not edit `etc/shared` or `etc/github-ref` casually: `etc/github-ref` pins the commit of
   propensive/.github whose scripts (`sync-deps.sh`, `snapshot.sh`, `deps.py`,
   `release-launcher.sh`, …) run here, and a bump is a deliberate one-line change. Set
   `PROPENSIVE_GITHUB=/path/to/a/.github/checkout` to test a change to the scripts themselves.

The whole flow, and the scripts, are documented in the README of
[propensive/.github](https://github.com/propensive/.github).
