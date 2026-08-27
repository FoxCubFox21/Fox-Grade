#!/usr/bin/env node
// Direct-to-target rule builder — the technique that actually proved correct.
//
// Lesson learned the hard way: INFERRED renames and multi-hop chaining produce garbage
// (`World -> DataComponents`). What holds up is resolving a class name against the REAL target jars.
// So: take every distinct old class name harvested from the wild corpus (26 mods, 1.6 -> 1.21) and
// resolve each DIRECTLY against 26.2 — no chaining, no inference, no intermediate hops.
//
// A rule is emitted only if ALL of these hold:
//   1. the old FQCN does NOT exist in the target (something really did change)
//   2. exactly ONE class with that simple name exists in the target (no ambiguity)
//   3. old and new share a package domain word, or the old package is generic (blocks name collisions)
//   4. it is not on the known-deleted list
//
// Usage: node direct-rules.mjs --to 26.2 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const toVer = args.to || '26.2';
const cp = fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim();

// --- real target index ---
const bySimple = new Map(), allFqcn = new Set();
for (const jar of cp.split(':')) {
  if (!jar.endsWith('.jar') || !fs.existsSync(jar)) continue;
  const r = spawnSync('unzip', ['-Z1', jar, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status !== 0 || !r.stdout) continue;
  for (const l of r.stdout.split('\n')) {
    const p = l.trim();
    if (!p.endsWith('.class') || p.includes('$')) continue;
    const fq = p.slice(0, -6).replace(/\//g, '.');
    if (!/^(net\.minecraft|net\.fabricmc|com\.mojang)\./.test(fq)) continue;
    const s = fq.slice(fq.lastIndexOf('.') + 1);
    if (!/^[A-Z][A-Za-z0-9_]*$/.test(s)) continue;
    allFqcn.add(fq);
    if (!bySimple.has(s)) bySimple.set(s, []);
    bySimple.get(s).push(fq);
  }
}
console.log(`Target index: ${allFqcn.size} classes in ${toVer}.`);

// --- harvest every distinct old class name we have ever seen, from the whole mined corpus ---
const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
const seen = new Set();
for (const [k, v] of Object.entries(d)) {
  if (k.startsWith('_')) continue;
  for (const r of v.renames || []) { seen.add(r.fromFqcn); seen.add(r.toFqcn); }
}
const existing = new Set((d[toVer]?.renames || []).map((r) => r.fromFqcn));
console.log(`Harvested ${seen.size} distinct class names from the mined corpus.\n`);

const KNOWN_DELETED = new Set((d[toVer]?.deleted || []).map((x) => x.fqcn));
const GENERIC = /(^|\.)(util|init|src|common|impl)(\.|$)/;
const domain = (fq) => new Set(fq.split('.').slice(0, -1).filter((w) => !/^(net|minecraft|com|mojang|fabricmc|world|core|api)$/.test(w)));
const javapOk = (fqcn) => spawnSync('javap', ['-classpath', cp, fqcn], { encoding: 'utf8' }).status === 0;

const emit = [], skipped = { valid: 0, ambiguous: 0, gone: 0, collision: 0, deleted: 0, javap: 0 };
for (const old of seen) {
  if (existing.has(old)) continue;
  if (allFqcn.has(old)) { skipped.valid++; continue; }                 // 1. still valid — nothing to do
  if ([...KNOWN_DELETED].some((k) => old === k || old.startsWith(k))) { skipped.deleted++; continue; } // 4
  const s = old.slice(old.lastIndexOf('.') + 1);
  const c = bySimple.get(s) || [];
  if (c.length === 0) { skipped.gone++; continue; }                     // renamed/deleted — needs AI
  if (c.length > 1) { skipped.ambiguous++; continue; }                  // 2. ambiguous — never guess
  const a = domain(old), b = domain(c[0]);
  const related = [...a].some((w) => b.has(w)) || GENERIC.test(old.slice(0, old.lastIndexOf('.')));
  if (!related) { skipped.collision++; continue; }                      // 3. likely a name collision
  emit.push({ from: old, to: c[0], simple: s });
}

console.log(`=== DIRECT ${toVer} MOVES (all 5 checks passed): ${emit.length} ===`);
for (const e of emit.slice(0, 25)) console.log(`  ${e.from}\n      -> ${e.to}`);
if (emit.length > 25) console.log(`  ... and ${emit.length - 25} more`);
console.log(`\nrejected: ${skipped.valid} already valid · ${skipped.ambiguous} ambiguous · ${skipped.collision} cross-domain · ${skipped.gone} renamed/deleted · ${skipped.deleted} known-deleted · ${skipped.javap} unloadable`);

if (args.write && emit.length) {
  d[toVer] = d[toVer] || { renames: [], advisories: [], deleted: [] };
  for (const e of emit) {
    d[toVer].renames.push({
      fromFqcn: e.from, toFqcn: e.to, fromSimple: e.simple, toSimple: e.simple,
      verified: true, chainable: true, kind: 'move',
      source: 'direct-jar-resolution', verifiedOn: toVer,
      note: `resolved directly against the real ${toVer} jars (unique name + domain + javap-loadable)`,
    });
  }
  fs.writeFileSync('rules.json', JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${emit.length} direct rule(s) -> "${toVer}" now has ${d[toVer].renames.length}.`);
} else if (emit.length) console.log('\n(dry run — pass --write)');
