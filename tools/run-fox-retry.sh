#!/bin/zsh
# Fox-Grade-only retry pass on ~/mc-porttest: every group without a ledger row is run with the newest dist jar.
# (The non-PASS and hollow-PASS rows were already purged by run-h2h2-retry-fox.sh.)
B=$HOME/foxgrade-work/batch2
fgrun() { ( export PT=$HOME/mc-porttest; [[ $2 == *nobasecloth* ]] && export NOBASECLOTH=1; source <(sed '/^CMDS=/,$d' $B/run-world.sh) >/dev/null 2>&1; run_one "$1" ${=3} ) }
while IFS=$'\t' read -r slug flags jars; do
  [[ -z $slug || $slug == \#* ]] && continue
  grep -qE "^(PASS|CRASH|HELD|STALL)	$slug	" $B/ledger.txt && continue
  echo "[$(date +%H:%M)] fox $slug"
  fgrun "$slug" "$flags" "$jars"
  pkill -f "gameDir $HOME/mc-porttest " 2>/dev/null; sleep 2
done < $B/h2h-groups.txt
echo "[$(date +%H:%M)] fox retry DONE"
echo "H2H2 FOX RETRY DONE" >> $B/ledger.txt
