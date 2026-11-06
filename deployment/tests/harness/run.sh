#!/bin/bash
# usage: run.sh <logname> <builddir> [java -D flags...]   (stop with: stop.sh)   Env: see env.sh. KEEP=1 skips the data reset.
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
LOG=$1; B=${2:-$ME/qbuild}; shift 2
# one server per world: a second one would share the console tail and the SQLite lock (batch 18 spare PC, 05:30 UTC)
if [ -n "$GAME_PORT" ] && port_listening "$GAME_PORT"; then echo "run.sh: port $GAME_PORT already listening, a server is still up on this world; refusing" >&2; exit 1; fi
: > "$ME/qt/console.txt"
if [ -z "$KEEP" ]; then rm -rf "$ME/qt/game/data/eco" "$ME/qt/game/data/quests"; cp -r "$ME/eco-pristine" "$ME/qt/game/data/eco"; cp -r "$ME/quests-pristine" "$ME/qt/game/data/quests"; cp "$ME/smartbots-pristine.json" "$ME/qt/game/data/smartbots.json"; fi
# survey data per run (batch 18): SEED=live copies the layout's liveSeed (data/survey + eco traps), SEED=<dir> a folder holding data/;
# otherwise $ME/survey-pristine when it exists, so no run inherits the last one's survey files. KEEP=1 skips this too.
if [ -z "$KEEP" ]; then
    SD=""; [ "$SEED" = "live" ] && SD="$FARM_SEED"; [ -n "$SEED" ] && [ "$SEED" != "live" ] && SD="$SEED"
    if [ -n "$SD" ] && [ -d "$SD/data/survey" ]; then rm -rf "$ME/qt/game/data/survey"; cp -r "$SD/data/survey" "$ME/qt/game/data/survey"; [ -d "$SD/data/eco" ] && cp "$SD/data/eco/"* "$ME/qt/game/data/eco/"
    elif [ -n "$SEED" ]; then echo "run.sh: seed $SEED has no data/survey" >&2; exit 1
    elif [ -d "$ME/survey-pristine" ]; then rm -rf "$ME/qt/game/data/survey"; cp -r "$ME/survey-pristine" "$ME/qt/game/data/survey"; fi
fi
[ -z "$KEEP" ] && rm -rf "$ME/qt/game/data/reports" # batch 19a: the stuck report's counts must not carry over from the last run
mkdir -p "$ME/qt/game/data/players" "$ME/qt/game/data/logs"
[ -n "$WORLD_ID" ] && sed -i "s/^world_id = .*/world_id = \"$WORLD_ID\"/" "$ME/qt/test.conf"
PORTFLAG=(); [ -n "$GAME_PORT" ] && PORTFLAG=("-Dfarm.gamePort=$GAME_PORT")
# 127.0.0.1 only: the listener shim must be on the classpath (server.jar alone binds every interface); build it here if missing
if [ ! -f "$ME/shim/core/net/NioReactor.class" ]; then mkdir -p "$ME/shim"; "$JAVAC" --release 11 -nowarn -cp "$(cygpath -w "$ME/qt/game/server.jar")" -d "$(cygpath -w "$ME/shim")" "$(cygpath -w "$H_DIR/shim/core/net/NioReactor.java")" 2>&1 | grep -v '^Note:'; fi
[ -f "$ME/shim/core/net/NioReactor.class" ] || { echo "run.sh: no listener shim in $ME/shim, refusing to start (it would bind every interface)" >&2; exit 1; }
cd "$ME/qt/game"
# the server's config parser drops a leading "/", so on Linux the config is given relative to qt/game
CONF="$(cygpath -w "$ME/qt/test.conf")"; [ "$CPS" = ':' ] && CONF="../test.conf"
"$JAVA" -Xmx${XMX:-2g} -D2009scape.bindAddress=127.0.0.1 "${PORTFLAG[@]}" "$@" -cp "$(cygpath -w "$B")$CPS$(cygpath -w "$ME/shim")${CPS}server.jar" core.Server "$CONF" < <(tail -f "$ME/qt/console.txt") > "$ME/qt/$LOG.log" 2>&1
echo >> "$ME/qt/console.txt" # the console tail ends on its next write once java has gone (it used to linger and hold the file)
exit 0
