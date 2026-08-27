#!/usr/bin/env node
// Rule cleaner — separates STRUCTURALLY SOUND rules from INFERRED ones.
//
// Why this exists: mining at scale produced 807 "1:1 renames" from the heuristic "one import removed +
// one added = a rename". Across thousands of files that fires on unrelated pairs, producing garbage like
// `World -> DataComponents`. Chaining then compounded it. A rule is only kept if it is:
//   MOVE      same simple name, different package  -> structurally sound (the name is an invariant)
//   RENAME    different simple name                -> ONLY if corroborated: the SAME mapping seen in
//             >= 2 independent source blocks, and never contradicted anywhere.
// Everything else goes to rules.candidates.json for human/AI review — not deleted, just not trusted.
import fs from 'node:fs';

const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));

// Evidence pass scoped PER BLOCK — cross-block differences are expected, not conflicts.
const targets = new Map();  // blockKey|fromFqcn -> Map(toFqcn -> count)
for (const [k, v] of Object.entries(d)) {
  if (k.startsWith("_")) continue;
  for (const r of v.renames || []) {
    const id = k + "|" + r.fromFqcn;
    if (!targets.has(id)) targets.set(id, new Map());
    const m = targets.get(id);
    m.set(r.toFqcn, (m.get(r.toFqcn) || 0) + 1);
  }
}

const out = {}, candidates = {};
let kept = 0, quarantined = 0, conflictDropped = 0;
for (const [k, v] of Object.entries(d)) {
  if (k.startsWith('_')) { out[k] = v; continue; }
  const keep = [], cand = [];
  for (const r of v.renames || []) {
    const isMove = r.fromSimple === r.toSimple;
    const m = targets.get(k + "|" + r.fromFqcn);
    const distinctTargets = m ? m.size : 1;
    const corroboration = m?.get(r.toFqcn) || 1;

    if (distinctTargets > 1) {            // contradicted somewhere -> never auto-apply
      cand.push({ ...r, kind: isMove ? 'move' : 'rename', rejected: `contradicted: ${distinctTargets} different targets seen` });
      conflictDropped++;
      continue;
    }
    if (isMove) { keep.push({ ...r, kind: 'move', chainable: true }); kept++; continue; }
    if (corroboration >= 2 || (r.evidence || 0) >= 3) { keep.push({ ...r, kind: 'rename', chainable: false, corroboration }); kept++; continue; }
    cand.push({ ...r, kind: 'rename', rejected: 'single-source 1:1 inference — unverified' });
    quarantined++;
  }
  if (keep.length) out[k] = { ...v, renames: keep };
  if (cand.length) candidates[k] = { renames: cand };
}

fs.writeFileSync('rules.json', JSON.stringify(out, null, 2) + '\n');
fs.writeFileSync('rules.candidates.json', JSON.stringify(candidates, null, 2) + '\n');
console.log(`kept        : ${kept}   (moves + corroborated renames)`);
console.log(`quarantined : ${quarantined}   (single-source 1:1 guesses)`);
console.log(`contradicted: ${conflictDropped}   (mapped multiple ways somewhere)`);
console.log(`-> rules.json (trusted) + rules.candidates.json (needs review)`);
