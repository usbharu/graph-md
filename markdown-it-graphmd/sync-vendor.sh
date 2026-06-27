#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$ROOT/core/build/dist/js/productionLibrary"
DEST_DIR="$SCRIPT_DIR/vendor"

if [ ! -f "$SRC_DIR/graph-md-core.js" ]; then
  echo ">> Building core Kotlin/JS production library..."
  "$ROOT/gradlew" -p "$ROOT" :core:jsNodeProductionLibraryDistribution --quiet
fi

mkdir -p "$DEST_DIR"

# Force Node/bundlers to treat the Kotlin UMD output as CommonJS so that
# `import core from "./vendor/graph-md-core.js"` resolves to module.exports.
if [ ! -f "$DEST_DIR/package.json" ]; then
  printf '{\n  "private": true,\n  "type": "commonjs"\n}\n' > "$DEST_DIR/package.json"
fi

cp "$SRC_DIR/graph-md-core.js" "$DEST_DIR/graph-md-core.js"
cp "$SRC_DIR/kotlin-kotlin-stdlib.js" "$DEST_DIR/kotlin-kotlin-stdlib.js"
cp "$SRC_DIR/kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js" "$DEST_DIR/kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js"

echo ">> vendor synced to $DEST_DIR"
