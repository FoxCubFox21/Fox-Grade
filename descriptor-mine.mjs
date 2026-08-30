#!/usr/bin/env node
// Find members that kept their name and changed their TYPE.
//
// The JVM resolves a field or method by name AND descriptor, so a member whose declared type moved
// or widened breaks every reference to it while sitting in plain sight. 26.2 declares
// Potions.WATER as Holder where 26.1 had Holder$Reference; the field is right there and the old
// reference still fails. Roughly a tenth of the broken references measured across ported mods are
// this shape, and none of the other miners can express it.
//
// Only WIDENING is proposed: the new type must be a supertype of the old, so a value that used to be
// a Holder$Reference is still a valid Holder. That is the case where rewriting the descriptor cannot
// change what the code receives. Narrowing is the opposite — it would hand the caller something
// weaker than it expects — and is reported rather than applied.
//
//   node descriptor-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --from 26.1 --to 26.2
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
if (!args.old || !args.new) { console.error('usage: node descriptor-mine.mjs --old <jar> --new <jar> --from .. --to ..'); process.exit(2); }

function index(jar) {
  const members = new Map(), supers = new Map();
  for (const e of readZip(fs.readFileSync(jar))) {
    if (!e.name.endsWith('.class')) continue;
    try {
      const d = new ClassFile(inflateEntry(e)).declared();
      if (!d.name) continue;
      members.set(d.name, d.members);
      supers.set(d.name, [d.super, ...(d.interfaces || [])].filter(Boolean));
    } catch { /* skip */ }
  }
  return { members, supers };
}
const A = index(args.old), B = index(args.new);

// Is `sub` assignable to `sup` in the NEW version? Walking the target's own hierarchy is what makes
// "widening" a fact rather than a guess about names.
function isSubtype(sub, sup, seen = new Set()) {
  if (sub === sup) return true;
  if (seen.has(sub)) return false;
  seen.add(sub);
  return (B.supers.get(sub) || []).some((s) => isSubtype(s, sup, seen));
}
const typeOf = (desc) => { const m = desc.match(/^L([\w/$]+);$/); return m ? m[1] : null; };

const widened = [], narrowed = [], changed = [];
for (const [cls, oldMembers] of A.members) {
  const newMembers = B.members.get(cls);
  if (!newMembers) continue;
  for (const om of oldMembers) {
    if (newMembers.some((m) => m.name === om.name && m.desc === om.desc)) continue;   // unchanged
    const sameName = newMembers.filter((m) => m.name === om.name && m.kind === om.kind);
    if (sameName.length !== 1) continue;             // overloads: cannot tell which is meant
    const nm = sameName[0];
    const oldT = typeOf(om.desc), newT = typeOf(nm.desc);
    const rec = { owner: cls, kind: om.kind, name: om.name, from: om.desc, to: nm.desc };
    if (oldT && newT && isSubtype(oldT, newT)) widened.push(rec);
    else if (oldT && newT && isSubtype(newT, oldT)) narrowed.push(rec);
    else changed.push(rec);
  }
}

console.log(`  widened   : ${widened.length}   (new type is a supertype — safe to rewrite)`);
console.log(`  narrowed  : ${narrowed.length}   (new type is more specific — reported, not applied)`);
console.log(`  otherwise : ${changed.length}   (unrelated types or a changed signature — needs code)`);
for (const w of widened.slice(0, 10)) console.log(`    ✓ ${w.owner.split('/').pop()}.${w.name}  ${w.from} → ${w.to}`);
for (const c of changed.slice(0, 4)) console.log(`    · ${c.owner.split('/').pop()}.${c.name}  ${c.from} → ${c.to}`);

const OUT = args.out || path.join(HERE, `descriptors.${args.from || 'old'}-${args.to || 'new'}.json`);
fs.writeFileSync(OUT, JSON.stringify({ schema: 1, from: args.from || null, to: args.to || null, source: 'jar-diff: supertype widening', widened, narrowed: narrowed.slice(0, 300), changed: changed.slice(0, 500) }, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
