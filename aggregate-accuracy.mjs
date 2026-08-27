#!/usr/bin/env node
// Aggregate the multi-mod accuracy suite.
// Reports BOTH averages, because they answer different questions:
//   micro (pooled)  — every mapping counts once; dominated by big mods
//   macro (per-mod) — every mod counts equally; shows consistency across codebases
// Also splits easy (same-simple-name move) vs hard (true rename) so the headline isn't flattered by
// a corpus that is mostly easy cases.
import fs from 'node:fs';

const raw = fs.readFileSync(process.argv[2] || '/dev/stdin', 'utf8');
const rows = [];
for (const line of raw.split('\n')) {
  const i = line.indexOf('{');
  if (i < 0) continue;
  try { const o = JSON.parse(line.slice(i)); if (o.repo) rows.push(o); } catch {}
}
if (!rows.length) { console.log('no results parsed'); process.exit(0); }

console.log('mod'.padEnd(34), 'gt'.padStart(5), 'easy'.padStart(5), 'hard'.padStart(5), 'ok'.padStart(5), 'wrong'.padStart(6), 'acc'.padStart(7));
let C = 0, W = 0, GT = 0, EASY = 0, HARD = 0;
const accs = [];
for (const r of rows.sort((a, b) => (b.accuracy ?? -1) - (a.accuracy ?? -1))) {
  const acc = r.predicted ? r.correct / r.predicted : null;
  if (acc !== null) accs.push(acc);
  C += r.correct || 0; W += r.wrong || 0; GT += r.gt || 0; EASY += r.sameName || 0; HARD += r.renamed || 0;
  console.log(
    r.repo.split('/')[1].slice(0, 33).padEnd(34),
    String(r.gt ?? 0).padStart(5), String(r.sameName ?? 0).padStart(5), String(r.renamed ?? 0).padStart(5),
    String(r.correct ?? 0).padStart(5), String(r.wrong ?? 0).padStart(6),
    (acc === null ? '—' : (acc * 100).toFixed(1) + '%').padStart(7));
}
const micro = C + W ? C / (C + W) : 0;
const macro = accs.length ? accs.reduce((a, b) => a + b, 0) / accs.length : 0;
console.log('\n=== AGGREGATE ===');
console.log(`  mods tested            : ${rows.length}  (${accs.length} produced predictions)`);
console.log(`  ground-truth mappings  : ${GT}   (${EASY} easy same-name moves · ${HARD} TRUE renames)`);
console.log(`  correct / wrong        : ${C} / ${W}`);
console.log(`\n  MICRO average (pooled) : ${(micro * 100).toFixed(1)}%   <- every mapping counts once`);
console.log(`  MACRO average (per-mod): ${(macro * 100).toFixed(1)}%   <- every mod counts equally`);
if (accs.length > 1) {
  const sorted = [...accs].sort((a, b) => a - b);
  console.log(`  worst mod / best mod   : ${(sorted[0] * 100).toFixed(1)}% / ${(sorted[sorted.length - 1] * 100).toFixed(1)}%`);
}
// The headline average hides everything if easy and hard cases behave differently — split them.
let eOk = 0, eBad = 0, hOk = 0, hBad = 0;
for (const r of rows) {
  if (!r.byType) continue;
  eOk += r.byType.easy.ok; eBad += r.byType.easy.bad;
  hOk += r.byType.hard.ok; hBad += r.byType.hard.bad;
}
if (eOk + eBad + hOk + hBad) {
  console.log('\n=== ACCURACY BY CASE DIFFICULTY (the number that actually matters) ===');
  const ea = eOk + eBad ? eOk / (eOk + eBad) : null;
  const ha = hOk + hBad ? hOk / (hOk + hBad) : null;
  console.log(`  EASY  same-simple-name moves : ${eOk}/${eOk + eBad}  = ${ea === null ? '—' : (ea * 100).toFixed(1) + '%'}`);
  console.log(`  HARD  true renames           : ${hOk}/${hOk + hBad}  = ${ha === null ? '—' : (ha * 100).toFixed(1) + '%'}`);
  console.log(`\n  ${Math.round(EASY / Math.max(1, GT) * 100)}% of ground truth is the EASY kind, so the pooled average mostly`);
  console.log('  reflects the mover. The HARD number is the honest measure of true renames.');
}
