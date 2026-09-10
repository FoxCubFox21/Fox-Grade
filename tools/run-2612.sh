#!/bin/zsh
# The 26.1.2 lane. Same pass rule as every other lane: the world starts loading, the game is still up 8 s later, and
# the mod under test is in the loaded-mod list.
#
# Two launches per mod, unlike the NeoForge lane. Fabric commits to its mod set before any mod code runs, so the first
# launch is what ports the inbox and the second is what tests the result. NeoForge needs only one because its
# discovery pipeline is still open when Fox-Grade runs.
#
# -Dfoxgrade.target=26.1.2 opens the gate for the run. That is what the property is for: a version cannot be measured
# until it is allowed, and it is not allowed until it has been measured.
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest-2612}"
B=~/foxgrade-work/batch2
LEDGER="${LEDGER:-$B/ledger-2612.txt}"
LOGPREFIX="${LOGPREFIX:-log-2612}"
MC="$HOME/Library/Application Support/minecraft"
CP=$(cat /tmp/fg-launch-cp-2612.txt)
TARGET=26.1.2

cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | grep -v -- -measure | head -1)" $PT/fg.jar

launch() {   # $1 = log file, $2... = extra args
  local log=$1; shift
  rm -f "$PT/saves/TEST2612/session.lock"
  ( cd $PT && /usr/bin/java -XstartOnFirstThread -Xmx2G -Dfoxgrade.target=$TARGET \
      -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$CP" \
      net.fabricmc.loader.impl.launch.knot.KnotClient \
      --username FGTest --version fabric-loader-0.19.3-$TARGET --gameDir "$PT" \
      --assetsDir "$MC/assets" --assetIndex 30 \
      --uuid 00000000-0000-0000-0000-000000000000 --accessToken dummy --userType msa --versionType release \
      "$@" >> "$log" 2>&1 & )
}

fg_run() {
  local name=$1; shift
  pkill -f "gameDir $PT" 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/fox-grade-inbox/*.jar; rm -rf $PT/crash-reports
  cp $PT/fg.jar $PT/mods/foxgrade-1.1.0.jar
  cp ~/mc-porttest-2612-base/*.jar $PT/mods/ 2>/dev/null
  for jar in "$@"; do cp "$jar" $PT/fox-grade-inbox/; done
  local log="$B/$LOGPREFIX-$name.log"; : > "$log"

  # First launch: port. It is done when the inbox line appears or the loader gives up.
  launch "$log"
  local n=0
  while [ $n -lt 40 ]; do
    sleep 5; n=$((n+1))
    grep -qE "INBOX PORTED|INBOX ERROR|INBOX HELD|Incompatible mods" "$log" 2>/dev/null && break
    pgrep -f "gameDir $PT" >/dev/null || break
  done
  sleep 3; pkill -f "gameDir $PT" 2>/dev/null; sleep 2

  # Second launch: test what the first one produced.
  launch "$log" --quickPlaySingleplayer "TEST2612"
  ( for i in $(seq 1 90); do sleep 2; for pid in $(pgrep -f "gameDir $PT"); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT" >/dev/null || break; done ) &
  local waited=0 ready=0
  while [ $waited -lt 170 ]; do
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
  mkdir -p $B/ports-2612; for pj in $PT/mods/*-fgport.jar; do cp "$pj" "$B/ports-2612/${name}--$(basename $pj)"; done
  pkill -f "gameDir $PT" 2>/dev/null; sleep 2
  echo "$verdict\t$name\t$why" >> $LEDGER
  echo "$verdict	$name"
}

for jar in "$@"; do fg_run "$(basename ${jar%%--*})" "$jar"; done
echo "2612 RUN DONE" >> $LEDGER
