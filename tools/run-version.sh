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
CP=$(cat /tmp/fg-cp-$TARGET.txt)

cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | head -1)" $PT/fg.jar

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
  pkill -f "gameDir $PT" 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/fox-grade-inbox/*.jar; rm -rf $PT/crash-reports
  cp $PT/fg.jar $PT/mods/foxgrade.jar
  cp $HOME/mc-porttest-v$SLUG-base/*.jar $PT/mods/ 2>/dev/null
  for jar in "$@"; do cp "$jar" $PT/fox-grade-inbox/; done
  local log="$B/$LOGPREFIX-$name.log"; : > "$log"

  launch "$log"
  local n=0
  while [ $n -lt 36 ]; do
    sleep 5; n=$((n+1))
    grep -qE "INBOX PORTED|INBOX ERROR|INBOX HELD|Incompatible mods|not been measured" "$log" 2>/dev/null && break
    pgrep -f "gameDir $PT" >/dev/null || break
  done
  sleep 3; pkill -f "gameDir $PT" 2>/dev/null; sleep 2

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
  if [[ $verdict == PASS && -n $mainid ]] && ! grep -qE "${mainid}(_fgport)?" "$log"; then verdict=HELD; fi
  local why=""
  [ "$verdict" != PASS ] && why=$(grep -m1 -E "NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|Mixin apply|Incompatible mods|requires" "$log" | sed 's/^\[[0-9:]*\] \[[^]]*\]: //' | head -c 150)
  pkill -f "gameDir $PT" 2>/dev/null; sleep 2
  echo "$verdict\t$name\t$why" >> $LEDGER
  echo "$verdict	$name"
}

for jar in "$@"; do fg_run "$(basename ${jar%%--*})" "$jar"; done
echo "RUN DONE $TARGET" >> $LEDGER
