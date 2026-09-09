#!/bin/zsh
# NeoForge lane of the harness. Same pass rule as the Fabric and Quilt lanes: the world starts loading, the game is
# still up 8 s later, and the mod under test is really in the loaded-mod list. A boot without the mod is not a pass.
#
# The one structural difference is where the port happens. On Fabric the loader has committed to its mod set before
# any mod code runs, so a port only takes effect after a relaunch; NeoForge asks registered locators for candidates
# while discovery is still open, so Fox-Grade ports the inbox and hands the result straight to the loader in the same
# launch. There is nothing here that restarts the game.
setopt NULL_GLOB
PT="${PT:-$HOME/mc-porttest-nf}"
B=~/foxgrade-work/batch2
# Overridable so a side run on a second instance cannot write into the corpus ledger being measured.
LEDGER="${LEDGER:-$B/ledger-neoforge.txt}"
LOGPREFIX="${LOGPREFIX:-log-nf}"
MC="$HOME/Library/Application Support/minecraft"
CP=$(cat /tmp/fg-launch-cp-neoforge.txt)
NFVER=26.2.0.82

cp -f "$(ls -t ~/foxgrade-work/foxgrade-mod/dist/foxgrade-*.jar | head -1)" $PT/fg.jar   # always test the newest build

# The mod id a NeoForge jar declares, read out of its TOML manifest without a TOML parser: the first modId key.
# Every modId this jar declares a dependency on, minecraft and neoforge excluded — plus its language loader, which
# is a dependency that never appears in a dependency table. VeinMiner says modLoader = "klf" because it is a Kotlin
# mod and dies with "Missing language loader klf" if nobody supplies kotlin-for-forge; reading dependency tables
# alone fails it for a library the harness never handed it.
nf_deps() {
  local toml=$(unzip -p "$1" META-INF/neoforge.mods.toml META-INF/mods.toml 2>/dev/null)
  local deps=$(echo "$toml" \
    | awk '/^\s*\[\[dependencies/{d=1} d && /^\s*modId\s*=/{print}' \
    | sed 's/.*= *"//; s/".*//' | grep -vxE 'minecraft|neoforge|forge')
  local lang=$(echo "$toml" | grep -m1 -E '^\s*modLoader\s*=' | sed 's/.*= *"//; s/".*//' \
    | grep -vxE 'javafml|lowcodefml')
  echo "$deps $lang" | tr ' ' '\n' | grep -v '^$' | sort -u | tr '\n' ' '
}

nf_modid() {
  unzip -p "$1" META-INF/neoforge.mods.toml META-INF/mods.toml 2>/dev/null \
    | grep -m1 -E '^\s*modId\s*=' | sed 's/.*= *"//; s/".*//'
}

# Every modId a jar provides, its own and those of the mods nested inside it. kotlin-for-forge's outer jar declares
# none of its own — kffmod and kfflang are nested — so matching on the outer manifest alone makes the library
# invisible to the base set and fails anything that needs it.
nf_provides() {
  local ids=$(nf_modid "$1")
  local tmp=$(mktemp -d)
  unzip -qo "$1" 'META-INF/jarjar/*.jar' 'META-INF/jars/*.jar' -d "$tmp" 2>/dev/null
  for nested in "$tmp"/META-INF/*/*.jar; do
    [[ -f $nested ]] && ids="$ids $(nf_modid "$nested")"
  done
  rm -rf "$tmp"
  # The Kotlin library is asked for by the language loader it provides, not by any modId it declares.
  [[ " $ids " == *" kotlinforforge "* ]] && ids="$ids klf"
  echo "$ids" | tr ' ' '\n' | grep -v '^$' | sort -u | tr '\n' ' '
}

nf_run() {
  local name=$1; shift
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  rm -f $PT/mods/*.jar $PT/fox-grade-inbox/*.jar $PT/mods/fox-grade-inbox/*.jar
  rm -rf $PT/crash-reports $PT/.fox-grade
  mkdir -p $PT/mods $PT/fox-grade-inbox
  cp $PT/fg.jar $PT/mods/foxgrade-1.1.0.jar

  local mainjar=$1
  local mainid=$(nf_modid "$mainjar")
  # Dependencies are supplied, the same concession the Fabric lane makes — but only the ones this mod declares, and
  # their own dependencies in turn. Loading the whole library shelf into every run is not a kinder test, it is a
  # different one: with all eleven present, Chat Heads shut the client down during startup with nothing logged, and
  # with only what it asks for it loads and reaches a world. The real target-version build of the mod under test is
  # never supplied, since that would answer the question for it.
  local want=" $(nf_deps "$mainjar") "
  local added=1
  while [[ $added == 1 ]]; do
    added=0
    for bjar in ~/foxgrade-work/nf-base/*.jar; do
      local bids=$(nf_provides "$bjar")
      [[ -z $bids ]] && continue
      [[ " $bids " == *" $mainid "* ]] && continue
      [[ -f $PT/mods/$(basename $bjar) ]] && continue
      local match=0
      for bid in ${=bids}; do [[ " $want " == *" $bid "* ]] && match=1; done
      if [[ $match == 1 ]]; then
        cp "$bjar" $PT/mods/
        want="$want $(nf_deps "$bjar")"
        added=1
      fi
    done
  done
  for jar in "$@"; do cp "$jar" $PT/fox-grade-inbox/; done

  cd $PT
  /usr/bin/java -XstartOnFirstThread -Xmx3G -Djava.net.preferIPv6Addresses=system \
    -DlibraryDirectory="$MC/libraries" \
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
    --add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming \
    -cp "$CP" net.neoforged.fml.startup.Client \
    --fml.neoForgeVersion $NFVER --fml.mcVersion 26.2 --fml.neoFormVersion 2 \
    --username FGTest --version neoforge-$NFVER --gameDir "$PT" \
    --assetsDir "$MC/assets" --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 --accessToken dummy --userType msa --versionType release \
    --quickPlaySingleplayer "NFTEST" \
    > "$B/$LOGPREFIX-$name.log" 2>&1 &
  # Keep the harness window out of the way, scoped by pid to this instance so the user's own game is never touched.
  ( for i in $(seq 1 120); do sleep 2; for pid in $(pgrep -f "gameDir $PT "); do osascript -e "tell application \"System Events\" to set visible of (every process whose unix id is $pid) to false" >/dev/null 2>&1; done; pgrep -f "gameDir $PT " >/dev/null || break; done ) &

  local READY="Preparing spawn area\|Time elapsed\|joined the game"
  local waited=0 ready=0
  while [ $waited -lt 180 ]; do
    sleep 6; waited=$((waited+6))
    pgrep -f "gameDir $PT " >/dev/null || break
    grep -q "$READY" "$B/$LOGPREFIX-$name.log" 2>/dev/null && { sleep 8; ready=1; break; }
  done

  local verdict
  if [ $ready = 1 ] && pgrep -f "gameDir $PT " >/dev/null; then verdict=PASS
  elif ! pgrep -f "gameDir $PT " >/dev/null; then verdict=CRASH
  else verdict=STALL; fi

  # NeoForge prints its mod set as "Name Version (modid)" rows; the mod has to be in there for a pass to count.
  if [[ $verdict == PASS && -n $mainid ]] && ! grep -qE "\($mainid\)" "$B/$LOGPREFIX-$name.log"; then verdict=HELD; fi

  local why=""
  if [ "$verdict" != PASS ]; then
    why=$(grep -m1 "NoClassDefFoundError\|NoSuchMethodError\|NoSuchFieldError\|Mixin apply failed\|InjectionError\|ResolutionException\|Missing or unsupported\|has failed to load\|requires .* any version" "$B/$LOGPREFIX-$name.log" | head -c 170)
  fi
  mkdir -p $B/crashes-nf; for c in $PT/crash-reports/*.txt; do cp "$c" "$B/crashes-nf/$name--$(basename "$c")"; done
  [[ -z $why && $verdict != PASS ]] && why=$(ls -t $PT/crash-reports/*.txt 2>/dev/null | head -1 | xargs -I{} grep -m1 "Caused by\|^java\.\|Exception:" {} 2>/dev/null | head -c 170)
  mkdir -p $B/ports-nf; for pj in $PT/mods/*-fgport.jar; do cp "$pj" "$B/ports-nf/${name}--$(basename $pj)"; done

  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
  echo "$verdict	$name	${why}" >> $LEDGER
  echo "$verdict	$name"
}

if [ $# -gt 0 ]; then
  for jar in "$@"; do nf_run "$(basename ${jar%%--*})" "$jar"; done
else
  for jar in ~/foxgrade-work/nf-corpus/*.jar; do nf_run "$(basename ${jar%%--*})" "$jar"; done
  echo "NEOFORGE RUN DONE" >> $LEDGER
fi
