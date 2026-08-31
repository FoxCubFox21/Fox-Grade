#!/usr/bin/env node
// Find classes that MOVED PACKAGE between two Minecraft jars.
//
// This was the gap. member-mine diffs the members of classes that exist in both versions; jar-mine
// and promote-ladder read class renames out of mod ports. Nothing diffed the CLASS LISTS of the two
// game jars directly, so a package move nobody's mod happened to demonstrate was simply never found —
// and 26.1 -> 26.2 moved net.minecraft.advancements.Criterion into advancements.triggers, which
// breaks every recipe-builder call that mentions it, in every content mod.
//
// A move shows up as a descriptor mismatch rather than a missing name, which is why it hid: the
// method unlockedBy(String, Criterion) still exists, just with a differently-packaged Criterion, so
// "is this member present" answers no for a reason that looks nothing like a package move.
//
// The signal is a simple name that vanished from one package and appeared in exactly one other. That
// is only evidence when it is unique on both sides — Minecraft has many classes called Builder.
//
//   node class-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --from 26.1 --to 26.2
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';
import { indexFromProguard } from './proguard.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
if ((!args.old && !args['old-mappings']) || (!args.new && !args['new-mappings'])) {
  console.error('usage: node class-mine.mjs --old <jar|--old-mappings f.txt> --new <jar|--new-mappings f.txt> --from 1.21.1 --to 26.2');
  process.exit(2);
}

function classesOf(jar) {
  const names = new Set(), members = new Map();
  for (const e of readZip(fs.readFileSync(jar))) {
    if (!e.name.endsWith('.class')) continue;
    const n = e.name.slice(0, -6);
    names.add(n);
    try { members.set(n, new Set(new ClassFile(inflateEntry(e)).declared().members.map((m) => `${m.name}\t${m.desc}`))); } catch { /* skip */ }
  }
  return { names, members };
}
// An obfuscated version has no readable class names in its jar, so the package moves this whole
// tool exists to find are invisible there. Mojang's published mappings carry the same information
// in readable form, and the diff below cannot tell the difference.
function classesFromMappings(file) {
  const idx = indexFromProguard(file);
  const names = new Set(idx.keys()), members = new Map();
  for (const [n, ms] of idx) members.set(n, new Set(ms.map((m) => `${m.name}\t${m.desc}`)));
  return { names, members };
}
const side = (jar, maps) => {
  const r = maps ? classesFromMappings(maps) : classesOf(jar);
  console.log(`  ${path.basename(maps || jar)}: ${r.names.size.toLocaleString()} classes${maps ? '  (from published mappings)' : ''}`);
  return r;
};
const A = side(args.old, args['old-mappings']);
const B = side(args.new, args['new-mappings']);

const gone = [...A.names].filter((n) => !B.names.has(n));
const arrived = [...B.names].filter((n) => !A.names.has(n));
const simple = (n) => n.slice(n.lastIndexOf('/') + 1);

// Group by simple name. A move is only unambiguous when exactly one class of that name left and
// exactly one arrived; Minecraft has dozens of Builder and Type classes and pairing them by name
// alone would be guesswork dressed as evidence.
const goneBy = new Map(), arrBy = new Map();
for (const n of gone) { const k = simple(n); if (!goneBy.has(k)) goneBy.set(k, []); goneBy.get(k).push(n); }
for (const n of arrived) { const k = simple(n); if (!arrBy.has(k)) arrBy.set(k, []); arrBy.get(k).push(n); }

const moves = [], ambiguous = [], weak = [];
for (const [name, gs] of goneBy) {
  const as = arrBy.get(name);
  if (!as) continue;
  if (gs.length !== 1 || as.length !== 1) { ambiguous.push([name, gs.length, as.length]); continue; }
  // Same simple name in a different package is suggestive, not conclusive — two unrelated classes
  // can share a name. Require the members to agree as well, which is what makes it the same class.
  const am = A.members.get(gs[0]) || new Set(), bm = B.members.get(as[0]) || new Set();
  const shared = [...am].filter((m) => bm.has(m)).length;
  const ratio = am.size ? shared / am.size : 0;
  const rec = { from: gs[0].replace(/\//g, '.'), to: as[0].replace(/\//g, '.'), shared, of: am.size, ratio: +ratio.toFixed(2) };
  // Small classes are mostly constructors and accessors, so a high ratio there is cheap; require
  // an absolute floor as well as a proportion.
  if (ratio >= 0.5 && (shared >= 3 || am.size <= 3)) moves.push(rec); else weak.push(rec);
}
// Classes that were RENAMED, not just moved. The same-simple-name rule cannot see these by
// construction, and 26.1 -> 26.2 fixed a typo — InstantenousMobEffect became InstantaneousMobEffect
// — which broke every mod referencing it. Here the members carry the argument and the name only
// corroborates: two classes in the same package with the same members are the same class.
function commonRun(a, b) {
  a = a.toLowerCase(); b = b.toLowerCase();
  let best = 0, prev = new Array(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    const cur = new Array(b.length + 1).fill(0);
    for (let j = 1; j <= b.length; j++) if (a[i - 1] === b[j - 1]) { cur[j] = prev[j - 1] + 1; if (cur[j] > best) best = cur[j]; }
    prev = cur;
  }
  return best;
}
// Edit distance, because longest-common-substring is the wrong tool for a typo. Fixing
// InstantenousMobEffect to InstantaneousMobEffect inserts one character in the middle, which halves
// the longest contiguous match (12 of 21) while changing the name by exactly one edit. Substring
// length measures "shares a chunk"; edit distance measures "is nearly the same word", and a typo fix
// is the second thing.
function editDistance(a, b) {
  a = a.toLowerCase(); b = b.toLowerCase();
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++)
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}
const pkg = (n) => n.slice(0, n.lastIndexOf('/'));
const claimed = new Set(moves.map((m) => m.to.replace(/\./g, '/')));
const renames = [];
for (const g of gone) {
  if (moves.some((m) => m.from.replace(/\./g, '/') === g)) continue;      // already explained as a move
  const am = A.members.get(g) || new Set();
  if (am.size < 2) continue;
  const cands = [];
  for (const a2 of arrived) {
    if (claimed.has(a2) || pkg(a2) !== pkg(g)) continue;                 // a rename stays put; a move is the other case
    const bm = B.members.get(a2) || new Set();
    const shared = [...am].filter((m) => bm.has(m)).length;
    const ratio = shared / am.size;
    const run = commonRun(simple(g), simple(a2));
    const dist = editDistance(simple(g), simple(a2));
    const minLen = Math.min(simple(g).length, simple(a2).length);
    const nameRatio = Math.max(run / minLen, 1 - dist / minLen);
    // Two ways to qualify. A big class can rely on its members: four or more shared and 70% agreement
    // is hard to reach by accident. A small one cannot — InstantenousMobEffect has three members and
    // one of them was renamed too — so it leans on the name instead, where a near-identical spelling
    // in the same package is itself strong evidence. A typo fix is exactly that case.
    const byMembers = ratio >= 0.7 && shared >= 4;
    const byName = nameRatio >= 0.6 && ratio >= 0.6 && shared >= 2;
    if (byMembers || byName) cands.push({ to: a2, shared, ratio, run });
  }
  if (cands.length !== 1) continue;                 // several equally-plausible classes is not evidence
  const c = cands[0];
  // The name must still look related: unrelated classes in one package can share an interface and
  // therefore most of their members.
  const minLen = Math.min(simple(g).length, simple(c.to).length);
  if (c.run < minLen * 0.6 && editDistance(simple(g), simple(c.to)) > minLen * 0.25) continue;
  renames.push({ from: g.replace(/\//g, '.'), to: c.to.replace(/\//g, '.'), shared: c.shared, of: am.size, ratio: +c.ratio.toFixed(2) });
  claimed.add(c.to);
}
for (const r of renames) moves.push(r);
if (renames.length) {
  console.log(`\n  CLASS RENAMES  : ${renames.length}   (same package, members agree, name still related)`);
  for (const r of renames.slice(0, 10)) console.log(`    ✓ ${r.from.split('.').pop()} → ${r.to.split('.').pop()}   (${r.shared}/${r.of} members)`);
}

moves.sort((a, b) => b.shared - a.shared);

console.log(`\n  classes gone   : ${gone.length}`);
console.log(`  classes arrived: ${arrived.length}`);
console.log(`  PACKAGE MOVES  : ${moves.length}   (same simple name, members agree)`);
console.log(`  ambiguous      : ${ambiguous.length}   (several classes share that simple name)`);
console.log(`  members disagree: ${weak.length}   (same name, different class)`);
for (const m of moves.slice(0, 18)) console.log(`    ✓ ${m.from}\n        → ${m.to}   (${m.shared}/${m.of} members shared)`);

const OUT = args.out || path.join(HERE, `classmoves.${args.from || 'old'}-${args.to || 'new'}.json`);
fs.writeFileSync(OUT, JSON.stringify({
  schema: 1, from: args.from || null, to: args.to || null,
  source: 'jar-diff: unique simple name with agreeing members',
  moves, weak: weak.slice(0, 500),
}, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
