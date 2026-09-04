#!/bin/sh
# Generates the fume install script for a given release, embedding per-platform digests.
VERSION=$1
BASE="https://github.com/propensive/fume/releases/download/$VERSION"
H() { gh api "repos/propensive/fume/releases/tags/$VERSION" --jq ".assets[] | select(.name==\"$1\") | .digest" | sed 's/sha256://'; }
cat <<EOF
#!/bin/sh

# The fume installer, served from https://fume.propensive.dev/ for:
#
#     curl -fsSL https://fume.propensive.dev/ | sh
#
# Detects the operating system and CPU architecture, downloads the matching \`fume\`
# executable from the GitHub release, verifies its SHA-256 against the digest embedded
# below, and installs it as \`fume\` in ~/.local/bin (or \$FUME_INSTALL_DIR). POSIX shell
# only; no stdin is read, so piping from curl is safe.
#
# Generated for fume $VERSION by etc/ci/release.sh; the digests are per-release.

set -e

version="$VERSION"
base="$BASE"

case "\$(uname -s)" in
  Darwin) os=macos ;;
  Linux)  os=linux ;;
  *)      echo "fume: unsupported operating system: \$(uname -s)" >&2
          echo "fume: (on Windows, download \$base/fume-windows-x64.exe)" >&2
          exit 1 ;;
esac

case "\$(uname -m)" in
  x86_64|amd64)  arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *)             echo "fume: unsupported architecture: \$(uname -m)" >&2; exit 1 ;;
esac

label="\$os-\$arch"

case "\$label" in
  linux-x64)   expected=$(H fume-linux-x64) ;;
  linux-arm64) expected=$(H fume-linux-arm64) ;;
  macos-x64)   expected=$(H fume-macos-x64) ;;
  macos-arm64) expected=$(H fume-macos-arm64) ;;
  *)           echo "fume: no executable is published for \$label" >&2; exit 1 ;;
esac

url="\$base/fume-\$label"
dir="\${FUME_INSTALL_DIR:-\$HOME/.local/bin}"
mkdir -p "\$dir"
tmp="\$dir/.fume.download.\$\$"
trap 'rm -f "\$tmp"' EXIT

echo "Downloading fume \$version for \$label..."
if command -v curl >/dev/null 2>&1
then curl -fsSL "\$url" -o "\$tmp"
elif command -v wget >/dev/null 2>&1
then wget -qO "\$tmp" "\$url"
else echo "fume: neither curl nor wget is available" >&2; exit 1
fi

if command -v sha256sum >/dev/null 2>&1
then actual=\$(sha256sum "\$tmp" | cut -d' ' -f1)
elif command -v shasum >/dev/null 2>&1
then actual=\$(shasum -a 256 "\$tmp" | cut -d' ' -f1)
else actual=\$(openssl dgst -sha256 "\$tmp" | sed 's/.* //')
fi

if [ "\$actual" != "\$expected" ]
then
  echo "fume: checksum mismatch for \$url" >&2
  echo "fume:   expected \$expected" >&2
  echo "fume:   received \$actual" >&2
  exit 1
fi

chmod +x "\$tmp"
mv "\$tmp" "\$dir/fume"
trap - EXIT

echo "Installed fume \$version to \$dir/fume"

case ":\$PATH:" in
  *:"\$dir":*) ;;
  *) echo "Note: \$dir is not on your PATH; add it with:"
     echo "    export PATH=\"\$dir:\\\$PATH\"" ;;
esac

echo "The first run fetches fume's dependencies; subsequent runs start instantly."
EOF
