#!/bin/zsh
# Head-to-head: the same old mods through Retromod, on the same instance and base jars as the
# Fox-Grade harness. Retromod ports on one launch and asks for a restart; the second launch loads
# what it produced (mods/ is left exactly as Retromod arranged it).
cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | head -1)" ~/foxgrade-work/batch121/base/foxgrade-1.1.0.jar   # FRESH JAR: the harness tests the newest build
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest}"   # instance dir; a second instance lets two harnesses run side by side
B=~/foxgrade-work/batch2
LEDGER=$B/retromod-ledger.txt
MC="$HOME/Library/Application Support/minecraft"
LCP=$(cat /tmp/fg-launch-cp.txt)
BASE=("$HOME/foxgrade-work/batch121/base/fabric-api-0.159.0+26.2.jar" "$HOME/foxgrade-work/batch121/base/cloth-config-26.2.155.jar" "$HOME/foxgrade-work/batch121/base/fabric-language-kotlin--fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar" "$HOME/foxgrade-work/batch2/retromod/retromod--retromod-1.3.0-snapshot.10+26.2.jar")
READY="Preparing spawn area\|Time elapsed\|joined the game"

launch() {   # $1 = log name
  cd $PT
  /usr/bin/java -XstartOnFirstThread -Xmx3G -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$LCP" \
    net.fabricmc.loader.impl.launch.knot.KnotClient \
    --username FGTest --version fabric-loader-0.19.3-26.2 \
    --gameDir "$PT" --assetsDir "$MC/assets" --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 \
    --accessToken dummy --userType msa --versionType release \
    --quickPlaySingleplayer "APPLE SKIN PORT 3" \
    > "$B/log-rm-$1.log" 2>&1 &
  local w=0
  while [ $w -lt 150 ]; do
    sleep 6; w=$((w+6))
    pgrep -f "gameDir $PT " >/dev/null || break
    grep -q "$READY" "$B/log-rm-$1.log" 2>/dev/null && { sleep 8; break; }
  done
}
reset() {
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/mods/*.jar.disabled; for b in $BASE; do case "$(basename "$b")" in cloth-config*) [[ ${NOBASECLOTH:-0} == 1 ]] && continue;; esac; cp "$b" $PT/mods/; done
  mkdir -p $PT/mods/retromod-input
  rm -f $PT/mods/retromod-input/*.jar $PT/mods/retromod-input/processed/*.jar $PT/retromod-input/*.jar $PT/retromod-input/processed/*.jar
  rm -rf $PT/mods/retromod-backups $PT/retromod-backups $PT/config/retromod/aot-cache $PT/screenshots $PT/crash-reports $PT/fox-grade.config.json
}
rm_run() {
  local name=$1; shift
  reset; for j in "$@"; do cp "$j" $PT/mods/retromod-input/; done
  launch "$name-A"; pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  { echo "== mods"; ls $PT/mods; echo "== mods/retromod-input"; ls $PT/mods/retromod-input; echo "== processed"; ls $PT/mods/retromod-input/processed 2>/dev/null; } > "$B/log-rm-$name.files" 2>&1
  launch "$name"
  local v=CRASH; grep -q "$READY" "$B/log-rm-$name.log" && pgrep -f "gameDir $PT " >/dev/null && v=PASS
  local mainid=$(unzip -p "$1" fabric.mod.json 2>/dev/null | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
  if [ "$v" = PASS ] && [ -n "$mainid" ] && ! grep -qE "^[[:space:]]*[-\\|]+[[:space:]]*${mainid}([_-][A-Za-z0-9]+)?[[:space:]]" "$B/log-rm-$name.log"; then v=HELD; fi
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  local loaded=$(grep -m1 -o "Loading [0-9]* mods" "$B/log-rm-$name.log")
  local why=""; [ "$v" != PASS ] && why=$(grep -m1 "Caused by\|NoClassDefFoundError\|NoSuchMethodError\|AbstractMethodError" "$B/log-rm-$name.log" | grep -v HTTP_ERROR | head -c 170)
  mkdir -p $B/crashes; for c in $PT/crash-reports/*.txt; do [ -f "$c" ] && cp "$c" "$B/crashes/rm-$name--$(basename "$c")"; done
  if [ "$v" != PASS ] && [ -z "$why" ]; then c=$(ls -t $PT/crash-reports/*.txt 2>/dev/null | head -1); [ -n "$c" ] && why=$(grep -m1 "Caused by\|Description:" "$c" | grep -v HTTP_ERROR | head -c 170); fi
  pkill -f "gameDir $PT " 2>/dev/null   # no game outlives its verdict — scoped to THIS instance
  printf '%s\t%s\t%s\t%s\n' "$v" "$name" "$loaded" "$why" >> $LEDGER
  mkdir -p $B/retromod-ports; for pj in $PT/mods/*.jar; do case "$(basename $pj)" in fabric-api*|cloth-config*|fabric-language-kotlin*|retromod--*) ;; *) cp "$pj" "$B/retromod-ports/$name--$(basename $pj)";; esac; done
}
rm_run modmenu $B/modmenu--*.jar
rm_run "zoomify+yacl" $B/zoomify--*.jar $B/gui/yacl--*.jar
rm_run betterf3 $B/gui/betterf3--*.jar
rm_run lithium $B/lithium--*.jar
rm_run entityculling $B/entityculling--*.jar
echo "RETROMOD RUN DONE" >> $LEDGER
