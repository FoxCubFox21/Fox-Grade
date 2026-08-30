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
import { ClassFile, referencedTypes, makeReplacer, applyMemberRenames } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['quiet', 'no-nested', 'best-guess']);
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
  // comp_NNNN is the third token kind: Java record component accessors. Missing it left every
  // record getter unmapped — FoodProperties.comp_2491 instead of .nutrition — which then looked
  // like a redesign needing AI, when the mapping was sitting in the table all along.
  for (const { value } of cf.utf8()) if (/^(method_\d+|field_\d+|comp_\d+|func_\d+_[a-z]+_?|field_\d+_[a-z]+_?)$/.test(value)) memberTokens.add(value);
}

// ── rules ────────────────────────────────────────────────────────────────────────────────────
const data = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
// Intermediary tables are generated per install rather than shipped (they are a join over Mojang's
// own mappings). Fold in any that have been built — released Fabric mods are unportable without one.
for (const f of fs.readdirSync(HERE).filter((x) => /^intermediary\.[\d.]+\.json$/.test(x))) {
  try { for (const [k, v] of Object.entries(JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')))) if (!data[k]) data[k] = v; }
  catch { console.error(`  ! ignoring unreadable ${f}`); }
}
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

// ── member renames, keyed by (owner, name, descriptor) ───────────────────────────────────────
// Over a narrow version hop this is where all the work is: Mojang barely moves classes between point
// releases, so the class table above matches nothing and every broken link is a member instead.
const memberRenames = new Map();   // owner \t kind \t name \t desc -> newName
const guessRules = new Map();
let memberRuleCount = 0, guessRuleCount = 0;
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  const m = f.match(/^members\.([\d.]+)-([\d.]+)\.json$/);
  const a2 = verOf(m[1]), b2 = verOf(m[2]);
  if ((fv && cmp(a2, fv) < 0) || (tv && cmp(b2, tv) > 0)) continue;   // outside the span being crossed
  try {
    const tbl = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'));
    for (const r of tbl.renames || []) {
      memberRenames.set(`${r.owner}\t${r.kind}\t${r.from}\t${r.desc}`, r.to);
      memberRuleCount++;
    }
    // Guesses are plausible but not certain — several members could fit, or the names only partly
    // agree. Off by default, because a wrong one links cleanly and behaves differently; on request,
    // because refusing outright leaves a jar that simply crashes instead. Every one is written to a
    // report next to the jar, with the rivals it beat, so nothing is applied silently.
    if (args['best-guess']) for (const r of tbl.guesses || []) {
      const k = `${r.owner}\t${r.kind}\t${r.from}\t${r.desc}`;
      if (memberRenames.has(k)) continue;                 // a confident rule always wins
      memberRenames.set(k, r.to); guessRules.set(k, r); guessRuleCount++;
    }
  } catch { console.error(`  ! ignoring unreadable ${f}`); }
}
// A rule names its owner at the SOURCE version, but by the time member renaming runs the class table
// has already respelled that owner. Index both forms so a rule still fires after a package move.
for (const [k, v] of [...memberRenames]) {
  const [owner, kind, name, desc] = k.split('\t');
  const moved = types.get(owner);
  if (moved) memberRenames.set(`${moved}\t${kind}\t${name}\t${desc}`, v);
}
// Hand-verified substitutions, loaded last so they beat anything mined. These are the cases the
// miner is structurally blind to — chiefly a member removed in favour of one that already existed,
// which an arrived-only diff can never propose. They carry their own provenance and are counted
// separately, so a table of derived facts is never quietly padded with human judgement.
let overrideCount = 0;
try {
  const ov = JSON.parse(fs.readFileSync(path.join(HERE, 'members.overrides.json'), 'utf8'));
  for (const r of ov.renames || []) {
    const a2 = verOf(r.from), b2 = verOf(r.to);
    if ((fv && cmp(a2, fv) < 0) || (tv && cmp(b2, tv) > 0)) continue;
    memberRenames.set(`${r.owner}\t${r.kind}\t${r.name}\t${r.desc}`, r.becomes);
    guessRules.delete(`${r.owner}\t${r.kind}\t${r.name}\t${r.desc}`);
    overrideCount++;
  }
} catch { /* no overrides file is fine */ }

// Descriptor widenings: same member, new type that is a supertype of the old. Only widenings are
// loaded — a narrowing would hand the caller something weaker than it expects.
const widened = new Map();   // owner \t kind \t name \t oldDesc -> newDesc
let widenCount = 0;
for (const f of fs.readdirSync(HERE).filter((x) => /^descriptors\.[\d.]+-[\d.]+\.json$/.test(x))) {
  const m = f.match(/^descriptors\.([\d.]+)-([\d.]+)\.json$/);
  const a2 = verOf(m[1]), b2 = verOf(m[2]);
  if ((fv && cmp(a2, fv) < 0) || (tv && cmp(b2, tv) > 0)) continue;
  try {
    for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).widened || []) {
      widened.set(`${r.owner}\t${r.kind}\t${r.name}\t${r.from}`, r.to);
      widenCount++;
    }
  } catch { /* ignore */ }
}

// Registry constants that moved class. The name is the identity for these — YELLOW_CONCRETE is
// YELLOW_CONCRETE — so a same-name match in a different class is the answer, not a guess.
const movedConstants = new Map();   // owner \t name -> {owner, desc}
let movedCount = 0;
for (const f of fs.readdirSync(HERE).filter((x) => /^registry\.[\d.]+-[\d.]+\.json$/.test(x))) {
  const m = f.match(/^registry\.([\d.]+)-([\d.]+)\.json$/);
  const a2 = verOf(m[1]), b2 = verOf(m[2]);
  if ((fv && cmp(a2, fv) < 0) || (tv && cmp(b2, tv) > 0)) continue;
  try {
    for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).moved || []) {
      movedConstants.set(`${r.owner}\t${r.name}`, { owner: r.toOwner, desc: r.toDesc });
      movedCount++;
    }
  } catch { /* ignore */ }
}

const memberLookup = (owner, kind, name, desc) => {
  const k = `${owner}\t${kind}\t${name}\t${desc}`;
  const movedTo = movedConstants.get(`${owner}\t${name}`);
  if (movedTo) return { to: name, owner: movedTo.owner, desc: movedTo.desc, guess: null };
  const to = memberRenames.get(k);
  const newDesc = widened.get(`${owner}\t${kind}\t${name}\t${desc}`);
  if (!to && !newDesc) return null;
  return { to: to || name, desc: newDesc || desc, guess: guessRules.has(k) ? k : null };
};

// ── report ───────────────────────────────────────────────────────────────────────────────────
const pct = (n, d) => d ? `${(100 * n / d).toFixed(1)}%` : '—';
console.log(`  ${path.basename(JAR)}${units.length > 1 ? `  (+${units.length - 1} bundled jar${units.length > 2 ? 's' : ''})` : ''}`);
console.log(`    classes            : ${classCount.toLocaleString()}`);
console.log(`    minecraft types    : ${referenced.size}`);
console.log(`    naming scheme      : ${scheme ? `${scheme.match(/^scheme:(\w+)@([\d.]+)/).slice(1).join(' @ ')}  (${schemeHits} hits)` : 'mojmap / already readable'}`);
if (!TARGET) console.log(`    ⚠ no --classpath, so no rename could be confirmed against the real ${TO} jars`);
console.log(`\n    remappable         : ${types.size}  ${pct(types.size, referenced.size)}`);
if (memberRuleCount) console.log(`    member rules       : ${memberRuleCount} for this span`);
if (movedCount) console.log(`    moved constants    : ${movedCount} registry constant(s) that changed class`);
if (widenCount) console.log(`    type widenings     : ${widenCount} member(s) whose declared type became a supertype`);
if (overrideCount) console.log(`    hand-verified      : ${overrideCount} substitution(s) the miner cannot derive`);
if (guessRuleCount) console.log(`    best-guess rules   : ${guessRuleCount}  ⚠ plausible, not certain — see the report`);
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
let memberHits = 0, metaFixed = 0;
const guessesUsed = new Set();
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
    // Porting the bytecode is not enough: fabric.mod.json still declares the version range the mod
    // was built for, and Fabric refuses to load outside it before any of our work is even reached.
    //   Freecam requires minecraft >=26.1.0 <26.2.0-0, but 26.2 is present
    // The dependency is narrowed to exactly the target, not widened to "*": this jar has been ported
    // to one specific version and has no business claiming to run on others.
    if (/(^|\/)fabric\.mod\.json$/.test(e.name)) {
      try {
        const meta = JSON.parse(inflateEntry(e).toString('utf8'));
        let touched = false;
        for (const field of ['depends', 'recommends', 'suggests', 'breaks', 'conflicts']) {
          if (meta[field] && meta[field].minecraft !== undefined) {
            if (field === 'depends') { meta[field].minecraft = TO; touched = true; }
            else { delete meta[field].minecraft; touched = true; }
          }
        }
        if (touched) { repl.set(e.name, Buffer.from(JSON.stringify(meta, null, 2) + '\n', 'utf8')); metaFixed++; }
      } catch { console.error(`  ! could not parse ${e.name}; leaving its version range alone`); }
      continue;
    }
    if (!e.name.endsWith('.class')) continue;
    // Types first, then members: a member rule names its owner, so that owner has to already be
    // spelled the way this class file now spells it.
    const original = inflateEntry(e);
    let bytes = new ClassFile(original).rewrite(replacer);
    if (memberRenames.size) {
      const mem = applyMemberRenames(new ClassFile(bytes || original), memberLookup);
      if (mem) { bytes = mem.buf; memberHits += mem.applied.length; for (const a of mem.guessed) guessesUsed.add(a); }
    }
    if (bytes) repl.set(e.name, bytes);
  }
  if (!repl.size) return null;
  return writeZip(entries.filter((e) => !/^META-INF\/.*\.(SF|RSA|DSA|EC)$/i.test(e.name)), repl);
}
const out = remapUnit(rootBuf) || rootBuf;
fs.writeFileSync(args.out, out);
console.log(`\n  wrote ${args.out}  (${stats.types || 0} type refs, ${stats.members || 0} token refs, ${memberHits} member call sites rewritten)`);
if (guessesUsed.size) {
  const lines = ['# Best-guess member renames applied', '',
    `Jar: ${path.basename(args.out)}   ${guessesUsed.size} guess(es) applied.`, '',
    'Each of these was NOT certain: either several members in the target class share the descriptor,',
    'or the names only partly agree. The chosen one is listed first with the rivals it beat. If the',
    'mod misbehaves in a way that matches one of these, this is the first place to look.', ''];
  for (const k of guessesUsed) {
    const g = guessRules.get(k); if (!g) continue;
    lines.push(`## ${g.owner.replace(/\//g, '.')}.${g.from}`, `- descriptor: \`${g.desc}\``,
      `- **chosen: ${g.to}** (${g.shared} characters shared)`, `- why not certain: ${g.why}`);
    const rivals = (g.alternatives || []).filter((a) => a.name !== g.to);
    if (rivals.length) lines.push(`- rejected: ${rivals.map((a) => `${a.name} (${a.run})`).join(', ')}`);
    lines.push('');
  }
  const rp = args.out.replace(/\.jar$/, '') + '.guesses.md';
  fs.writeFileSync(rp, lines.join('\n'));
  console.log(`  ⚠ ${guessesUsed.size} best-guess rename(s) applied — every one listed in ${path.basename(rp)}`);
}
if (metaFixed) console.log(`  ${metaFixed} fabric.mod.json version range(s) retargeted at ${TO}`);
console.log(`  the original is untouched. Verify by launching the game — a jar that loads is not a jar that works.`);
