#!/usr/bin/env node
// Mine METHOD AND FIELD renames by diffing two Minecraft jars directly.
//
// Over a wide version gap the breakage is classes: whole packages move, types are dissolved. Over a
// narrow one almost nothing happens to classes and everything happens to members — 26.1 to 26.2
// renamed Minecraft.setScreen to setScreenAndShow and removed Gui.getGuiTicks, while barely touching
// a class name. Class-level remapping is therefore a no-op on exactly the hops most people want.
//
// The signal that makes this safe is the DESCRIPTOR. Inside one class, if a member with descriptor
// (Lnet/minecraft/client/gui/screens/Screen;)V disappears and exactly one member with that same
// descriptor appears, they are the same member under a new name. Requiring a unique descriptor match
// within a single class is what keeps this from degenerating into the guesswork that produced
// World -> DataComponents earlier in this project.
//
// Unlike SRG or intermediary tokens, readable member names are NOT globally unique — "tick" appears
// on hundreds of classes — so every rule here is keyed by (owner, name, descriptor) and can only be
// applied where all three match.
//
//   node member-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --out members.26.1-26.2.json
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
if (!args.old || !args.new) { console.error('usage: node member-mine.mjs --old <jar> --new <jar> [--out file.json]'); process.exit(2); }

// Longest common substring, the cheapest signal that separates a real rename from a coincidence.
// Every correct pair found by descriptor matching keeps a recognisable chunk of its name —
// setScreen/setScreenAndShow, getCurrentMode/currentMode, MEMORY_POOl/MEMORY_POOL — while every
// wrong one shares nothing: closed/canPersistentMap, tick/setClientLevelTeardownInProgress.
function commonRun(a, b) {
  a = a.toLowerCase(); b = b.toLowerCase();
  let best = 0, prev = new Array(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    const cur = new Array(b.length + 1).fill(0);
    for (let j = 1; j <= b.length; j++) {
      if (a[i - 1] === b[j - 1]) { cur[j] = prev[j - 1] + 1; if (cur[j] > best) best = cur[j]; }
    }
    prev = cur;
  }
  return best;
}
// A descriptor made only of primitives carries almost no identifying information: half the boolean
// fields in a class share Z. Those pairings need the name to corroborate them. A descriptor naming
// real types is distinctive enough to stand on its own.
const distinctive = (d) => d.includes('L') || d.includes('[');

function index(jarPath) {
  const out = new Map();   // internal class name -> [{kind,name,desc}]
  for (const e of readZip(fs.readFileSync(jarPath))) {
    if (!e.name.endsWith('.class')) continue;
    let d;
    try { d = new ClassFile(inflateEntry(e)).declared(); } catch { continue; }
    if (d.name) out.set(d.name, d.members);
  }
  return out;
}
const oldIdx = index(args.old), newIdx = index(args.new);
console.log(`  ${path.basename(args.old)}: ${oldIdx.size.toLocaleString()} classes`);
console.log(`  ${path.basename(args.new)}: ${newIdx.size.toLocaleString()} classes`);

const renames = [];       // {owner, kind, desc, from, to}
const removed = [];       // gone with no same-descriptor replacement
let shared = 0, examined = 0;
for (const [cls, oldMembers] of oldIdx) {
  const newMembers = newIdx.get(cls);
  if (!newMembers) continue;                       // class itself moved or went; not our problem here
  examined++;
  const key = (m) => `${m.kind}\t${m.desc}`;
  const oldNames = new Set(oldMembers.map((m) => `${m.kind}\t${m.name}\t${m.desc}`));
  const newNames = new Set(newMembers.map((m) => `${m.kind}\t${m.name}\t${m.desc}`));
  const gone = oldMembers.filter((m) => !newNames.has(`${m.kind}\t${m.name}\t${m.desc}`));
  const arrived = newMembers.filter((m) => !oldNames.has(`${m.kind}\t${m.name}\t${m.desc}`));
  if (!gone.length) { shared++; continue; }
  // The descriptor must be unique across the WHOLE class on both sides, not merely among the members
  // that changed. A first cut required only "one gone, one arrived" and produced closed ->
  // canPersistentMap and isResized -> exclusiveFullscreen: a boolean field's descriptor is just Z,
  // which every boolean in the class shares, so the pairing was arbitrary.
  const countBy = (list) => { const m = new Map(); for (const x of list) { const k = key(x); m.set(k, (m.get(k) || 0) + 1); } return m; };
  const oldCount = countBy(oldMembers), newCount = countBy(newMembers);
  // Compiler-generated members are not renames of anything: <init>/<clinit> are structural, and
  // lambda$foo$3 -> lambda$foo$1 is just renumbering.
  const synthetic = (n) => n.startsWith('<') || n.startsWith('lambda$') || n.startsWith('$') || /^access\$\d+$/.test(n);
  const byDesc = (list) => { const m = new Map(); for (const x of list) { const k = key(x); if (!m.has(k)) m.set(k, []); m.get(k).push(x); } return m; };
  const g = byDesc(gone), a = byDesc(arrived);
  for (const [k, gs] of g) {
    const as = a.get(k);
    const unique = oldCount.get(k) === 1 && newCount.get(k) === 1;
    if (!as) { for (const x of gs) removed.push({ owner: cls, kind: x.kind, name: x.name, desc: x.desc, why: 'nothing arrived with this descriptor' }); continue; }
    // Requiring the descriptor to be unique in the class was too strict on its own: Minecraft has
    // several (Screen;)V methods, so setScreen -> setScreenAndShow was thrown out even though the
    // names share nine characters. Within a descriptor group, pair by name similarity instead —
    // strong enough evidence disambiguates where the descriptor cannot, and a unique descriptor
    // still lets a weaker name match through.
    for (const x of gs) {
      if (synthetic(x.name)) { removed.push({ owner: cls, kind: x.kind, name: x.name, desc: x.desc, why: 'compiler-generated' }); continue; }
      let best = null, bestRun = 0, tie = false;
      for (const y of as) {
        if (synthetic(y.name)) continue;
        const run = commonRun(x.name, y.name);
        if (run > bestRun) { best = y; bestRun = run; tie = false; }
        else if (run === bestRun && bestRun > 0) tie = true;
      }
      const soleMatch = unique && gs.length === 1 && as.length === 1;
      // A bare 6-character overlap is not enough on its own: FOG_SNIPPET and
      // MATRICES_PROJECTION_SNIPPET both "matched" WORLD_TEXT_SNIPPET on the shared word. Require a
      // strong match to cover most of the shorter name, so a common suffix cannot carry it alone.
      // A field typed with a bare primitive has nothing but its name to go on, and a shared word is
      // not identity: elementsMask -> MAX_VERTEX_ELEMENTS passed every length test and is wrong.
      // Those may only come through the strict path, where the descriptor is unique in the class.
      // This does lose correct pairs, which is the right trade for a table applied automatically.
      const strong = !tie && bestRun >= 8 && bestRun >= Math.min(x.name.length, best.name.length) * 0.6
        && (x.kind === 'method' || distinctive(k));
      const ok = strong || (soleMatch && (bestRun >= 5 || (distinctive(k) && bestRun >= 3)));
      if (!best || !ok) {
        removed.push({ owner: cls, kind: x.kind, name: x.name, desc: x.desc,
          why: tie ? 'two candidates match the name equally well' : bestRun ? `best name overlap only ${bestRun} chars` : 'names share nothing; descriptor alone is not evidence' });
        continue;
      }
      renames.push({ owner: cls, kind: x.kind, desc: x.desc, from: x.name, to: best.name, shared: bestRun });
    }
  }
}

// Two members cannot both have been renamed to the same thing. When it happens the pairing was
// driven by a shared word rather than identity, so neither claim survives.
const claimed = new Map();
for (const r of renames) { const k = `${r.owner}\t${r.kind}\t${r.to}`; claimed.set(k, (claimed.get(k) || 0) + 1); }
const contested = renames.filter((r) => claimed.get(`${r.owner}\t${r.kind}\t${r.to}`) > 1);
if (contested.length) {
  for (const r of contested) removed.push({ owner: r.owner, kind: r.kind, name: r.from, desc: r.desc, why: `several members claim ${r.to}` });
  for (let i = renames.length - 1; i >= 0; i--) if (claimed.get(`${renames[i].owner}\t${renames[i].kind}\t${renames[i].to}`) > 1) renames.splice(i, 1);
  console.log(`  dropped, contested dest: ${contested.length}`);
}

console.log(`  classes in both        : ${examined.toLocaleString()}`);
console.log(`  MEMBER RENAMES         : ${renames.length}`);
console.log(`  removed, no replacement: ${removed.length}`);
const methods = renames.filter((r) => r.kind === 'method').length;
console.log(`                           ${methods} methods, ${renames.length - methods} fields`);
for (const r of renames.slice(0, 15)) console.log(`    ${r.owner.replace(/\//g, '.')}.${r.from} → ${r.to}   ${r.desc}`);
if (renames.length > 15) console.log(`    … ${renames.length - 15} more`);

const OUT = args.out || path.join(HERE, `members.${args.from || 'old'}-${args.to || 'new'}.json`);
fs.writeFileSync(OUT, JSON.stringify({
  schema: 1, from: args.from || null, to: args.to || null,
  source: 'jar-diff: unique descriptor match within one class',
  renames, removed: removed.slice(0, 4000),
}, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
