#!/usr/bin/env node
// Bridge the mined ladder to the real target version.
//
// The wild miner built a ladder of human-shipped migrations spanning ~1.7 -> 1.21, but GitHub has no
// "1.21 -> 26.2" port to mine, so those chains dead-end one hop short and can't help a 26.2 port.
// This closes the gap WITHOUT AI: take every class the ladder terminates at, and resolve it against the
// REAL target jars — exists as-is, moved (unique simple-name match), or gone.
//
// Usage: node bridge-rules.mjs --to 26.2 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const toVer = args.to || '26.2';
const cp = args.classpath || fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim();

// Real class index of the target
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

const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
// Terminal classes: every toFqcn that is not itself the source of another rule (the ladder's tips).
const sources = new Set(), tips = new Set();
for (const [k, v] of Object.entries(d)) {
  if (k.startsWith('_') || k === toVer) continue;
  for (const r of v.renames || []) { sources.add(r.fromFqcn); tips.add(r.toFqcn); }
}
const terminal = [...tips].filter((t) => !sources.has(t));
console.log(`Ladder tips (classes the mined chains end at): ${terminal.length}`);

const already = [], bridged = [], ambiguous = [], gone = [];
const GENERIC = /(^|\.)(util|init|src|common)(\.|$)/;
const domain = (fq) => new Set(fq.split('.').slice(0, -1).filter((w) => !/^(net|minecraft|com|mojang|fabricmc|world|core|api)$/.test(w)));
for (const t of terminal) {
  if (allFqcn.has(t)) { already.push(t); continue; }          // still valid in the target — chain already lands
  const s = t.slice(t.lastIndexOf('.') + 1);
  const c = bySimple.get(s) || [];
  if (c.length !== 1) { (c.length ? ambiguous : gone).push(t); continue; }
  const a = domain(t), b = domain(c[0]);
  const shared = [...a].some((w) => b.has(w)) || GENERIC.test(t.slice(0, t.lastIndexOf('.')));
  if (shared) bridged.push({ from: t, to: c[0] });
  else ambiguous.push(t);
}
console.log(`  already valid in ${toVer}      : ${already.length}`);
console.log(`  BRIDGED (moved, unique match) : ${bridged.length}`);
console.log(`  ambiguous / cross-domain      : ${ambiguous.length}`);
console.log(`  gone (deleted or renamed)     : ${gone.length}`);
console.log('\n  --- sample bridges ---');
for (const b of bridged.slice(0, 12)) console.log(`   ${b.from}\n       -> ${b.to}`);

if (args.write && bridged.length) {
  d[toVer] = d[toVer] || { renames: [], advisories: [], deleted: [] };
  const have = new Set(d[toVer].renames.map((r) => r.fromFqcn));
  let n = 0;
  for (const b of bridged) {
    if (have.has(b.from)) continue;
    have.add(b.from);
    const s = b.from.split('.').pop();
    d[toVer].renames.push({
      fromFqcn: b.from, toFqcn: b.to, fromSimple: s, toSimple: b.to.split('.').pop(),
      verified: true, chainable: true, kind: 'move',
      source: 'bridge', verifiedOn: toVer,
      note: `final hop: ladder tip resolved against the real ${toVer} jars`,
    });
    n++;
  }
  fs.writeFileSync('rules.json', JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${n} bridge rule(s) into "${toVer}" (total ${d[toVer].renames.length}).`);
} else if (bridged.length) console.log('\n(dry run — pass --write)');
