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
   The release runs `deps.py check` and refuses while any pin, transitively, is a
   snapshot.
4. When bumping a pin, bump only `etc/refs`. If the new version breaks the build, the PR that
   fixes the breakage carries the bump; do not split them.
5. Do not edit `etc/shared` or `etc/github-ref` casually: `etc/github-ref` pins the commit of
   propensive/.github whose scripts (`sync-deps.sh`, `snapshot.sh`, `deps.py`,
   `release.sh`, …) run here, and a bump is a deliberate one-line change. Set
   `PROPENSIVE_GITHUB=/path/to/a/.github/checkout` to test a change to the scripts themselves.

### Releasing

A release is cut by tagging, and by nothing else:

```sh
git tag -s X.Y.Z && git push --tags
```

Bump `fumeVersion` in `build.mill` and merge that first; the tag then fires
`.github/workflows/release.yml`, which runs the shared `release.sh` in
propensive/.github. Never publish by hand, and never create a release or
upload an asset with `gh`: the script exists so that every release is made the same way.

It gates before it publishes — the tag must be signed and verified, CI must *already* be green
on that exact commit (the release does not re-run the suite), and every pin must be a published
release — and if a later step fails it deletes the release **and** the tag from origin,
so the retry is `git tag -d X.Y.Z && git tag -s X.Y.Z && git push --tags`.

What this repository needs beyond the common path is declared in `etc/release`, one
`key value` line each. The release notes are generated: the **Changes** section is built from
the body of every pull request merged since the previous tag, which is what
`pull_request_template.md` asks each PR for — so a PR whose body is empty or addressed to
reviewers rather than to users degrades the next release's notes. A hand-written overview can be
added as `doc/notes/<version>.md`, which is optional and ungated.

### Tools are not dependencies

What a repository *runs* (fume, for most of them; fume itself runs its tests in-process) is pinned in `etc/tools`, not in
`etc/refs`. A tool is always a release, never a snapshot; it is not walked transitively and does
not gate a release, because a release of it exists by definition. That distinction is what keeps
the release graph free of cycles (Soundness runs flair, flair depends on Pyrocosm, Pyrocosm
depends on Soundness). `make tools` installs the pinned commands. Never pin a tool in `etc/refs`
to get an unreleased build of it: release the tool instead.

The whole flow, and the scripts, are documented in the README of
[propensive/.github](https://github.com/propensive/.github).
