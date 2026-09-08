#!/bin/zsh
cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | head -1)" ~/foxgrade-work/batch121/base/foxgrade-1.1.0.jar   # FRESH JAR: the harness tests the newest build
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest}"   # instance dir; a second instance lets two harnesses run side by side
B=~/foxgrade-work/batch2
LEDGER=$B/ledger.txt
MC="$HOME/Library/Application Support/minecraft"
LCP=$(cat /tmp/fg-launch-cp-quilt.txt)
BASE=("$HOME/foxgrade-work/batch121/base/fabric-api-0.159.0+26.2.jar" "$HOME/foxgrade-work/batch121/base/cloth-config-26.2.155.jar" "$HOME/foxgrade-work/batch121/base/foxgrade-1.1.0.jar" "/Users/cassiusmehlhopt/foxgrade-work/batch121/base/fabric-language-kotlin--fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar")

run_one() {
  local name=$1; shift
  # reset instance
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  local w=0; until ! pgrep -f "gameDir $PT " >/dev/null || [ $w -ge 40 ]; do sleep 1; w=$((w+1)); done   # Quilt rewrites its transform cache on exit: never overlap two JVMs
  rm -rf "$PT/.cache/quilt_loader/transform-cache-client"
  rm -f $PT/mods/*.jar $PT/mods/*.jar.disabled
  for bjar in $BASE; do case "$(basename "$bjar")" in cloth-config*) [[ ${NOBASECLOTH:-0} == 1 ]] && continue;; esac; cp "$bjar" $PT/mods/; done
  rm -f $PT/mods/foxgrade-1.1.0.jar; cp "$HOME/foxgrade-work/foxgrade-mod/dist/foxgrade-1.1.0+quilt.jar" $PT/mods/   # Quilt host gets the Quilt-manifest build
  rm -f $PT/mods/fox-grade-inbox/*.jar $PT/mods/fox-grade-inbox/processed/*.jar $PT/fox-grade-inbox/*.jar
  rm -rf $PT/screenshots $PT/fox-grade-report.txt $PT/crash-reports $PT/.fox-grade-crash-seen
  local mainjar=$1
  mkdir -p $PT/fox-grade-inbox; for jar in "$@"; do if [[ ${AUTOINBOX:-0} == 1 ]]; then cp "$jar" $PT/mods/; else cp "$jar" $PT/fox-grade-inbox/; fi; done   # Quilt scans mods/ recursively: use the game-folder inbox   # AUTOINBOX=1: drop into mods/ and let the sweep find it
  echo "{ \"port\": [], \"portAll\": false, \"autotestTicks\": 220, \"autotestCommands\": [${CMDS:-}] }" > $PT/fox-grade.config.json
  cd $PT
  /usr/bin/java -XstartOnFirstThread -Xmx3G -Dloader.noGui=true -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$LCP" \
    org.quiltmc.loader.impl.launch.knot.KnotClient \
    --username FGTest --version quilt-loader-0.31.0-beta.4-26.2 \
    --gameDir "$PT" --assetsDir "$MC/assets" --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 \
    --accessToken dummy --userType msa --versionType release \
    --quickPlaySingleplayer "APPLE SKIN PORT 3" \
    > "$B/log-$name.log" 2>&1 &
  # Keep the game out of the way: hide every java window within 2 s of it appearing, for the life of this run.
  # Hide only THIS instance's game window (by process id) — never other Java apps such as the user's own Minecraft.
  ( for i in $(seq 1 120); do sleep 2; for pid in $(pgrep -f "gameDir $PT "); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT " >/dev/null || break; done ) &
  # Identical pass rule to the Retromod lane: the world starts loading (READY marker) and the game is still running
  # 8 s later, within a 150 s cap. A screenshot is still taken when the game gets that far, but it is not the verdict.
  local READY="Preparing spawn area\|Time elapsed\|joined the game"
  local waited=0 ready=0
  while [ $waited -lt 150 ]; do
    sleep 6; waited=$((waited+6))
    pgrep -f "gameDir $PT " >/dev/null || break
    grep -q "$READY" "$B/log-$name.log" 2>/dev/null && { sleep 8; ready=1; break; }
  done
  local port_line=$(grep -m1 "INBOX PORTED\|INBOX ERROR" "$B/log-$name.log" | sed 's/.*INBOX/INBOX/')
  local verdict
  if [ $ready = 1 ] && pgrep -f "gameDir $PT " >/dev/null; then
    verdict=PASS
    find $PT/screenshots -name "*.png" 2>/dev/null | head -1 | while read -r shot; do cp "$shot" "$B/shot-$name.png"; done
  elif ! pgrep -f "gameDir $PT " >/dev/null; then
    verdict=CRASH
  else
    verdict=STALL
  fi
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  mkdir -p $B/ports; for pj in $PT/mods/*-fgport.jar; do cp "$pj" "$B/ports/${name}--$(basename $pj)"; done
  # A boot without the mod under test is not a pass: the mod may have been held for a missing library.
  local mainid=$(unzip -p "$mainjar" fabric.mod.json 2>/dev/null | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
  if [[ $verdict == PASS && -n $mainid ]] && ! grep -qE "^[[:space:]]*[-\\|]+[[:space:]]*${mainid}(_fgport)?[[:space:]]|^\\|[^|]*\\|[^|]*\\|[[:space:]]*${mainid}(_fgport)?[[:space:]]*\\|" "$B/log-$name.log"; then
    verdict=HELD; why_held=$(grep -m1 "INBOX HELD *$(basename "$mainjar")" "$B/log-$name.log" | sed 's/.*— needs /needs /; s/;.*//')
  fi
  local why="${why_held:-}"
  if [ "$verdict" != PASS ]; then
    why=$(grep -m1 "requires any version\|Incompatible mods\|InjectionError\|Mixin apply failed\|Unable to launch\|NoClassDefFoundError\|NoSuchMethodError" "$B/log-$name.log" | head -c 160)
  fi
  mkdir -p $B/crashes; for c in $PT/crash-reports/*.txt; do [[ -f $c ]] && cp "$c" "$B/crashes/$name--$(basename "$c")"; done; cp $PT/logs/latest.log "$B/crashes/$name--latest.log" 2>/dev/null
  if [[ $verdict != PASS && -z $why ]]; then c=$(ls -t $PT/crash-reports/*.txt 2>/dev/null | head -1); [[ -n $c ]] && why=$(grep -m1 "Caused by\|^java\.\|Exception:" "$c" | grep -v HTTP_ERROR | head -c 170); fi   # server-side crash reports carry the cause too
  pkill -f "gameDir $PT " 2>/dev/null   # no game outlives its verdict — scoped to THIS instance (a bare $PT prefix-matches the other instances)
  echo "$verdict	$name	${port_line:-no-port-line}	${why}" >> $LEDGER
}

mkdir -p $B/base   # base/ is canonical — the freshly built foxgrade jar is placed there by the build step
# save current mods to restore later
mkdir -p $B/saved-mods; cp $PT/mods/*.jar $B/saved-mods/ 2>/dev/null

# World-rendering corpus. CMDS = JSON list body of server commands run before the shot.
CMDS='"execute at @p run summon friendsandfoes:copper_golem ^ ^ ^3", "execute at @p run summon friendsandfoes:moobloom ^1.5 ^ ^4", "execute at @p run summon friendsandfoes:glare ^-1.5 ^1 ^4", "time set day"' run_one "friendsandfoes+resourcefullib" $B/world/friends-and-foes--*.jar $B/world/resourceful-lib--*.jar
CMDS='"execute at @p run summon naturalist:deer ^ ^ ^3", "execute at @p run summon naturalist:bear ^2 ^ ^5", "execute at @p run summon naturalist:snail ^-1 ^ ^2", "time set day"' run_one naturalist $B/world/naturalist--*.jar
echo "WORLD RUN DONE" >> $LEDGER
