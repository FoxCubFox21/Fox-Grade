#!/usr/bin/env node
// Match every registry constant across two versions, by name.
//
// Blocks, Items, BlockTags and friends are enormous lists of static fields, and the overwhelming
// majority are untouched between versions: YELLOW_CONCRETE is YELLOW_CONCRETE. Pairing them by
// descriptor is meaningless — they all share one — which is how a search ended up claiming
// YELLOW_CONCRETE became SULFUR and PINK_WOOL became BONE_MEAL.
//
// The name IS the identity here. These are not implementation details that get renamed for clarity;
// they are the names of things in the game. So the matching rule is simply: same name, same thing.
//
// That turns a hopeless search into three exact answers per constant:
//   unchanged  — same name, same class. Nothing to do, and most of them.
//   moved      — same name, different class or type. Mechanically fixable.
//   removed    — no field of that name anywhere. Genuinely gone; needs a person.
//
//   node registry-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --from 26.1 --to 26.2
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
  console.error('usage: node registry-mine.mjs --old <jar|--old-mappings f.txt> --new <jar|--new-mappings f.txt> --from .. --to ..');
  process.exit(2);
}

// A registry holder is a class whose static fields are overwhelmingly SHOUTING_CASE constants.
// Detecting them by shape rather than by a hard-coded list means this keeps working when Mojang adds
// another one, and it picks up the small ones nobody would think to list.
const CONST = /^[A-Z][A-Z0-9_]{2,}$/;
function registries(jar) {
  const out = new Map();          // class -> Map(CONSTANT -> descriptor)
  for (const e of readZip(fs.readFileSync(jar))) {
    if (!e.name.endsWith('.class')) continue;
    let d; try { d = new ClassFile(inflateEntry(e)).declared(); } catch { continue; }
    if (!d.name) continue;
    const consts = d.members.filter((m) => m.kind === 'field' && CONST.test(m.name));
    if (consts.length < 8) continue;                    // not a registry holder
    out.set(d.name, new Map(consts.map((m) => [m.name, m.desc])));
  }
  return out;
}
// Same shape, built from Mojang's published mappings, so an obfuscated version can be diffed too.
// A registry holder is still recognised by shape rather than by name: the constants are readable in
// the mappings even when the jar calls the class `dqh`.
function registriesFromMappings(file) {
  const out = new Map();
  for (const [cls, ms] of indexFromProguard(file)) {
    const consts = ms.filter((m) => m.kind === 'field' && CONST.test(m.name));
    if (consts.length < 8) continue;
    out.set(cls, new Map(consts.map((m) => [m.name, m.desc])));
  }
  return out;
}
const side = (jar, maps) => (maps ? registriesFromMappings(maps) : registries(jar));
const A = side(args.old, args['old-mappings']);
const B = side(args.new, args['new-mappings']);

// Where does each constant name live in the new version? A name can legitimately appear on several
// classes (BlockTags.SAND and ItemTags.SAND), so the class is kept alongside it.
const newHomes = new Map();       // CONSTANT -> [{cls, desc}]
for (const [cls, consts] of B) for (const [n, desc] of consts) {
  if (!newHomes.has(n)) newHomes.set(n, []);
  newHomes.get(n).push({ cls, desc });
}

const unchanged = [], moved = [], retyped = [], removed = [], ambiguous = [];
for (const [cls, consts] of A) {
  for (const [name, desc] of consts) {
    const here = B.get(cls)?.get(name);
    if (here === desc) { unchanged.push(1); continue; }
    if (here !== undefined) { retyped.push({ owner: cls, name, from: desc, to: here }); continue; }
    const homes = (newHomes.get(name) || []);
    if (!homes.length) { removed.push({ owner: cls, name, desc }); continue; }
    // Prefer a home that kept the type; otherwise a single candidate is still unambiguous.
    const sameType = homes.filter((h) => h.desc === desc);
    const pick = sameType.length === 1 ? sameType[0] : (homes.length === 1 ? homes[0] : null);
    if (!pick) { ambiguous.push({ owner: cls, name, homes: homes.map((h) => h.cls) }); continue; }
    moved.push({ owner: cls, name, desc, toOwner: pick.cls, toDesc: pick.desc });
  }
}

console.log(`  registry classes : ${A.size} → ${B.size}`);
console.log(`  constants        : ${unchanged.length + moved.length + retyped.length + removed.length + ambiguous.length}`);
console.log(`    unchanged      : ${unchanged.length}   — same name, same class. Nothing to do.`);
console.log(`    MOVED          : ${moved.length}   — same name, different class`);
console.log(`    RETYPED        : ${retyped.length}   — same name and class, new type`);
console.log(`    REMOVED        : ${removed.length}   — no constant of that name anywhere`);
console.log(`    ambiguous      : ${ambiguous.length}   — that name lives on several classes now`);
for (const m of moved.slice(0, 10)) console.log(`    → ${m.owner.split('/').pop()}.${m.name}  moved to ${m.toOwner.split('/').pop()}`);
for (const r of retyped.slice(0, 6)) console.log(`    ~ ${r.owner.split('/').pop()}.${r.name}  ${r.from} → ${r.to}`);
for (const r of removed.slice(0, 8)) console.log(`    ✗ ${r.owner.split('/').pop()}.${r.name}  (gone)`);

const OUT = args.out || path.join(HERE, `registry.${args.from || 'old'}-${args.to || 'new'}.json`);
fs.writeFileSync(OUT, JSON.stringify({
  schema: 1, from: args.from || null, to: args.to || null,
  source: 'jar-diff: registry constants matched by name',
  moved, retyped, removed: removed.slice(0, 2000), ambiguous: ambiguous.slice(0, 500),
}, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
