#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$ROOT/lsp/build/install/lsp"
DEST_DIR="$SCRIPT_DIR/server"

if [ ! -x "$SRC_DIR/bin/lsp" ] && [ ! -f "$SRC_DIR/bin/lsp.bat" ]; then
  echo ">> Building Kotlin LSP distribution..."
  "$ROOT/gradlew" -p "$ROOT" :lsp:installDist --quiet
fi

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"
cp -R "$SRC_DIR/." "$DEST_DIR/"

echo ">> LSP server bundled to $DEST_DIR"
