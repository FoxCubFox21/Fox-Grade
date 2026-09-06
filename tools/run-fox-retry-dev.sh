#!/bin/zsh
# Second Fox-Grade retry worker on ~/mc-porttest-dev: walks the group list BACKWARDS so it meets the forward worker in the middle.
B=$HOME/foxgrade-work/batch2
fgrun() { ( export PT=$HOME/mc-porttest-dev; [[ $2 == *nobasecloth* ]] && export NOBASECLOTH=1; source <(sed '/^CMDS=/,$d' $B/run-world.sh) >/dev/null 2>&1; run_one "$1" ${=3} ) }
tail -r $B/h2h-groups.txt | while IFS=$'\t' read -r slug flags jars; do
  [[ -z $slug || $slug == \#* ]] && continue
  grep -qE "^(PASS|CRASH|HELD|STALL)	$slug	" $B/ledger.txt && continue
  grep -q "fox $slug\$" $B/h2h2-fox-retry.log && continue     # the forward worker is on it right now
  echo "[$(date +%H:%M)] fox-dev $slug"
  fgrun "$slug" "$flags" "$jars"
  pkill -f "gameDir $HOME/mc-porttest-dev " 2>/dev/null; sleep 2
done
echo "[$(date +%H:%M)] fox-dev retry DONE"
