#!/usr/bin/env node
// Package rules THIS install discovered, so they can be contributed back to the shared set.
//
// Design rule, learned the hard way: a rule is only worth sharing if it carries PROOF.
// Every entry records how it was established, and unproven kinds are refused outright.
// Nothing about your mod is included — only "old class name -> new class name" facts.
//
//   node export-learned.mjs --to 26.2 --out my-rules.json
//   (then open a pull request; CI re-verifies before anything is merged)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const OUT = args.out || 'my-rules.json';

// Only these provenances are contributable. Everything else stays local.
const PROVEN = {
  'repair-diff':            'a failing compile became a passing compile after this change',
  'direct-jar-resolution':  'resolved against the real target jars',
  'bridge':                 'resolved against the real target jars',
  'official-mappings':      'derived from Mojang/Fabric/MCPConfig published data',
  'human-groundtruth':      'observed in a port a human actually shipped',
  'rendering-audit':        'hand-verified against javap output',
};
const REFUSED = ['wild', 'structural-matcher', 'inference'];

const rules = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
const out = { schema: 1, target: args.to || '26.2', generated: 'local-export', entries: [] };
let refused = 0, dupes = 0;
const seen = new Set();

for (const [block, v] of Object.entries(rules)) {
  if (block.startsWith('_')) continue;
  for (const r of (v.renames || [])) {
    const src = String(r.source || '');
    const kind = Object.keys(PROVEN).find((k) => src.startsWith(k));
    if (!kind || REFUSED.some((b) => src.startsWith(b))) { refused++; continue; }
    const key = `${r.fromFqcn}>${r.toFqcn}`;
    if (seen.has(key)) { dupes++; continue; }
    seen.add(key);
    out.entries.push({
      from: r.fromFqcn, to: r.toFqcn, block,
      proof: kind, why: PROVEN[kind],
      evidence: r.evidence || r.provenance || undefined,
    });
  }
}

fs.writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n');
console.log(`  ${OUT}`);
console.log(`    contributable : ${out.entries.length}  (each carries its proof)`);
console.log(`    refused       : ${refused}  (inference/matcher — not provable, stays local)`);
console.log(`    duplicates    : ${dupes}`);
console.log('\n  Contains only class-name pairs. No mod source, no file names, no paths.');
