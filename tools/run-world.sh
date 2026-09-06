#!/bin/zsh
cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | head -1)" ~/foxgrade-work/batch121/base/foxgrade-1.1.0.jar   # FRESH JAR: the harness tests the newest build
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest}"   # instance dir; a second instance lets two harnesses run side by side
B=~/foxgrade-work/batch2
LEDGER=$B/ledger.txt
MC="$HOME/Library/Application Support/minecraft"
LCP=$(cat /tmp/fg-launch-cp.txt)
BASE=("$HOME/foxgrade-work/batch121/base/fabric-api-0.159.0+26.2.jar" "$HOME/foxgrade-work/batch121/base/cloth-config-26.2.155.jar" "$HOME/foxgrade-work/batch121/base/foxgrade-1.1.0.jar" "/Users/cassiusmehlhopt/foxgrade-work/batch121/base/fabric-language-kotlin--fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar")

run_one() {
  local name=$1; shift
  # reset instance
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/mods/*.jar.disabled
  for bjar in $BASE; do case "$(basename "$bjar")" in cloth-config*) [[ ${NOBASECLOTH:-0} == 1 ]] && continue;; esac; cp "$bjar" $PT/mods/; done
  rm -f $PT/mods/fox-grade-inbox/*.jar
  rm -rf $PT/screenshots $PT/fox-grade-report.txt $PT/crash-reports $PT/.fox-grade-crash-seen
  mkdir -p $PT/mods/fox-grade-inbox; for jar in "$@"; do if [[ ${AUTOINBOX:-0} == 1 ]]; then cp "$jar" $PT/mods/; else cp "$jar" $PT/mods/fox-grade-inbox/; fi; done   # AUTOINBOX=1: drop into mods/ and let the sweep find it
  echo "{ \"port\": [], \"portAll\": false, \"autotestTicks\": 220, \"autotestCommands\": [${CMDS:-}] }" > $PT/fox-grade.config.json
  cd $PT
  /usr/bin/java -XstartOnFirstThread -Xmx3G -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$LCP" \
    net.fabricmc.loader.impl.launch.knot.KnotClient \
    --username FGTest --version fabric-loader-0.19.3-26.2 \
    --gameDir "$PT" --assetsDir "$MC/assets" --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 \
    --accessToken dummy --userType msa --versionType release \
    --quickPlaySingleplayer "APPLE SKIN PORT 3" \
    > "$B/log-$name.log" 2>&1 &
  local waited=0
  while [ $waited -lt 240 ]; do
    sleep 6; waited=$((waited+6))
    find $PT/screenshots -name "*.png" 2>/dev/null | grep -q . && break
    pgrep -f "gameDir $PT " >/dev/null || break
  done
  local port_line=$(grep -m1 "INBOX PORTED\|INBOX ERROR" "$B/log-$name.log" | sed 's/.*INBOX/INBOX/')
  local verdict
  if find $PT/screenshots -name "*.png" 2>/dev/null | grep -q .; then
    verdict=PASS
    cp "$(find $PT/screenshots -name "*.png" | head -1)" "$B/shot-$name.png"
  elif ! pgrep -f "gameDir $PT " >/dev/null; then
    verdict=CRASH
  else
    verdict=STALL
  fi
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  mkdir -p $B/ports; for pj in $PT/mods/*-fgport.jar; do cp "$pj" "$B/ports/${name}--$(basename $pj)"; done
  local why=""
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
