#!/usr/bin/env bash
# Build Stele and link `stele` onto your PATH.
# In a checkout:   ./install.sh
# Standalone (clones first):  STELE_REPO=<git-url> bash install.sh
set -euo pipefail

if [ -f gradlew ] && grep -q 'rootProject.name = "stele"' settings.gradle.kts 2>/dev/null; then
  repo="$(pwd)"
else
  : "${STELE_REPO:?set STELE_REPO=<git-url> (the repo is not published yet)}"
  echo "Cloning Stele..."
  git clone --depth 1 "$STELE_REPO" stele
  cd stele
  repo="$(pwd)"
fi

command -v java >/dev/null 2>&1 || { echo "Need a JDK 17+ on PATH."; exit 1; }

echo "Building Stele (one-time)..."
./gradlew :cli:installDist

bin="$repo/cli/build/install/stele/bin"
[ -x "$bin/stele" ] || { echo "Build did not produce $bin/stele"; exit 1; }

dest="${STELE_BIN_DIR:-$HOME/.local/bin}"
mkdir -p "$dest"
ln -sf "$bin/stele" "$dest/stele"
echo "Linked: $dest/stele -> $bin/stele"

case ":$PATH:" in
  *":$dest:"*) ;;
  *) echo "Add this to your shell profile:  export PATH=\"$dest:\$PATH\"" ;;
esac

echo "Done. Try:  stele --help"
