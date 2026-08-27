#!/usr/bin/env node
// REAL ACCURACY TEST — against human ground truth, not self-assessment.
//
// "79% coverage" only meant 79% of imports resolved to a class that EXISTS. It said nothing about
// whether they resolved to the RIGHT class. To measure that we need ground truth.
//
// Twilight Forest maintains both a 1.16.x and a 1.20.x branch: the same mod, ported by humans.
// Forge switched to mojmap names at 1.17, so the 1.20 branch is already in mojmap — directly comparable
// to what our pipeline outputs. So:
//   for each file present in BOTH branches, take an import the human CHANGED,
//   ask our pipeline what it predicts, and compare to what the human actually wrote.
//
// Usage: node test-accuracy.mjs
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';

const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });
const REPO = process.argv[2] || 'TeamTwilight/twilightforest';
const OLD_REF = process.argv[3] || '1.16.x';
const NEW_REF = process.argv[4] || '1.20.x';
const JSON_OUT = process.argv.includes('--json');

function fetchRef(ref) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'acc-'));
  sh(`curl -sL "https://codeload.github.com/${REPO}/tar.gz/${encodeURIComponent(ref)}" | tar xz -C "${dir}"`);
  const roots = fs.readdirSync(dir);
  return roots.length ? path.join(dir, roots[0]) : null;
}
function walk(d, o = []) {
  if (!fs.existsSync(d)) return o;
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : (p.endsWith('.java') && o.push(p));
  }
  return o;
}
const importsOf = (s) => {
  const m = new Map();
  for (const x of s.matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) m.set(x[1], x[1].slice(x[1].lastIndexOf('.') + 1));
  return m;
};

// ---- our pipeline's resolver, exactly as the porter builds it ----
const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
const flat = new Map();
const addBlock = (b) => { for (const r of (b?.renames || [])) if (r.verified !== false && !flat.has(r.fromFqcn)) flat.set(r.fromFqcn, r.toFqcn); };
addBlock(d['scheme:mcp@1.16.5->mojmap']);
for (const k of ['1.16.5->1.17.1', '1.17.1->1.18.2', '1.18.2->1.19.2', '1.19.2->1.19.4', '1.19.4->1.20.1']) addBlock(d[k]);
// Halt-at-valid, mirroring the porter: stop once the name exists in the TARGET version, otherwise a
// chain can overshoot a correct answer into a later-version redesign.
const targetValid = (() => {
  const sh2 = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 }).stdout;
  try {
    const man = JSON.parse(sh2('curl -sL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json'));
    const want = (NEW_REF.match(/\d+\.\d+(\.\d+)?/) || [])[0];
    const e = man.versions.find((v) => v.id === want) || man.versions.find((v) => v.id.startsWith(want || '@@'));
    if (!e) return null;
    const meta = JSON.parse(sh2(`curl -sL "${e.url}"`) || '{}');
    const u = meta.downloads?.client_mappings?.url; if (!u) return null;
    const set = new Set();
    for (const l of (sh2(`curl -sL "${u}"`) || '').split('\n')) {
      const g = l.match(/^([\w.$]+) -> [\w.$]+:$/); if (g && !g[1].includes('$')) set.add(g[1]);
    }
    return set.size ? set : null;
  } catch { return null; }
})();
const resolve = (fq) => {
  let c = fq, seen = new Set();
  while (flat.has(c) && !seen.has(c)) {
    if (targetValid && targetValid.has(c) && c !== fq) return c;   // already valid in the target — stop
    seen.add(c); c = flat.get(c);
  }
  return c;
};

if (!JSON_OUT) console.log(`Fetching ${REPO} ${OLD_REF} (source) and ${NEW_REF} (human ground truth)...`);
const [oldRoot, newRoot] = [fetchRef(OLD_REF), fetchRef(NEW_REF)];
if (!oldRoot || !newRoot) { console.error('download failed'); process.exit(1); }
const key = (root, f) => { const rel = path.relative(root, f); const i = rel.lastIndexOf('src/main/java/'); return i >= 0 ? rel.slice(i + 14) : null; };
const oldF = new Map(walk(oldRoot).map((f) => [key(oldRoot, f), f]).filter(([k]) => k));
const newF = new Map(walk(newRoot).map((f) => [key(newRoot, f), f]).filter(([k]) => k));
let paired = [...oldF.keys()].filter((k) => newF.has(k)).map((k) => [oldF.get(k), newF.get(k)]);
// Some mods restructure their own packages wholesale between versions, so exact package paths stop
// matching. Fall back to unique-basename pairing so those repos still yield ground truth.
if (paired.length < 20) {
  const byBase = new Map();
  for (const [k, f] of newF) { const b = path.basename(k); if (!byBase.has(b)) byBase.set(b, []); byBase.get(b).push(f); }
  const seen = new Set(paired.map(([a]) => a));
  for (const [k, f] of oldF) {
    if (seen.has(f)) continue;
    const c = byBase.get(path.basename(k));
    if (c && c.length === 1) paired.push([f, c[0]]);   // unique basename only — never guess between duplicates
  }
}
if (!JSON_OUT) console.log(`  paired files: ${paired.length}\n`);

// Ground truth: within a paired file, an import the human REPLACED (same simple name, new package,
// or a 1:1 swap) tells us what that class became.
const truth = new Map();
for (const [oldPath, newPath] of paired) {
  const A = importsOf(fs.readFileSync(oldPath, 'utf8'));
  const B = importsOf(fs.readFileSync(newPath, 'utf8'));
  const removed = [...A.keys()].filter((x) => !B.has(x) && /^(net\.minecraft|com\.mojang)\./.test(x));
  const added = [...B.keys()].filter((x) => !A.has(x) && /^(net\.minecraft|com\.mojang)\./.test(x));
  for (const r of removed) {
    const hit = added.find((x) => B.get(x) === A.get(r));
    if (hit) { if (!truth.has(r)) truth.set(r, new Map()); truth.get(r).set(hit, (truth.get(r).get(hit) || 0) + 1); }
  }
  // ALSO capture true renames (different simple name) when the swap is unambiguous 1:1,
  // otherwise the ground truth is biased toward the easy same-name cases our movers already handle.
  const remLeft = removed.filter((r) => !added.some((x) => B.get(x) === A.get(r)));
  const addLeft = added.filter((x) => !removed.some((r) => A.get(r) === B.get(x)));
  if (remLeft.length === 1 && addLeft.length === 1) {
    const r = remLeft[0], hit = addLeft[0];
    if (!truth.has(r)) truth.set(r, new Map()); truth.get(r).set(hit, (truth.get(r).get(hit) || 0) + 1);
  }
}
// Keep only unanimous ground truth — and require CORROBORATION for the risky kind.
// A same-simple-name swap is structurally safe (the name is an invariant), so 1 sighting is fine.
// A different-name 1:1 swap is a guess unless several independent files agree; without this the
// ground truth fills with junk like BlockGetter->BlockPos and the test measures its own noise.
const gt = new Map();
let droppedWeak = 0;
for (const [from, tg] of truth) {
  if (tg.size !== 1) continue;
  const to = [...tg.keys()][0];
  const sightings = tg.get(to);
  const sameName = from.split('.').pop() === to.split('.').pop();
  if (!sameName && sightings < 2) { droppedWeak++; continue; }
  gt.set(from, to);
}
if (!JSON_OUT && droppedWeak) console.log(`  dropped ${droppedWeak} uncorroborated 1:1 guess(es) from ground truth`);
let sameName = 0, renamed = 0;
for (const [f, t] of gt) (f.split('.').pop() === t.split('.').pop() ? sameName++ : renamed++);
if (!JSON_OUT) console.log(`human ground-truth mappings (unanimous): ${gt.size}`);
if (!JSON_OUT) console.log(`  of which same-simple-name moves : ${sameName}  (the easy kind)`);
if (!JSON_OUT) console.log(`  of which TRUE renames           : ${renamed}  (the hard kind)\n`);

let correct = 0, wrong = 0, noPrediction = 0;
// Split by case difficulty — the headline is meaningless if easy and hard cases behave differently.
const byType = { easy: { ok: 0, bad: 0, none: 0 }, hard: { ok: 0, bad: 0, none: 0 } };
const wrongs = [];
for (const [from, expected] of gt) {
  const isEasy = from.split('.').pop() === expected.split('.').pop();
  const t = isEasy ? byType.easy : byType.hard;
  const got = resolve(from);
  if (got === from) { noPrediction++; t.none++; continue; }
  if (got === expected) { correct++; t.ok++; }
  else { wrong++; t.bad++; if (wrongs.length < 12) wrongs.push({ from, got, expected }); }
}
const predicted = correct + wrong;
if (!JSON_OUT) console.log(`=== ACCURACY vs HUMAN PORT (${REPO} ${OLD_REF} -> ${NEW_REF}) ===`);
console.log(`  ground-truth mappings tested : ${gt.size}`);
console.log(`  our pipeline made a prediction: ${predicted}`);
console.log(`    CORRECT (matches the human) : ${correct}`);
console.log(`    WRONG                       : ${wrong}`);
console.log(`  no prediction (left for AI)   : ${noPrediction}`);
console.log(`\n  ACCURACY (of predictions made): ${predicted ? Math.round(correct / predicted * 100) : 0}%`);
console.log(`  RECALL   (of all changes)     : ${gt.size ? Math.round(correct / gt.size * 100) : 0}%`);
if (wrongs.length) {
  console.log('\n  --- disagreements with the human ---');
  for (const w of wrongs) console.log(`   ${w.from}\n     ours:  ${w.got}\n     human: ${w.expected}`);
}
if (JSON_OUT) console.log('RESULT ' + JSON.stringify({ repo: REPO, oldRef: OLD_REF, newRef: NEW_REF,
  gt: gt.size, droppedWeak, sameName, renamed, predicted, correct, wrong, noPrediction, byType,
  accuracy: predicted ? correct / predicted : null }));
sh(`rm -rf "${path.dirname(oldRoot)}" "${path.dirname(newRoot)}"`);
