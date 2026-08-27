#!/usr/bin/env node
// Tier 1: remap a compiled mod jar between Minecraft versions, without source.
//
// Renames and package moves are a CONSTANT POOL edit, not a bytecode edit — see classfile.mjs. So
// this needs no decompiler, no javac, no ASM, and no access to the mod's build environment, and it
// works just as well on a jar whose own classes were obfuscated by their author, because it only
// ever touches names that appear in our tables.
//
// It does NOT handle genuine API redesigns. Where an old class has no counterpart, it says so and
// leaves the reference alone rather than inventing one that loads and then behaves wrongly.
//
//   node jar-remap.mjs mod.jar --from 1.16.5 --to 26.2 --classpath "<target jars>"   # report only
//   node jar-remap.mjs mod.jar --from 1.16.5 --to 26.2 --classpath ... --out new.jar
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile, referencedTypes, makeReplacer } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['quiet', 'no-nested']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const JAR = pos[0];
if (!JAR) { console.error('usage: node jar-remap.mjs <mod.jar> --from <ver> --to <ver> [--classpath ...] [--out new.jar]'); process.exit(2); }
if (!fs.existsSync(JAR)) { console.error(`no such jar: ${JAR}`); process.exit(2); }
const TO = args.to || '26.2', FROM = args.from || null;
// Never write over the input. A mod jar is often the only copy someone has.
if (args.out && path.resolve(args.out) === path.resolve(JAR)) { console.error('--out must differ from the input jar'); process.exit(2); }

const dot = (s) => s.replace(/\//g, '.');
const slash = (s) => s.replace(/\./g, '/');

// ── the target's real class list, used to stop a rename chain at the right hop ────────────────
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
let TARGET = null;
if (cp) {
  TARGET = new Set();
  for (const j of cp.split(':')) {
    if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
    const r = spawnSync('unzip', ['-Z1', j, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
    if (r.status !== 0 || !r.stdout) continue;
    for (const l of r.stdout.split('\n')) { const p = l.trim(); if (p.endsWith('.class')) TARGET.add(p.slice(0, -6)); }
  }
  if (!TARGET.size) TARGET = null;
}

// ── read every class in the jar, including bundled ones ──────────────────────────────────────
// Fabric mods ship their dependencies as nested jars under META-INF/jars/. Skipping those leaves
// half a mod unported, so they are opened too unless asked not to.
const rootBuf = fs.readFileSync(JAR);
const units = [];   // {label, entries, nested?: {parentName}}
function load(buf, label, depth = 0) {
  let entries;
  try { entries = readZip(buf); } catch (e) { console.error(`  ! ${label}: ${e.message}`); return; }
  units.push({ label, entries });
  if (args['no-nested'] || depth > 1) return;
  for (const e of entries) {
    if (!/^META-INF\/jars\/.+\.jar$/.test(e.name)) continue;
    try { load(inflateEntry(e), `${label} › ${path.basename(e.name)}`, depth + 1); } catch { /* leave it be */ }
  }
}
load(rootBuf, path.basename(JAR));

const classesOf = (u) => u.entries.filter((e) => e.name.endsWith('.class'));
const referenced = new Set();
const memberTokens = new Set();
let classCount = 0;
for (const u of units) for (const e of classesOf(u)) {
  classCount++;
  const cf = new ClassFile(inflateEntry(e));
  for (const t of referencedTypes(cf)) if (t.startsWith('net/minecraft/')) referenced.add(t);
  for (const { value } of cf.utf8()) if (/^(method_\d+|field_\d+|func_\d+_[a-z]+_?|field_\d+_[a-z]+_?)$/.test(value)) memberTokens.add(value);
}

// ── rules ────────────────────────────────────────────────────────────────────────────────────
const data = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
const VER = /(\d+)\.(\d+)(?:\.(\d+))?/;
const verOf = (s) => { const m = String(s).match(VER); return m ? [+m[1], +m[2], +(m[3] || 0)] : null; };
const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

// Which naming scheme is this jar written in? Same question the source porter asks of imports,
// asked of the constant pool instead. Modern Fabric jars ship mojmap and hit nothing here; older
// ones are intermediary or SRG and need the translation tables first.
let scheme = null, schemeHits = 0;
for (const [k, v] of Object.entries(data)) {
  if (!/^scheme:\w+@[\d.]+->mojmap$/.test(k)) continue;
  let hits = 0;
  for (const r of v.renames || []) if (referenced.has(slash(r.fromFqcn))) hits++;
  if (hits > schemeHits) { schemeHits = hits; scheme = k; }
}
// A jar really written in a scheme hits a large share of its own type references, not a handful.
// Three coincidental hits out of a thousand is two 26.2 classes sharing a name with a yarn entry,
// and acting on it silently renames correct code.
if (schemeHits < 10 || schemeHits < referenced.size * 0.25) { scheme = null; schemeHits = 0; }

const fv = FROM ? verOf(FROM) : null, tv = verOf(TO);
const ladder = Object.entries(data)
  .map(([k, v]) => { const m = k.match(/^([\d.]+)->([\d.]+)$/); return m ? { v, a: verOf(m[1]), b: verOf(m[2]) } : null; })
  .filter(Boolean)
  .filter((x) => (!fv || cmp(x.a, fv) >= 0) && (!tv || cmp(x.b, tv) <= 0))
  .sort((x, y) => cmp(x.a, y.a));
const steps = ladder.map((x) => new Map((x.v.renames || []).filter((r) => r.verified !== false).map((r) => [r.fromFqcn, r.toFqcn])));
const targetBlock = new Map(((data[TO] || {}).renames || []).filter((r) => r.verified !== false).map((r) => [r.fromFqcn, r.toFqcn]));
const deleted = new Map(((data[TO] || {}).deleted || []).map((d) => [d.fqcn, d.replacement]));

// Stop as soon as the name exists in the target: walking on can overshoot a correct answer into a
// later redesign (the MatrixStack -> PoseStack -> GuiGraphics trap).
const valid = (x) => TARGET && TARGET.has(slash(x));
function walk(start) {
  let c = start;
  for (const m of steps) { if (valid(c)) return c; if (m.has(c)) c = m.get(c); }
  if (valid(c)) return c;
  if (targetBlock.has(c)) c = targetBlock.get(c);
  return c;
}

const types = new Map();      // internal form -> internal form
const unresolved = [], removed = [], alreadyOk = [], phantom = [];
const schemeMap = scheme ? new Map((data[scheme].renames || []).map((r) => [r.fromFqcn, r.toFqcn])) : null;
for (const t of referenced) {
  const from = dot(t);
  if (deleted.has(from)) { removed.push([t, deleted.get(from)]); continue; }
  const start = schemeMap?.get(from) ?? from;
  const end = walk(start);
  if (end !== from) {
    // A chain can end somewhere that simply does not exist — a wild-mined rule, or a scheme table
    // applied to a jar that was never in that scheme. Writing that name produces a jar that loads
    // and then dies with NoClassDefFoundError. If we cannot see the destination, we do not jump.
    if (TARGET && !valid(end)) { phantom.push([t, end]); continue; }
    types.set(t, slash(end)); continue;
  }
  if (valid(from)) { alreadyOk.push(t); continue; }   // already correct for the target
  unresolved.push(t);
}

// Member tokens (SRG / intermediary) are globally unique, so a plain token rewrite is sound.
const members = new Map();
if (memberTokens.size) {
  const want = scheme?.match(/@([\d.]+)/)?.[1];
  for (const [k, v] of Object.entries(data)) {
    if (!k.startsWith('members:')) continue;
    if (want && !k.includes('@' + want + '-')) continue;
    for (const [a, b] of Object.entries(v.members || {})) if (memberTokens.has(a) && !members.has(a)) members.set(a, b);
  }
}

// ── report ───────────────────────────────────────────────────────────────────────────────────
const pct = (n, d) => d ? `${(100 * n / d).toFixed(1)}%` : '—';
console.log(`  ${path.basename(JAR)}${units.length > 1 ? `  (+${units.length - 1} bundled jar${units.length > 2 ? 's' : ''})` : ''}`);
console.log(`    classes            : ${classCount.toLocaleString()}`);
console.log(`    minecraft types    : ${referenced.size}`);
console.log(`    naming scheme      : ${scheme ? `${scheme.match(/^scheme:(\w+)@([\d.]+)/).slice(1).join(' @ ')}  (${schemeHits} hits)` : 'mojmap / already readable'}`);
if (!TARGET) console.log(`    ⚠ no --classpath, so no rename could be confirmed against the real ${TO} jars`);
console.log(`\n    remappable         : ${types.size}  ${pct(types.size, referenced.size)}`);
console.log(`    already correct    : ${alreadyOk.length}`);
console.log(`    NO EQUIVALENT      : ${removed.length}   ← these cannot be fixed by renaming`);
console.log(`    unresolved         : ${unresolved.length}   ← Tier 2: needs a real code change`);
if (phantom.length) console.log(`    declined           : ${phantom.length}   ← a rule pointed at a class that is not in ${TO}`);
if (members.size) console.log(`    member tokens      : ${members.size} of ${memberTokens.size}`);
for (const [t, why] of removed.slice(0, 6)) console.log(`      ✗ ${dot(t)}\n          ${why}`);
for (const [t, end] of phantom.slice(0, 4)) console.log(`      ~ ${dot(t)}  →  ${end}  (no such class in ${TO}; left alone)`);
for (const t of unresolved.slice(0, 8)) console.log(`      ? ${dot(t)}`);
if (unresolved.length > 8) console.log(`      … ${unresolved.length - 8} more`);

if (!args.out) {
  console.log(`\n  report only — pass --out <file> to write a remapped jar`);
  process.exit(0);
}
if (removed.length || unresolved.length || phantom.length) {
  console.log(`\n  ⚠ ${removed.length + unresolved.length + phantom.length} reference(s) will be left UNCHANGED. The jar will load, but any`);
  console.log(`    code path reaching them will fail at runtime. This is not a finished port.`);
}

// ── write ────────────────────────────────────────────────────────────────────────────────────
const stats = {};
const replacer = makeReplacer(types, members, stats);
function remapUnit(buf) {
  const entries = readZip(buf);
  const repl = new Map();
  for (const e of entries) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name) && !args['no-nested']) {
      const inner = remapUnit(inflateEntry(e));
      if (inner) repl.set(e.name, inner);
      continue;
    }
    // A modified jar can never satisfy the original signature, so the stale signature files go.
    if (/^META-INF\/.*\.(SF|RSA|DSA|EC)$/i.test(e.name)) continue;
    if (!e.name.endsWith('.class')) continue;
    const out = new ClassFile(inflateEntry(e)).rewrite(replacer);
    if (out) repl.set(e.name, out);
  }
  if (!repl.size) return null;
  return writeZip(entries.filter((e) => !/^META-INF\/.*\.(SF|RSA|DSA|EC)$/i.test(e.name)), repl);
}
const out = remapUnit(rootBuf) || rootBuf;
fs.writeFileSync(args.out, out);
console.log(`\n  wrote ${args.out}  (${stats.types || 0} type refs, ${stats.members || 0} member refs rewritten)`);
console.log(`  the original is untouched. Verify by launching the game — a jar that loads is not a jar that works.`);
