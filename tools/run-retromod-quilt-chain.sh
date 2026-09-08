#!/bin/zsh
# Re-test named corpus groups on the DEV instance with the newest Fox-Grade jar, one game at a time.
# Rows land in dev-ledger.txt as dev-<slug> (excluded from the compat page). Usage: ./run-dev-chain.sh slug [slug...]
B=$HOME/foxgrade-work/batch2
export PT=$HOME/mc-porttest-quilt-rm
for slug in "$@"; do
  if [[ $slug == */* ]]; then jars=$slug; flags=-; slug=$(basename $slug .jar | sed 's/--.*//')   # explicit jar path
  else row=$(awk -F"\t" -v s="$slug" '$1==s' $B/h2h-groups.txt); [[ -z "$row" ]] && { echo "SKIP	quiltrm-$slug	no group row" >> $B/dev-ledger.txt; continue; }
    flags=$(echo "$row" | cut -f2); jars=$(echo "$row" | cut -f3); fi
  ( [[ $flags == *nobasecloth* ]] && export NOBASECLOTH=1
    source <(sed '/^CMDS=/,$d' $B/run-retromod-quilt.sh) >/dev/null 2>&1
    LEDGER=$B/dev-ledger.txt
    rm_run "quiltrm-$slug" ${=jars} )
  pkill -f "gameDir $PT " 2>/dev/null; sleep 2
done
echo "DEV CHAIN DONE $(date +%H:%M)" >> $B/dev-ledger.txt
