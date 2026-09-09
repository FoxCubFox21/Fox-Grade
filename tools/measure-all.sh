#!/bin/zsh
# Measure a list of target versions, one after another, with the same corpus each time.
#
# A version is not supported until a corpus has been run against it and the result is known — that rule lives in
# Targets.java, and this is the thing that satisfies it. Everything is sequential on purpose: two clients on one
# machine distort each other's timings, and a distorted run once made a whole lane look broken.
#
# For each version it builds the instance if it is missing (make-instance.py writes the world, the Fabric API build,
# the options file and /tmp/fg-cp-<version>.txt), then hands off to run-version.sh, which is the only thing that
# decides PASS/CRASH/STALL. Ledgers land in batch2/ledger-v<slug>.txt, one per version, and are left alone if they
# already carry a RUN DONE line, so re-running this picks up where it stopped instead of redoing measured versions.
#
# Usage: measure-all.sh <version>...        Stop it with:  pkill -f measure-all.sh
set -u
setopt NULL_GLOB
B=$HOME/foxgrade-work/batch2
cd $B
CORPUS=($B/h2h/*.jar)

for V in "$@"; do
  SLUG=${V//./_}
  LEDGER=$B/ledger-v$SLUG.txt
  if [ -f "$LEDGER" ] && grep -q "RUN DONE" "$LEDGER"; then
    echo "[measure-all] $V already measured ($(grep -c '^PASS' $LEDGER) pass); skipping"
    continue
  fi
  if [ ! -f "/tmp/fg-cp-$V.txt" ] || [ ! -d "$HOME/mc-porttest-v$SLUG" ]; then
    echo "[measure-all] building instance for $V"
    if ! python3 $HOME/foxgrade-work/make-instance.py "$V"; then
      echo "[measure-all] $V: instance build failed, skipping"; continue
    fi
  fi
  ASSET=$(python3 - "$V" <<'PY'
import json, pathlib, sys
# The asset index a version wants, read from the version json make-instance.py already fetched, so the two can
# never disagree about it.
v = sys.argv[1]
for p in (pathlib.Path.home()/".minecraft"/"versions"/v/f"{v}.json",
          pathlib.Path.home()/"Library/Application Support/minecraft/versions"/v/f"{v}.json"):
    if p.exists():
        print(json.loads(p.read_text()).get("assetIndex", {}).get("id", "")); break
else:
    print("")
PY
)
  if [ -z "$ASSET" ]; then echo "[measure-all] $V: no assetIndex, skipping"; continue; fi
  echo "[measure-all] === $V (assetIndex $ASSET, ${#CORPUS} mods) ==="
  rm -f "$LEDGER" $B/log-v$SLUG-*.log
  ./run-version.sh "$V" "$ASSET" $CORPUS
  echo "[measure-all] $V done: $(grep -c '^PASS' $LEDGER) pass of $(grep -cE '^(PASS|CRASH|STALL|HELD)' $LEDGER)"
done
echo "[measure-all] all versions finished"
