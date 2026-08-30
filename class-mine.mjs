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

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
if (!args.old || !args.new) { console.error('usage: node class-mine.mjs --old <jar> --new <jar> --from 26.1 --to 26.2'); process.exit(2); }

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
const A = classesOf(args.old), B = classesOf(args.new);
console.log(`  ${path.basename(args.old)}: ${A.names.size.toLocaleString()} classes`);
console.log(`  ${path.basename(args.new)}: ${B.names.size.toLocaleString()} classes`);

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
