#!/bin/zsh
# End-of-run pass: every Fox-Grade group that did not PASS is rerun with the FINAL jar (its old verdict is dropped
# first, so the resumable loop revisits it); Retromod groups missing from its ledger (purged as tainted) are run once.
setopt NULL_GLOB
B=~/foxgrade-work/batch2
source <(sed -n '/^fgrun()/,/^}/p; /^rmrun()/,/^}/p; /^loop()/,/^}/p' $B/run-h2h2.sh)
# round-2 mods that failed earlier get the final jar too (their jars live in h2h/ root)
R2=$B/h2h-round2-groups.txt
{ echo "jei	-	$(ls $B/h2h/jei--*.jar)"; echo "waystones	-	$(ls $B/h2h/waystones--*.jar) $(ls $B/h2h/balm--*.jar)"; echo "rei	nobasecloth	$(ls $B/h2h/rei--*.jar) $(ls $B/h2h/architectury-api--*.jar) $(ls $B/h2h/cloth-config--*.jar 2>/dev/null)"; } > $R2
cat $R2 >> $B/h2h-groups.txt
for s in $(cut -f1 $B/h2h-groups.txt); do
  v=$(grep -E "^(PASS|CRASH|HELD|STALL)	$s	" $B/ledger.txt | tail -1 | cut -f1)
  [[ -n $v && $v != PASS ]] && sed -i '' -E "/^(CRASH|HELD|STALL)	$s	/d" $B/ledger.txt
  # a PASS whose port deregistered mixins may have run under the old "empty the whole config" behaviour: rerun it too
  [[ $v == PASS ]] && grep -E "^PASS	$s	" $B/ledger.txt | tail -1 | grep -q "mixinsDeregistered=" && sed -i '' -E "/^PASS	$s	/d" $B/ledger.txt
done
loop fox > $B/h2h2-fox-retry.log 2>&1 &
loop retro > $B/h2h2-retro-retry.log 2>&1 &
wait
echo "H2H2 RETRY DONE" >> $B/ledger.txt
