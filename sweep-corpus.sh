#!/bin/zsh
# Re-measure the 18-mod corpus with every fix that has landed since the last sweep.
#
# Two things made the previous numbers wrong, and both made them look WORSE than reality:
#
#   1. sweep3 never passed --corpus to jar-verify. Fabric API adds methods to vanilla classes by
#      mixin, so they exist in no jar on the classpath and were all counted as broken links.
#   2. `L=${L:-0}` turned "the measurement failed" into "0 links", which then printed as CLEAN.
#      A crashed verify and a perfect port were indistinguishable in the output.
#
# So here a missing measurement is ERR, never 0, and the verdict is withheld unless every number
# was actually obtained. Links are measured before AND after, because "12 links" means nothing
# without knowing whether it started at 12 or at 400.
cd "/Users/cassiusmehlhopt/Library/Mobile Documents/com~apple~CloudDocs/Coding/foxgrade"
S=/private/tmp/claude-501/-Users-cassiusmehlhopt/0eb3c942-b1be-4cca-8473-756df2995ecd/scratchpad
CP=$(cat /tmp/foxgrade_cp.txt); SRC="$S/mc-26.1.jar"; C="$HOME/foxgrade-corpus"
mkdir -p $S/p4

# Pull the number out, but report an empty result as ERR rather than silently as zero.
num(){ v=$(print -r -- "$1" | grep -oE "$2" | grep -oE '[0-9]+' | head -1); print -r -- "${v:-ERR}"; }

printf "%-22s %7s %7s %7s %7s %s\n" mod links_in links_out mix_in mix_out verdict
for f in $S/v261/*-26.1.jar; do
  m=$(basename $f -26.1.jar)
  L0=$(num "$(node jar-verify.mjs "$f" --classpath "$CP" --corpus "$C" 2>/dev/null)" '^  [0-9]+ link')
  # every link resolves -> jar-verify prints no count line at all, which is 0, not a failure
  [[ "$L0" == "ERR" ]] && node jar-verify.mjs "$f" --classpath "$CP" --corpus "$C" 2>/dev/null | grep -q 'every link resolves' && L0=0
  MX=$(node mixin-check.mjs "$f" --classpath "$CP" --source-classpath "$SRC" 2>/dev/null)
  M0=$(num "$MX" 'PROBLEMS *: *[0-9]+')
  # "no mixins in this jar" is not a failed measurement, it is zero mixin problems. fabric-api keeps
  # its mixins in bundled sub-jars, so the top-level jar has none and printed ERR on a clean result.
  [[ "$M0" == "ERR" ]] && print -r -- "$MX" | grep -q 'no mixins in this jar' && M0=0

  node jar-remap.mjs "$f" --from 26.1 --to 26.2 --classpath "$CP" --best-guess --out "$S/p4/$m.jar" >/dev/null 2>&1
  if [[ ! -f "$S/p4/$m.jar" ]]; then
    printf "%-22s %7s %7s %7s %7s %s\n" "$m" "$L0" "ERR" "$M0" "ERR" "remap failed"
    continue
  fi
  node mixin-check.mjs "$S/p4/$m.jar" --classpath "$CP" --source-classpath "$SRC" --out "$S/p4/$m.f.jar" >/dev/null 2>&1
  [[ -f "$S/p4/$m.f.jar" ]] && mv "$S/p4/$m.f.jar" "$S/p4/$m.jar"

  L1=$(num "$(node jar-verify.mjs "$S/p4/$m.jar" --classpath "$CP" --corpus "$C" 2>/dev/null)" '^  [0-9]+ link')
  [[ "$L1" == "ERR" ]] && node jar-verify.mjs "$S/p4/$m.jar" --classpath "$CP" --corpus "$C" 2>/dev/null | grep -q 'every link resolves' && L1=0
  MX1=$(node mixin-check.mjs "$S/p4/$m.jar" --classpath "$CP" --source-classpath "$SRC" 2>/dev/null)
  M1=$(num "$MX1" 'PROBLEMS *: *[0-9]+')
  [[ "$M1" == "ERR" ]] && print -r -- "$MX1" | grep -q 'no mixins in this jar' && M1=0

  if [[ "$L1" == "ERR" || "$M1" == "ERR" ]]; then V="NOT MEASURED"
  elif [[ "$L1" == "0" && "$M1" == "0" ]]; then V="CLEAN"
  elif [[ "$M1" == "0" ]]; then V="links only"
  else V="mixins blocked"; fi
  printf "%-22s %7s %7s %7s %7s %s\n" "$m" "$L0" "$L1" "$M0" "$M1" "$V"
done
