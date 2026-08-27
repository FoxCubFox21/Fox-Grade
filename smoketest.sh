#!/bin/zsh
# modport rule #3 — LOAD smoke test.
# Compile-clean != works. This boots a headless Fabric server with the ported mod jar dropped in and
# checks it actually reaches "Done" (mods loaded) instead of crashing during init. That's real runtime
# proof the ported jar loads — far stronger than "it compiled".
#
# Usage:   ./smoketest.sh <fabric-server-dir> <ported-mod.jar> [extra-mod.jar ...]
# Needs a one-time Fabric server (see SETUP below). Exit 0 = loaded clean; non-zero = failed to load.

set -u
SERVER_DIR="${1:-}"
shift 2>/dev/null || true
MODS=("$@")

if [[ -z "$SERVER_DIR" || ${#MODS[@]} -eq 0 ]]; then
  cat <<'EOF'
usage: ./smoketest.sh <fabric-server-dir> <ported-mod.jar> [extra-mod.jar ...]

SETUP (one time) — a headless Fabric server to test against:
  1. mkdir fabric-test-server && cd fabric-test-server
  2. Download the Fabric server launcher for your MC version from https://fabricmc.net/use/server/
     (or run the fabric-installer with `server -mcversion <ver> -downloadMinecraft`).
  3. echo "eula=true" > eula.txt      # you are accepting Mojang's EULA
  4. mkdir mods && drop fabric-api-*.jar in it.
  5. First run once to generate the world, then Ctrl-C.
Then: ./smoketest.sh ./fabric-test-server ../mymod.ported.jar
EOF
  exit 2
fi

LAUNCH="$(ls "$SERVER_DIR"/fabric-server-*launch*.jar "$SERVER_DIR"/fabric-server-launcher.jar 2>/dev/null | head -1)"
if [[ -z "$LAUNCH" ]]; then
  echo "❌ No fabric server launcher jar found in $SERVER_DIR (see SETUP)."; exit 2
fi

mkdir -p "$SERVER_DIR/mods"
STAGED=()
for m in "${MODS[@]}"; do
  cp "$m" "$SERVER_DIR/mods/" && STAGED+=("$SERVER_DIR/mods/$(basename "$m")") && echo "staged $(basename "$m")"
done

LOG="$(mktemp)"
echo "=== booting server (up to 150s) to see if the mod loads ==="
# Prevent idle sleep, cap runtime, feed 'stop' so it shuts down if it DOES reach the console.
( echo "stop" ) | caffeinate -i java -Xmx2G -jar "$LAUNCH" nogui >"$LOG" 2>&1 &
PID=$!
( sleep 150 && kill "$PID" 2>/dev/null ) &
WATCH=$!
wait "$PID" 2>/dev/null
kill "$WATCH" 2>/dev/null

# clean up staged mods so the test dir doesn't accumulate
for s in "${STAGED[@]}"; do rm -f "$s"; done

echo "=== verdict ==="
if grep -qE 'Done \([0-9.]+s\)! For help' "$LOG"; then
  echo "✅ LOADED CLEAN — server reached 'Done', the mod initialized without crashing."
  RC=0
elif grep -qiE 'Could not execute entrypoint|Mixin apply.*failed|Incompatible mod set|A mod crashed on startup|Failed to run mod initializer|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError' "$LOG"; then
  echo "❌ FAILED TO LOAD — mod-init error:"
  grep -iE 'entrypoint|mixin|incompatible|crashed|NoSuchMethod|NoClassDefFound|ClassNotFound|Caused by' "$LOG" | head -20
  RC=1
else
  echo "⚠️  INCONCLUSIVE — server did not clearly reach 'Done' and no known error matched. Tail:"
  tail -25 "$LOG"
  RC=3
fi
echo "(full log: $LOG)"
exit $RC
