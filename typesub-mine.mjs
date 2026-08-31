#!/usr/bin/env node
// Find class renames from the TYPE SUBSTITUTIONS they force in other classes' signatures.
//
// class-mine pairs classes by their members, and that has a blind spot it cannot see past: when a
// class is renamed, its own methods change too, because they mention it. Identifier.withSuffix
// returns an Identifier where ResourceLocation.withSuffix returned a ResourceLocation, so the
// descriptors differ and the members do not match. The names share nothing either. The most
// consequential rename in 1.21.1 -> 26.2 — ResourceLocation to Identifier, which touches nearly
// every mod ever written — is invisible to member matching by construction.
//
// The evidence is in the OTHER classes. Hundreds of unrelated methods kept their owner, their name
// and their shape, and changed in exactly one respect: where they used to say ResourceLocation they
// now say Identifier. One such method is a coincidence. Six hundred of them, with no competing
// substitution for the same type, is a rename.
//
// This deliberately says nothing about classes that were merely deleted: a type that vanishes and
// takes its methods with it produces no surviving signature to compare, and inventing a destination
// for it is the guesswork this project keeps deleting.
//
//   node typesub-mine.mjs --old-mappings 1.21.1.txt --new mc-26.2.jar --from 1.21.1 --to 26.2
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
  console.error('usage: node typesub-mine.mjs --old <jar|--old-mappings f> --new <jar|--new-mappings f> --from .. --to ..');
  process.exit(2);
}
const MIN = +(args.min || 8);

function indexJar(jarPath) {
  const out = new Map();
  for (const e of readZip(fs.readFileSync(jarPath))) {
    if (!e.name.endsWith('.class')) continue;
    let d; try { d = new ClassFile(inflateEntry(e)).declared(); } catch { continue; }
    if (d.name) out.set(d.name, d.members);
  }
  return out;
}
const load = (jar, maps) => (maps ? indexFromProguard(maps) : indexJar(jar));
const A = load(args.old, args['old-mappings']);
const B = load(args.new, args['new-mappings']);
console.log(`  old: ${A.size.toLocaleString()} classes    new: ${B.size.toLocaleString()} classes`);

// Split a descriptor into its ordered type tokens, keeping array depth attached so that
// [Lfoo; and Lfoo; are never treated as the same position.
function types(desc) {
  const out = [];
  for (let i = 0; i < desc.length; i++) {
    const c = desc[i];
    if (c === '(' || c === ')') continue;
    let dims = '';
    while (desc[i] === '[') { dims += '['; i++; }
    if (desc[i] === 'L') { const j = desc.indexOf(';', i); out.push(dims + desc.slice(i, j + 1)); i = j; }
    else out.push(dims + desc[i]);
  }
  return out;
}

// Every substitution seen, and how often. Counting the pairs rather than deciding on the first one
// is the whole point: a single differing signature proves nothing, agreement across hundreds does.
const votes = new Map();      // "old\tnew" -> count
const owners = new Map();     // "old\tnew" -> Set(classes that showed it)
let compared = 0;
for (const [cls, oldMembers] of A) {
  const newMembers = B.get(cls);
  if (!newMembers) continue;
  // Index the new side by name+kind. A name with several overloads is skipped rather than guessed
  // at: pairing the wrong overload would manufacture a substitution that never happened.
  const byName = new Map();
  for (const m of newMembers) {
    const k = `${m.kind}\t${m.name}`;
    if (byName.has(k)) byName.set(k, null); else byName.set(k, m);
  }
  const seen = new Map();
  for (const m of oldMembers) {
    const k = `${m.kind}\t${m.name}`;
    if (seen.has(k)) seen.set(k, null); else seen.set(k, m);
  }
  for (const [k, m] of seen) {
    if (!m) continue;
    const n = byName.get(k);
    if (!n || m.desc === n.desc) continue;
    const a = types(m.desc), b = types(n.desc);
    if (a.length !== b.length) continue;                 // arity changed: a different edit entirely
    const diffs = [];
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) diffs.push([a[i], b[i]]);
    // Require the signature to differ in ONE type only. Two simultaneous changes cannot tell you
    // which of them explains which, and counting both would let unrelated edits vote for each other.
    if (diffs.length !== 1) continue;
    let [o, t] = diffs[0];
    const dims = (o.match(/^\[+/) || [''])[0];
    if (!o.startsWith(`${dims}L`) || !t.startsWith(`${dims}L`)) continue;   // primitive swap, not a rename
    o = o.slice(dims.length + 1, -1); t = t.slice(dims.length + 1, -1);
    if (o === t) continue;
    compared++;
    const key = `${o}\t${t}`;
    votes.set(key, (votes.get(key) || 0) + 1);
    if (!owners.has(key)) owners.set(key, new Set());
    owners.get(key).add(cls);
  }
}

// A type that "became" several different things did not get renamed — the signatures changed for
// some other reason. Only an unopposed substitution is evidence, and it has to clear MIN sightings
// across more than one class so a single refactored file cannot carry a rule on its own.
const from = new Map();
for (const [k, n] of votes) { const [o] = k.split('\t'); from.set(o, (from.get(o) || 0) + n); }
const renames = [], contested = [];
for (const [k, n] of votes) {
  const [o, t] = k.split('\t');
  const share = n / from.get(o);
  const rec = { from: o.replace(/\//g, '.'), to: t.replace(/\//g, '.'), sightings: n, classes: owners.get(k).size, share: +share.toFixed(2) };
  if (n < MIN || owners.get(k).size < 2) continue;
  // The old name must actually be gone and the new one must actually exist, or this is a widening
  // (Screen -> Object) rather than a rename.
  if (B.has(o) || !B.has(t)) { contested.push({ ...rec, why: B.has(o) ? 'old type still exists' : 'new type not found' }); continue; }
  (share >= 0.9 ? renames : contested).push(share >= 0.9 ? rec : { ...rec, why: `only ${Math.round(share * 100)}% of this type's changes` });
}
renames.sort((a, b) => b.sightings - a.sightings);
contested.sort((a, b) => b.sightings - a.sightings);

console.log(`  single-type signature changes examined: ${compared.toLocaleString()}`);
console.log(`  CLASS RENAMES FROM TYPE EVIDENCE       : ${renames.length}   (>= ${MIN} sightings, >= 2 classes, unopposed)`);
for (const r of renames.slice(0, 20)) console.log(`    ✓ ${r.from}\n        → ${r.to}   (${r.sightings} signatures across ${r.classes} classes)`);
console.log(`  rejected                               : ${contested.length}`);
for (const c of contested.slice(0, 8)) console.log(`    · ${c.from.split('.').pop()} → ${c.to.split('.').pop()}   ${c.why}`);

const OUT = args.out || path.join(HERE, `typesubs.${args.from || 'old'}-${args.to || 'new'}.json`);
fs.writeFileSync(OUT, JSON.stringify({
  schema: 1, from: args.from || null, to: args.to || null,
  source: 'jar-diff: class renames inferred from consistent single-type substitutions in surviving signatures',
  renames, rejected: contested.slice(0, 300),
}, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
