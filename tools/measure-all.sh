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

# One chain at a time. Two of these ran concurrently over the same version list and both wrote the same ledger,
# so a 14-mod corpus came out as 28 rows and every number in it was wrong.
CHAINLOCK=/tmp/fg-measure-all.lock
# One chain at a time. A lock left behind by a killed run must not block every future one, and a lock held by a
# live run must not be clearable by hand -- removing it by hand before a restart is exactly how two chains came to
# write the same ledger, giving 1.20.3 every mod twice. The pid inside decides which case this is.
if mkdir "$CHAINLOCK" 2>/dev/null; then
  echo $$ > "$CHAINLOCK/pid"
else
  owner=$(cat "$CHAINLOCK/pid" 2>/dev/null || echo "")
  if [[ -n $owner ]] && ! kill -0 "$owner" 2>/dev/null; then
    rm -rf "$CHAINLOCK"
    mkdir "$CHAINLOCK" 2>/dev/null && echo $$ > "$CHAINLOCK/pid"
  fi
fi
if [[ $(cat "$CHAINLOCK/pid" 2>/dev/null) != $$ ]]; then
  echo "[measure-all] another chain is already running; refusing to double up." >&2
  exit 4
fi
trap 'rm -rf "$CHAINLOCK" 2>/dev/null' EXIT
trap 'rm -rf "$CHAINLOCK" 2>/dev/null; exit 130' INT TERM   # cleanup alone would let the script resume

setopt NULL_GLOB
B=$HOME/foxgrade-work/batch2
cd $B
# Which corpus measures which target. Fox-Grade ports forward, so a target must be measured with mods built for an
# OLDER version — running the 1.21.1 corpus against a 1.20.1 target would be porting backwards, which is not a thing
# Fox-Grade claims to do and would have burned a whole overnight run measuring nonsense.
era_for() {
  case $1 in
    1.21.2|1.21.3|1.21.4|1.21.5|1.21.6|1.21.7|1.21.8|1.21.9|1.21.10|1.21.11) echo "$B/h2h" ;;   # 1.21.1-built
    1.21|1.21.1|1.20.5|1.20.6)  echo "$HOME/foxgrade-work/era-corpus/1.20.1" ;;
    # 1.20 and 1.20.3 take the corpus their family host takes, so each row is directly comparable to the version
    # it shares tables with: 1.20 against 1.20.1, 1.20.3 against 1.20.4. Anything else compares two things at once.
    1.20.2|1.20.4|1.20.3)       echo "$HOME/foxgrade-work/era-corpus/1.19.4" ;;
    1.20.1|1.19.4|1.20)         echo "$HOME/foxgrade-work/era-corpus/1.19.2" ;;
    1.19.2)                     echo "$HOME/foxgrade-work/era-corpus/1.18.2" ;;
    1.18.2)                     echo "$HOME/foxgrade-work/era-corpus/1.17.1" ;;
    1.17.1)                     echo "$HOME/foxgrade-work/era-corpus/1.16.5" ;;
    1.16.5)                     echo "$HOME/foxgrade-work/era-corpus/1.15.2" ;;
    1.15.2)                     echo "$HOME/foxgrade-work/era-corpus/1.14.4" ;;
    *)                          echo "" ;;
  esac
}

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
  # Written by make-instance.py alongside the classpath, so the two can never disagree and a version does not get
  # skipped for a file the launcher happens not to have.
  ASSET=$(cat "/tmp/fg-asset-$V.txt" 2>/dev/null)
  if [ -z "$ASSET" ]; then echo "[measure-all] $V: no assetIndex, skipping"; continue; fi
  CORPUS_DIR=$(era_for "$V")
  if [ -z "$CORPUS_DIR" ] || [ ! -d "$CORPUS_DIR" ]; then
    echo "[measure-all] $V: no corpus older than it, skipping"; continue
  fi
  export CORPUS_DIR
  CORPUS=($CORPUS_DIR/*.jar)
  if [ ${#CORPUS} -eq 0 ]; then echo "[measure-all] $V: corpus $CORPUS_DIR is empty, skipping"; continue; fi
  echo "[measure-all] === $V (assetIndex $ASSET, ${#CORPUS} mods from $(basename $CORPUS_DIR)) ==="
  rm -f "$LEDGER" $B/log-v$SLUG-*.log
  ./run-version.sh "$V" "$ASSET" $CORPUS
  # The denominator is the mods this lane could actually put the question to. NOTFABRIC (a jar with no
  # fabric.mod.json), NOMODID and UNTESTABLE (a jar needing a newer loader than this lane runs) are all rows where
  # the port was never tested, so counting them as failures understates every version by however many the corpus
  # happens to contain.
  echo "[measure-all] $V done: $(grep -c '^PASS' $LEDGER) pass of $(grep -cE '^(PASS|CRASH|STALL|HELD)' $LEDGER) testable"
done
echo "[measure-all] all versions finished"
