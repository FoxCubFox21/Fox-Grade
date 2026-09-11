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
LOADERV=$(tr ':' '\n' <<< "$CP" | grep -oE "fabric-loader-[0-9.]+" | head -1 | sed 's/fabric-loader-//;s/\.$//')

LOCK="$PT/.lane.lock"
# One lane at a time. A lock left behind by a killed run must not block every future one, and a lock held by a
# live run must not be clearable by hand -- removing it by hand before a restart is exactly how two chains came to
# write the same ledger, giving 1.20.3 every mod twice. The pid inside decides which case this is.
if mkdir "$LOCK" 2>/dev/null; then
  echo $$ > "$LOCK/pid"
else
  owner=$(cat "$LOCK/pid" 2>/dev/null || echo "")
  if [[ -n $owner ]] && ! kill -0 "$owner" 2>/dev/null; then
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null && echo $$ > "$LOCK/pid"
  fi
fi
if [[ $(cat "$LOCK/pid" 2>/dev/null) != $$ ]]; then
  echo "[$TARGET] SKIPPED: another lane already owns $PT and is still running." >&2
  echo "[$TARGET] Two lanes in one game dir wipe each other's mods and kill each other's game; the numbers" >&2
  echo "[$TARGET] that come out are not about porting." >&2
  exit 4
fi
trap 'rm -rf "$LOCK" 2>/dev/null' EXIT
trap 'rm -rf "$LOCK" 2>/dev/null; exit 130' INT TERM   # cleanup alone would let the script resume

# A lane can only grade Fox-Grade if the game itself can start. Minecraft up to 1.18.2 ships LWJGL 3.2.x, which has
# no arm64 macOS natives, so on Apple Silicon it dies at "Failed to locate library: liblwjgl.dylib" before a mod
# loads -- and every mod in the corpus is then recorded as a porting failure. 1.17.1 read 0 of 14 that way. That is
# a fact about this machine, not about the port, and it must not reach a ledger. Checked before anything is started.
if [[ $(uname -m) == arm64 ]] && ! grep -q "natives-macos-arm64" /tmp/fg-cp-$TARGET.txt; then
  echo "[$TARGET] SKIPPED: no arm64 macOS natives on this classpath (LWJGL 3.2.x). The game cannot start here," >&2
  echo "[$TARGET] so any result would measure the machine. Measure this version on x86, or under an x86 JDK." >&2
  exit 3
fi

# How this lane reaches a world. --quickPlaySingleplayer arrived in 1.20; an older client prints "Completely
# ignored arguments" and sits at the main menu, which is why every pre-1.20 lane read as a total failure. Those
# clients do honour --server and --port, which predate quickPlay by years, so on them the way into a world is to
# join one: a dedicated server of the same version, offline mode, flat world, on a port derived from the version.
# The mod under test still runs in the client, in a real world, which is what the pass rule is about.
if python3 -c "
import sys
key = lambda v: [int(x) if x.isdigit() else 0 for x in v.split('.')]
sys.exit(0 if key('$TARGET') < key('1.20') else 1)
"; then
  SRVDIR="$HOME/mc-porttest-srv-${SLUG}"
  [[ -f $SRVDIR/server.jar ]] || python3 ~/foxgrade-work/make-server.py "$TARGET" >&2 || exit 5
  SRVPORT=$(grep '^server-port=' "$SRVDIR/server.properties" | cut -d= -f2)
  SRVLOG="$B/srv-v$SLUG.log"; : > "$SRVLOG"
  # -Dfoxgrade.srv puts the version on the server's command line. Without it there is nothing there to match on:
  # the jar is called server.jar in every version's directory and pkill -f reads the command line, not the cwd,
  # so a trap aimed at the directory would kill nothing and leave a server holding its port after the lane ended.
  ( cd "$SRVDIR" && exec /usr/bin/java -Dfoxgrade.srv="$SLUG" -Xmx1500M -jar server.jar nogui ) >> "$SRVLOG" 2>&1 &
  SRVPID=$!
  for i in $(seq 1 60); do sleep 2; grep -q 'Done (' "$SRVLOG" && break; done
  if ! grep -q 'Done (' "$SRVLOG"; then
    echo "[$TARGET] SKIPPED: the $TARGET server never finished starting; see $SRVLOG" >&2
    exit 5
  fi
  ENTER_WORLD=(--server 127.0.0.1 --port "$SRVPORT")
  trap 'kill $SRVPID 2>/dev/null; pkill -f "foxgrade.srv=$SLUG" 2>/dev/null; rm -rf "$LOCK" 2>/dev/null' EXIT
  trap 'kill $SRVPID 2>/dev/null; pkill -f "foxgrade.srv=$SLUG" 2>/dev/null; rm -rf "$LOCK" 2>/dev/null; exit 130' INT TERM
  echo "[$TARGET] joining a local $TARGET server on port $SRVPORT (quickPlay does not exist before 1.20)" >&2
else
  ENTER_WORLD=(--quickPlaySingleplayer TESTWORLD)
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
  # Asked and answered before anything is launched. A jar that wants a newer Fabric Loader than this lane runs is
  # refused at mod resolution: the loader puts up a modal "Incompatible mods found!" dialog, twice per mod, and the
  # port is never tested. Reading the requirement out of the jar costs nothing and skips both launches.
  local need=$(unzip -p "$1" fabric.mod.json 2>/dev/null | /usr/bin/python3 -c "
import json,sys
try: print((json.load(sys.stdin).get('depends') or {}).get('fabricloader',''))
except Exception: print('')
" 2>/dev/null)
  if [[ -n $need ]] && ! python3 -c "
import re, sys
need, have = '$need', '$LOADERV'
m = re.search(r'[0-9]+(?:\.[0-9]+)*', need)
if not m or not have: sys.exit(0)
key = lambda v: [int(x) for x in v.split('.')]
sys.exit(1 if key(have) < key(m.group(0)) else 0)
"; then
    echo "UNTESTABLE\t$name\tneeds Fabric Loader $need; this lane runs $LOADERV" >> $LEDGER
    echo "UNTESTABLE	$name"
    return
  fi
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

  launch "$log" "${ENTER_WORLD[@]}"
  ( for i in $(seq 1 80); do sleep 2; for pid in $(pgrep -f "gameDir $PT"); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT" >/dev/null || break; done ) &
  local waited=0 ready=0
  while [ $waited -lt 150 ]; do
    sleep 6; waited=$((waited+6))
    pgrep -f "gameDir $PT" >/dev/null || break
    grep -qE "Preparing spawn area|Time elapsed|joined the game|Loaded [0-9]+ advancements" "$log" 2>/dev/null && { sleep 8; ready=1; break; }
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
  # A corpus jar that demands a newer Fabric Loader than this lane runs was never going to start here, whatever
  # Fox-Grade did to it. Fabric Language Kotlin tags a single 2026 build for all 47 game versions it has ever
  # supported, so "newest build tagged for 1.19.2" is a jar needing loader 0.19.5 while these lanes run 0.18.6.
  # It was recorded as a porting failure in fourteen ledgers -- one wrong row in every published denominator.
  # Same shape as NOTFABRIC: not a pass, not a failure of the port, a jar this lane cannot put the question to.
  if grep -q "of mod 'Fabric Loader' (fabricloader), but only the wrong version is present" "$log" 2>/dev/null; then
    verdict=UNTESTABLE
  elif [[ -z $mainid ]]; then verdict=NOTFABRIC
  elif [[ $verdict == PASS ]] && ! grep -qE "${mainid}(_fgport)?" "$log"; then verdict=HELD; fi
  local why=""
  [ "$verdict" != PASS ] && why=$(grep -m1 -E "NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|Mixin apply|Incompatible mods|requires" "$log" | sed 's/^\[[0-9:]*\] \[[^]]*\]: //' | head -c 150)
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  echo "$verdict\t$name\t$why" >> $LEDGER
  echo "$verdict	$name"
}

echo "# target $TARGET  loader $LOADERV  corpus $CORPUS_DIR  $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> $LEDGER
for jar in "$@"; do fg_run "$(basename ${jar%%--*})" "$jar"; done
echo "RUN DONE $TARGET" >> $LEDGER
