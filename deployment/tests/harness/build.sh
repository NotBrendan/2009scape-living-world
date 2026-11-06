#!/bin/bash
# Env: see env.sh (WORLD, HARNESS, JAVAC, JAVA, FARM_LAYOUT).
# usage: build.sh <outdir> [srcdir] [test sources...]   (default srcdir: <project>/deployment/addons-src)
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
OUT=${1:-$ME/qbuild}; SRC=${2:-"$PROJECT/deployment/addons-src"}; shift 2 2>/dev/null
rm -rf "$OUT"; mkdir -p "$OUT"
EXTRA=(); for t in "$@"; do EXTRA+=("$(cygpath -w "$(cd "$(dirname "$t")" && pwd)/$(basename "$t")")"); done
cd "$SRC" || exit 1
"$JAVAC" --release 11 -nowarn -encoding UTF-8 -cp "$(cygpath -w "$ME/qt/game/server.jar")" -d "$(cygpath -w "$OUT")" content/custom/*.java core/game/ge/*.java "${EXTRA[@]}" 2>&1 | grep -v '^Note:' | head -60
echo "javac exit: ${PIPESTATUS[0]}"
cp content/custom/artisan-recipes.json content/custom/bargain-lines.json "$OUT/content/custom/" 2>/dev/null
if [ -f content/custom/afk-spells.json ]; then cp content/custom/afk-spells.json "$OUT/content/custom/"; else echo "note: no afk-spells.json in this srcdir (Batch 25 only), not copied"; fi
echo "classes: $(find "$OUT" -name '*.class' | wc -l)"
# trial-only listener shim (127.0.0.1 + layout port), kept out of the build itself
if [ ! -f "$ME/shim/core/net/NioReactor.class" ] || [ "$H_DIR/shim/core/net/NioReactor.java" -nt "$ME/shim/core/net/NioReactor.class" ]; then
    mkdir -p "$ME/shim"; "$JAVAC" --release 11 -nowarn -cp "$(cygpath -w "$ME/qt/game/server.jar")" -d "$(cygpath -w "$ME/shim")" "$(cygpath -w "$H_DIR/shim/core/net/NioReactor.java")" 2>&1 | grep -v '^Note:'
fi
