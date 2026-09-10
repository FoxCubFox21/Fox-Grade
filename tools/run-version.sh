#!/bin/zsh
# Measure one target version. Same pass rule as every other lane: the world starts loading, the game is still up
# 8 s later, and the mod under test is in the loaded-mod list.
#
# Two launches per mod, because Fabric commits to its mod set before any mod code runs — the first ports the inbox,
# the second tests what it produced. -Dfoxgrade.target opens the gate for the run, which is what the property is
# for: a version cannot be measured until it is allowed, and is not allowed until it has been measured.
#
# Usage: run-version.sh <targetVersion> <assetIndex> <mod.jar>...
setopt NULL_GLOB
TARGET=$1; ASSET=$2; shift 2
SLUG=${TARGET//./_}
PT="$HOME/mc-porttest-v$SLUG"
B=~/foxgrade-work/batch2
LEDGER="${LEDGER:-$B/ledger-v$SLUG.txt}"
LOGPREFIX="${LOGPREFIX:-log-v$SLUG}"
MC="$HOME/Library/Application Support/minecraft"
CORPUS_DIR="${CORPUS_DIR:-$B/h2h}"
CP=$(cat /tmp/fg-cp-$TARGET.txt)

LOCK="$PT/.lane.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "[$TARGET] SKIPPED: another lane already owns $PT (lock $LOCK)." >&2
  echo "[$TARGET] Two lanes in one game dir wipe each other's mods and kill each other's game; the numbers that" >&2
  echo "[$TARGET] come out are not about porting. Remove the lock by hand if no lane is really running." >&2
  exit 4
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
trap 'rmdir "$LOCK" 2>/dev/null; exit 130' INT TERM   # cleanup alone would let the script resume

# A lane can only grade Fox-Grade if the game itself can start. Minecraft up to 1.18.2 ships LWJGL 3.2.x, which has
# no arm64 macOS natives, so on Apple Silicon it dies at "Failed to locate library: liblwjgl.dylib" before a mod
# loads -- and every mod in the corpus is then recorded as a porting failure. 1.17.1 read 0 of 14 that way. That is
# a fact about this machine, not about the port, and it must not reach a ledger.
# The pass rule is "a world starts loading", and this lane reaches a world with --quickPlaySingleplayer. That
# argument arrived in 1.20. An older client prints "Completely ignored arguments: [--quickPlaySingleplayer, ...]",
# sits at the main menu, and never loads anything -- so every mod is recorded STALL no matter how well it ported.
# 1.19.2 and 1.17.1 both read as total failures for this reason alone. Until there is another way into a world on
# those versions, a lane there measures nothing and must not write a ledger.
if python3 -c "
import sys
key = lambda v: [int(x) if x.isdigit() else 0 for x in v.split('.')]
sys.exit(0 if key('$TARGET') < key('1.20') else 1)
"; then
  echo "[$TARGET] SKIPPED: --quickPlaySingleplayer arrived in 1.20, so this client never loads a world and every" >&2
  echo "[$TARGET] mod would be recorded STALL regardless of its port. That is a fact about the harness." >&2
  exit 5
fi

if [[ $(uname -m) == arm64 ]] && ! grep -q "natives-macos-arm64" /tmp/fg-cp-$TARGET.txt; then
  echo "[$TARGET] SKIPPED: no arm64 macOS natives on this classpath (LWJGL 3.2.x). The game cannot start here," >&2
  echo "[$TARGET] so any result would measure the machine. Measure this version on x86, or under an x86 JDK." >&2
  exit 3
fi

# Only ever a measurement build. The shipping jar declares the floor it has actually earned, so on any version
# below that Fabric refuses Fox-Grade before it runs and the whole lane reads as the mods failing -- a wrong number
# that looks exactly like a real one. Build it with:  MEASURE_FLOOR=1.15.2 ./build.sh
FGJAR="$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*-measure.jar 2>/dev/null | head -1)"
if [[ -z $FGJAR ]]; then
  echo "[$TARGET] NO MEASUREMENT JAR -- run: cd ~/foxgrade-work/foxgrade-mod && MEASURE_FLOOR=1.15.2 ./build.sh" >&2
  exit 2
fi
FLOOR=$(unzip -p "$FGJAR" fabric.mod.json | python3 -c "import json,sys;print(json.load(sys.stdin)['depends']['minecraft'])")
python3 - "$FLOOR" "$TARGET" <<'PYFLOOR' || exit 2
import sys
key = lambda v: [int(x) if x.isdigit() else 0 for x in v.split(".")]
floor, target = sys.argv[1].lstrip(">=").strip(), sys.argv[2]
if key(target) < key(floor):
    print(f"  ! measurement jar floor is {floor}; it cannot load on {target}", file=sys.stderr)
    raise SystemExit(1)
PYFLOOR
cp -f "$FGJAR" $PT/fg.jar

launch() {
  local log=$1; shift
  rm -f "$PT/saves/TESTWORLD/session.lock"
  ( cd $PT && /usr/bin/java -XstartOnFirstThread -Xmx2G -Dfoxgrade.target=$TARGET \
      -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$CP" \
      net.fabricmc.loader.impl.launch.knot.KnotClient \
      --username FGTest --version fabric-$TARGET --gameDir "$PT" \
      --assetsDir "$MC/assets" --assetIndex $ASSET \
      --uuid 00000000-0000-0000-0000-000000000000 --accessToken dummy --userType msa --versionType release \
      "$@" >> "$log" 2>&1 & )
}

fg_run() {
  local name=$1; shift
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/fox-grade-inbox/*.jar; rm -rf $PT/crash-reports
  cp $PT/fg.jar $PT/mods/foxgrade.jar
  cp $HOME/mc-porttest-v$SLUG-base/*.jar $PT/mods/ 2>/dev/null
  for jar in "$@"; do cp "$jar" $PT/fox-grade-inbox/; done
  # A mod that hard-depends on another and is launched without it is refused, not broken: Fabric (or the mod's own
  # checker) puts up a startup-error dialog and never reaches a world, which grades the harness rather than the port.
  # Only what this jar actually asks for, transitively — loading the whole corpus into every run makes mods collide
  # and was tried once already. The dependencies are ported alongside it; the verdict is still about this mod alone.
  for dep in $(python3 $B/deps-closure.py "$1" $CORPUS_DIR 2>/dev/null); do cp "$dep" $PT/fox-grade-inbox/; done
  local log="$B/$LOGPREFIX-$name.log"; : > "$log"

  launch "$log"
  local n=0
  while [ $n -lt 36 ]; do
    sleep 5; n=$((n+1))
    grep -qE "INBOX PORTED|INBOX ERROR|INBOX HELD|Incompatible mods|not been measured" "$log" 2>/dev/null && break
    pgrep -f "gameDir $PT" >/dev/null || break
  done
  sleep 3; pkill -f "gameDir $PT " 2>/dev/null; sleep 2

  launch "$log" --quickPlaySingleplayer "TESTWORLD"
  ( for i in $(seq 1 80); do sleep 2; for pid in $(pgrep -f "gameDir $PT"); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT" >/dev/null || break; done ) &
  local waited=0 ready=0
  while [ $waited -lt 150 ]; do
    sleep 6; waited=$((waited+6))
    pgrep -f "gameDir $PT" >/dev/null || break
    grep -qE "Preparing spawn area|Time elapsed|joined the game" "$log" 2>/dev/null && { sleep 8; ready=1; break; }
  done

  local verdict
  if [ $ready = 1 ] && pgrep -f "gameDir $PT" >/dev/null; then verdict=PASS
  elif ! pgrep -f "gameDir $PT" >/dev/null; then verdict=CRASH
  else verdict=STALL; fi
  local mainid=$(unzip -p "$1" fabric.mod.json 2>/dev/null | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
  # A jar with no fabric.mod.json is not a Fabric mod, and Fabric simply ignores it: the vanilla world loads, the
  # game stays up, and every check below passes. Guarding the "was the mod really loaded" test on a non-empty modid
  # meant that test was SKIPPED exactly when it mattered, so a Forge jar in the inbox scored a clean pass. It is
  # not a pass and not a failure of the port either — it is a corpus that should never have contained the jar.
  if [[ -z $mainid ]]; then verdict=NOTFABRIC
  elif [[ $verdict == PASS ]] && ! grep -qE "${mainid}(_fgport)?" "$log"; then verdict=HELD; fi
  local why=""
  [ "$verdict" != PASS ] && why=$(grep -m1 -E "NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|Mixin apply|Incompatible mods|requires" "$log" | sed 's/^\[[0-9:]*\] \[[^]]*\]: //' | head -c 150)
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  echo "$verdict\t$name\t$why" >> $LEDGER
  echo "$verdict	$name"
}

for jar in "$@"; do fg_run "$(basename ${jar%%--*})" "$jar"; done
echo "RUN DONE $TARGET" >> $LEDGER
