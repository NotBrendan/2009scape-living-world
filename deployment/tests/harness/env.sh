#!/bin/bash
# Sourced by build.sh, run.sh, stop.sh and run-test.sh (batch 18).
# Env: WORLD=wNN picks a farm world from layout.json (HARNESS = <worlds>/wNN, world id and game port from the layout).
#      HARNESS (default $HOME/rs-harness) as before. JAVAC and JAVA are paths to the binaries.
#      FARM_LAYOUT overrides where layout.json is looked for (default: <farm root>/farm/layout.json, found from this folder).
H_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVAC="${JAVAC:-/c/Program Files/Java/jdk-24/bin/javac}"
JAVA="${JAVA:-/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot/bin/java}"
# Linux (cloud session, batch 20): no cygpath, ':' classpath, PATH java, /proc/net/tcp instead of Windows netstat. Windows is unchanged.
CPS=';'
if ! command -v cygpath > /dev/null 2>&1; then
    CPS=':'
    cygpath() { case "$1" in -u|-w) shift;; esac; printf '%s\n' "$1"; }
    [ -x "$JAVAC" ] || JAVAC="$(command -v javac)"; [ -x "$JAVA" ] || JAVA="$(command -v java)"
fi
# port_listening PORT: 0 when something listens on PORT. port_public PORT: 0 when it listens beyond 127.0.0.1.
port_rows() { awk -v p="$(printf '%04X' "$1")" '$4=="0A" { split($2,a,":"); if (a[2]==p) print a[1] }' /proc/net/tcp /proc/net/tcp6 2>/dev/null; }
port_listening() { if [ "$CPS" = ';' ]; then netstat -ano | grep LISTENING | grep -q ":$1 "; else [ -n "$(port_rows "$1")" ]; fi; }
port_public() { if [ "$CPS" = ';' ]; then netstat -ano | grep LISTENING | grep ":$1 " | grep -vq '127.0.0.1:'; else port_rows "$1" | grep -vqE '^(0100007F|0000000000000000FFFF00000100007F|00000000000000000000000001000000)$'; fi; }
LAYOUT="${FARM_LAYOUT:-$(cd "$H_DIR/../../../.." && pwd)/farm/layout.json}"
lj() { [ -f "$LAYOUT" ] && sed -n "s/^[[:space:]]*\"$1\":[[:space:]]*\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}[[:space:]]*$/\1/p" "$LAYOUT" | head -1 | sed 's/\\\\/\\/g'; }
upath() { [ -n "$1" ] && cygpath -u "$1"; }
if [ -f "$LAYOUT" ]; then
    PROJECT="$(upath "$(lj project)")"; FARM_WORLDS="$(upath "$(lj worlds)")"; FARM_RESULTS="$(upath "$(lj results)")"
    FARM_SCENARIOS="$(upath "$(lj scenarios)")"; FARM_PRISTINE="$(upath "$(lj pristine)")"; FARM_SEED="$(upath "$(lj liveSeed)")"
fi
PROJECT="${PROJECT:-$(cd "$H_DIR/../../.." && pwd)}"
if [ -n "${WORLD:-}" ] && [ -n "${FARM_WORLDS:-}" ]; then
    NN=$((10#${WORLD#w})); HARNESS="$FARM_WORLDS/$WORLD"
    WORLD_ID=$(( $(lj worldIdBase) + NN )); GAME_PORT=$(( $(lj gamePortBase) + NN * $(lj portStride) ))
fi
ME="${HARNESS:-$HOME/rs-harness}" # a folder holding qt/ (a copy of the trial world: game/ with server.jar, test.conf) and the pristine data copies
