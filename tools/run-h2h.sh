#!/bin/zsh
# Head-to-head on a fresh mod set: each group goes through Fox-Grade (world harness, screenshot at
# tick 220) and then through Retromod (two launches), same instance, same base jars. The two
# harness scripts are sourced in separate subshells so their function/variable names never mix.
setopt NULL_GLOB
B=~/foxgrade-work/batch2
fgrun() { ( source <(sed '/^CMDS=/,$d' $B/run-world.sh) >/dev/null 2>&1; run_one "$@" ) }
rmrun() { ( source <(sed '/^rm_run modmenu/,$d' $B/run-retromod.sh) >/dev/null 2>&1; rm_run "$@" ) }
h2h() { echo "[$(date +%H:%M)] fox-grade $1"; fgrun "$@"; echo "[$(date +%H:%M)] retromod $1"; rmrun "$@"; }
h2h appleskin $B/h2h/appleskin--*.jar
h2h dynamic-fps $B/h2h/dynamic-fps--*.jar
h2h no-chat-reports $B/h2h/no-chat-reports--*.jar
h2h ferrite-core $B/h2h/ferrite-core--*.jar
h2h trinkets $B/h2h/trinkets--*.jar
h2h inventory-profiles-next $B/h2h/inventory-profiles-next--*.jar
h2h xaeros-world-map $B/h2h/xaeros-world-map--*.jar
h2h waystones $B/h2h/waystones--*.jar $B/h2h/balm--*.jar
h2h rei $B/h2h/rei--*.jar $B/h2h/architectury-api--*.jar
h2h jei $B/h2h/jei--*.jar
echo "H2H DONE" >> $B/ledger.txt; echo "H2H DONE" >> $B/retromod-ledger.txt
