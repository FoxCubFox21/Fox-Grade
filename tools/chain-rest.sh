#!/bin/zsh
# Finish the measurement queue in order, one game at a time: two clients on one machine distort each other, and a
# distorted run has already made a whole lane look broken once.
set -u
B=~/foxgrade-work/batch2
cd $B
until grep -q "NEOFORGE RUN DONE" ledger-neoforge.txt 2>/dev/null; do sleep 20; done
echo "[chain] neoforge done: $(grep -c '^PASS' ledger-neoforge.txt) pass"

# 1.21.2 again, on an instance rebuilt with a loader new enough for the floors mods actually declare.
python3 ~/foxgrade-work/make-instance.py 1.21.2 >> /tmp/fg-chain-rest.log 2>&1
cp ledger-v1_21_2.txt ledger-v1_21_2.before-apifloor.txt
rm -f ledger-v1_21_2.txt log-v1_21_2-*.log
./run-version.sh 1.21.2 18 h2h/*.jar >> /tmp/fg-chain-rest.log 2>&1
echo "[chain] 1.21.2 rerun: $(grep -c '^PASS' ledger-v1_21_2.txt) pass"

# Forge, which has never had a corpus run against it at all.
rm -f ledger-forge.txt log-forge-*.log
./run-forge.sh >> /tmp/fg-chain-rest.log 2>&1
echo "[chain] forge: $(grep -c '^PASS' ledger-forge.txt) pass of $(grep -cE '^(PASS|CRASH|STALL|HELD)' ledger-forge.txt)"
# Everything above measures versions that already had numbers. This is the breadth axis: every version whose tables
# exist but which has never had a corpus run against it, which is the only thing that lets it into Targets.SUPPORTED.
# Long — roughly 40 minutes a version — and safe to interrupt, since measure-all.sh skips versions already measured.
./measure-all.sh 1.21.1 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 \
                 1.20.1 1.20.2 1.20.4 1.20.5 1.20.6 1.19.2 1.19.4 1.18.2 1.17.1 1.16.5 1.15.2 1.14.4 \
                 >> /tmp/fg-measure-all.log 2>&1
echo "[chain] ALL DONE"
