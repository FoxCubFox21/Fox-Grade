#!/bin/zsh
# Head-to-head: the same old mods through Retromod, on the same instance and base jars as the
# Fox-Grade harness. Retromod ports on one launch and asks for a restart; the second launch loads
# what it produced (mods/ is left exactly as Retromod arranged it).
cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | grep -v -- -measure | head -1)" ~/foxgrade-work/batch121/base/foxgrade-1.1.0.jar   # FRESH JAR: the harness tests the newest build
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest}"   # instance dir; a second instance lets two harnesses run side by side
B=~/foxgrade-work/batch2
LEDGER=$B/quilt-retromod-ledger.txt
MC="$HOME/Library/Application Support/minecraft"
LCP=$(cat /tmp/fg-launch-cp-quilt.txt)
BASE=("$HOME/foxgrade-work/batch121/base/fabric-api-0.159.0+26.2.jar" "$HOME/foxgrade-work/batch121/base/cloth-config-26.2.155.jar" "$HOME/foxgrade-work/batch121/base/fabric-language-kotlin--fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar" "$HOME/foxgrade-work/batch2/retromod/retromod--retromod-1.3.0-snapshot.10+26.2.jar")
READY="Preparing spawn area\|Time elapsed\|joined the game"

launch() {   # $1 = log name
  cd $PT
  /usr/bin/java -XstartOnFirstThread -Xmx3G -Dloader.noGui=true -Dloader.transform_cache.disable_preload=true -DFabricMcEmu="net.minecraft.client.main.Main" -cp "$LCP" \
    org.quiltmc.loader.impl.launch.knot.KnotClient \
    --username FGTest --version quilt-loader-0.31.0-beta.4-26.2 \
    --gameDir "$PT" --assetsDir "$MC/assets" --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 \
    --accessToken dummy --userType msa --versionType release \
    --quickPlaySingleplayer "APPLE SKIN PORT 3" \
    > "$B/log-quiltrm-$1.log" 2>&1 &
  # Keep the game out of the way: hide every java window within 2 s of it appearing, for the life of this run.
  # Hide only THIS instance's game window (by process id) — never other Java apps such as the user's own Minecraft.
  ( for i in $(seq 1 120); do sleep 2; for pid in $(pgrep -f "gameDir $PT "); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT " >/dev/null || break; done ) &
  local w=0
  while [ $w -lt 150 ]; do
    sleep 6; w=$((w+6))
    pgrep -f "gameDir $PT " >/dev/null || break
    grep -q "$READY" "$B/log-quiltrm-$1.log" 2>/dev/null && { sleep 8; break; }
  done
}
reset() {
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  local w=0; until ! pgrep -f "gameDir $PT " >/dev/null || [ $w -ge 40 ]; do sleep 1; w=$((w+1)); done
  # NOTE: no cache wipe. Forcing Quilt 0.31 to rebuild its transform cache every run is the path that fails;
  # letting it reuse/refresh its own cache is also what a real user experiences after their first launch.
  # Quilt scans mods/ recursively and pulls Retromod's own folders + README files into its transform cache;
  # mark them skipped the same way Fox-Grade marks its inbox, so neither tool is charged for that.
  for d in $PT/mods/*/; do [ -d "$d" ] && : > "$d/quilt_loader_ignored"; done
  rm -f $PT/mods/*.txt
  rm -f $PT/mods/*.jar $PT/mods/*.jar.disabled; for b in $BASE; do case "$(basename "$b")" in cloth-config*) [[ ${NOBASECLOTH:-0} == 1 ]] && continue;; esac; cp "$b" $PT/mods/; done
  mkdir -p $PT/retromod-input
  # Wipe every Retromod state folder, else its processed/ marks a mod as already done and it silently skips porting.
  rm -rf $PT/retromod-input $PT/mods/retromod-input $PT/retromod-output $PT/mods/retromod-output $PT/retromod-backups $PT/mods/retromod-backups
  mkdir -p $PT/retromod-input
  rm -rf $PT/mods/retromod-backups $PT/retromod-backups $PT/config/retromod/aot-cache $PT/screenshots $PT/crash-reports $PT/fox-grade.config.json
}
rm_run() {
  local name=$1; shift
  reset; for j in "$@"; do cp "$j" $PT/retromod-input/; done
  launch "$name-A"; pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  { echo "== mods"; ls $PT/mods; echo "== mods/retromod-input"; ls $PT/retromod-input; echo "== processed"; ls $PT/retromod-input/processed 2>/dev/null; } > "$B/log-rm-$name.files" 2>&1
  launch "$name"
  local v=CRASH; grep -q "$READY" "$B/log-quiltrm-$name.log" && pgrep -f "gameDir $PT " >/dev/null && v=PASS
  local mainid=$(unzip -p "$1" fabric.mod.json 2>/dev/null | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
  if [ "$v" = PASS ] && [ -n "$mainid" ] && ! grep -qE "^[[:space:]]*[-\\|]+[[:space:]]*${mainid}([_-][A-Za-z0-9]+)?[[:space:]]|^\\|[^|]*\\|[^|]*\\|[[:space:]]*${mainid}([_-][A-Za-z0-9]+)?[[:space:]]*\\|" "$B/log-quiltrm-$name.log"; then v=HELD; fi
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  local loaded=$(grep -m1 -o "Loading [0-9]* mods" "$B/log-quiltrm-$name.log")
  local why=""; [ "$v" != PASS ] && why=$(grep -m1 "Caused by\|NoClassDefFoundError\|NoSuchMethodError\|AbstractMethodError" "$B/log-quiltrm-$name.log" | grep -v HTTP_ERROR | head -c 170)
  mkdir -p $B/crashes; for c in $PT/crash-reports/*.txt; do [ -f "$c" ] && cp "$c" "$B/crashes/rm-$name--$(basename "$c")"; done
  if [ "$v" != PASS ] && [ -z "$why" ]; then c=$(ls -t $PT/crash-reports/*.txt 2>/dev/null | head -1); [ -n "$c" ] && why=$(grep -m1 "Caused by\|Description:" "$c" | grep -v HTTP_ERROR | head -c 170); fi
  pkill -f "gameDir $PT " 2>/dev/null   # no game outlives its verdict — scoped to THIS instance
  printf '%s\t%s\t%s\t%s\n' "$v" "$name" "$loaded" "$why" >> $LEDGER
  mkdir -p $B/retromod-ports; for pj in $PT/mods/*.jar; do case "$(basename $pj)" in fabric-api*|cloth-config*|fabric-language-kotlin*|retromod--*) ;; *) cp "$pj" "$B/retromod-ports/$name--$(basename $pj)";; esac; done
}
