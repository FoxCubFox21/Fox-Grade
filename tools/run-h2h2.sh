#!/bin/zsh
# 100-mod head-to-head: Fox-Grade on one instance, Retromod on a second, in parallel, over h2h-groups.txt.
setopt NULL_GLOB
B=~/foxgrade-work/batch2
fgrun() { ( export PT=$HOME/mc-porttest; [[ $2 == *nobasecloth* ]] && export NOBASECLOTH=1; source <(sed '/^CMDS=/,$d' $B/run-world.sh) >/dev/null 2>&1; run_one "$1" ${=3} ) }
rmrun() { ( export PT=$HOME/mc-porttest-rm; [[ $2 == *nobasecloth* ]] && export NOBASECLOTH=1; source <(sed '/^rm_run modmenu/,$d' $B/run-retromod.sh) >/dev/null 2>&1; rm_run "$1" ${=3} ) }
loop() {
  local tool=$1
  while IFS=$'\t' read -r slug flags jars; do
    [[ -z $slug || $slug == \#* ]] && continue
    if [[ $tool == fox ]]; then grep -qE "^(PASS|CRASH|HELD|STALL)	$slug	" $B/ledger.txt && continue; else grep -qE "^(PASS|CRASH)	$slug	" $B/retromod-ledger.txt && continue; fi
    echo "[$(date +%H:%M)] $tool $slug"
    if [[ $tool == fox ]]; then fgrun "$slug" "$flags" "$jars"; else rmrun "$slug" "$flags" "$jars"; fi
  done < $B/h2h-groups.txt
  echo "[$(date +%H:%M)] $tool DONE"
}
loop fox > $B/h2h2-fox.log 2>&1 &
loop retro > $B/h2h2-retro.log 2>&1 &
wait
echo "H2H2 DONE" >> $B/ledger.txt
