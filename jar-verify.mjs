#!/usr/bin/env node
// Does this jar actually link against the target version?
//
// A remapped jar being well-formed says nothing about whether it works — javap will happily accept a
// class that calls a method deleted three versions ago. The JVM only finds out at the moment it
// resolves the call, which is somewhere deep in a play session.
//
// So resolve those links here instead: every method and field the jar reaches for on a Minecraft
// class, checked against the real jars for its exact descriptor, walking superclasses and interfaces
// the way the JVM does. Anything missing is a crash that WILL happen on that code path.
//
//   node jar-verify.mjs ported.jar --classpath "<target jars>"
import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';
import { liveRefs } from './bytecode.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
if (!JAR) { console.error('usage: node jar-verify.mjs <jar> --classpath "<target jars>"'); process.exit(2); }
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath to check against'); process.exit(2); }
const PREFIX = args.prefix || 'net/minecraft/';

// Members that OTHER MODS still call in their builds FOR THIS VERSION. Fabric API adds methods to
// vanilla classes by mixin, so they exist in no jar on the classpath and yet resolve at runtime:
// EntitySelectorParser.getCustomFlag was reported broken while five shipping mods called it on 26.2.
// Static absence is not runtime absence, and a corpus of working builds is the only evidence of the
// difference available without launching the game.
// Several corpora, comma-separated, because one loader's evidence cannot speak for another's.
// NeoForge adds getModelData and getAuxLightManager to vanilla interfaces; they are in no Minecraft
// jar and in no Fabric mod, so against a Fabric-only corpus every one reads as a broken link.
// A corpus records names as they were in ITS version, so it can only excuse a reference that has not
// been ported yet. Verifying a ported jar against a 1.21.1 corpus therefore penalises exactly the
// references the port got right: BlockAndTintGetter.getModelData is excused before porting and
// counted as broken after, purely because its descriptor now says Identifier where the corpus says
// ResourceLocation. Measured that way a correct port always looks worse than doing nothing.
//
// So corpus keys are carried across the same hop the jar was, using the same mined tables. Only the
// class names move; the member name and shape are untouched.
const classRenames = new Map();
if (args.from && args.to) {
  const HERE2 = path.dirname(fileURLToPath(import.meta.url));
  for (const f of fs.readdirSync(HERE2).filter((x) => /^(classmoves|typesubs)\.[\d.]+-[\d.]+\.json$/.test(x))) {
    if (!f.includes(`.${args.from}-${args.to}.`)) continue;
    try {
      const tbl = JSON.parse(fs.readFileSync(path.join(HERE2, f), 'utf8'));
      for (const r of [...(tbl.moves || []), ...(tbl.renames || [])])
        if (r.from && r.to) classRenames.set(r.from.replace(/\./g, '/'), r.to.replace(/\./g, '/'));
    } catch { /* a table we cannot read simply does not contribute */ }
  }
}
const carry = (key) => {
  if (!classRenames.size) return key;
  const [owner, name, desc] = key.split('\t');
  const o2 = classRenames.get(owner) || owner;
  const d2 = (desc || '').replace(/L([\w/$]+);/g, (m0, c) => `L${classRenames.get(c) || c};`);
  return `${o2}\t${name}\t${d2}`;
};

// Corroboration has to come from OTHER mods, and from more than one of them. The corpus is built
// out of jars, and the jar being checked may well be one of them: appleskin's own record excused
// every reference appleskin makes, and all twelve mods scored a flawless zero. A member counts as
// loader-provided only when at least two DIFFERENT mods, neither of them this one, are seen calling
// it — the same standard the rest of the project applies to a mined rule.
// Which loader is this jar for? Evidence from one loader cannot excuse a link in another's mod.
// NeoForge adds its own methods to vanilla interfaces, so a NeoForge corpus asked about a Fabric mod
// would forgive members Fabric does not provide — turning a broken port into a clean report, which
// is the one outcome this tool exists to prevent. Fox-Grade is a Fabric tool; this keeps a corpus
// gathered for anything else from quietly answering on Fabric's behalf.
function loaderOf(jar) {
  try {
    for (const e of readZip(fs.readFileSync(jar))) {
      if (e.name === 'fabric.mod.json') return 'fabric';
      if (e.name === 'META-INF/neoforge.mods.toml') return 'neoforge';
      if (e.name === 'META-INF/mods.toml') return 'forge';
    }
  } catch { /* unreadable jar: fall through to unknown */ }
  return null;
}
const jarLoader = args.loader || loaderOf(JAR);

const CORROBORATE = +(args['corpus-min'] || 2);
const selfSlug = path.basename(JAR).replace(/\.jar$/, '').replace(/[^\w.-]+/g, '_');
const seenIn = new Map();
let skippedLoader = 0;
for (const dir of String(args.corpus || '').split(',').map((s) => s.trim()).filter(Boolean)) {
  if (!fs.existsSync(dir)) { console.error(`  ! no such corpus: ${dir}`); continue; }
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json.gz')) continue;
    if (f.replace(/\.json\.gz$/, '') === selfSlug) continue;          // a mod cannot vouch for itself
    let rec; try { rec = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(dir, f)))); } catch { continue; }
    // Records predating the loader field are Fabric, which is all this project ever targeted.
    const recLoader = rec.loader || 'fabric';
    if (jarLoader && recLoader !== jarLoader) { skippedLoader++; continue; }
    // Both name forms, because this tool cannot tell whether the jar in front of it has been ported.
    const keys = new Set();
    for (const r of rec.new?.refs || []) { keys.add(r); keys.add(carry(r)); }
    for (const k of keys) seenIn.set(k, (seenIn.get(k) || 0) + 1);
  }
}
if (skippedLoader) console.log(`  ${skippedLoader} corpus record(s) ignored — built for a different loader than this ${jarLoader} jar`);
const modProvided = new Set();
for (const [k, n] of seenIn) if (n >= CORROBORATE) modProvided.add(k);

// A broken link is far more useful when it says WHERE the member went. member-mine already mines
// relocations — Gui.getGuiTicks did not vanish, it moved to Hud and is reached through Gui's `hud`
// field — and that was being thrown away, so the report showed a bare ✗ for something whose answer
// was already sitting in the tables. Knowing it moved turns "this mod is broken" into a one-line fix,
// and it is exactly the context Tier 2 needs to make that fix itself.
const relocated = new Map();      // "owner\tname\tdesc" -> {via, host}
{
  const f = args.members || (args.from && args.to
    ? path.join(path.dirname(fileURLToPath(import.meta.url)), `members.${args.from}-${args.to}.json`)
    : null);
  if (f && fs.existsSync(f)) {
    try {
      for (const r of JSON.parse(fs.readFileSync(f, 'utf8')).relocations || [])
        relocated.set(`${r.owner}\t${r.name}\t${r.desc}`, r);
    } catch { /* a malformed table must not stop the check it is only annotating */ }
  }
}

// ── collect every link the jar makes into the target ─────────────────────────────────────────
const wanted = new Map();   // owner -> Set("kind name desc")
const reachable = new Set();   // only those an instruction actually names
let classes = 0;
function scan(buf) {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    classes++;
    // Split what the CODE reaches from what merely sits in the pool. The JVM resolves a constant
    // pool entry lazily, on first execution of an instruction naming it, so an entry no instruction
    // reaches can never throw. Counting the pool alone reports links nothing can follow — and after
    // a call site is redirected the old entry stays behind, inert, and would be reported forever.
    const data = inflateEntry(e);
    let live = new Set();
    try { live = liveRefs(ClassFile, data); } catch { /* fall back to treating everything as live */ }
    for (const r of new ClassFile(data).refs()) {
      if (!r.owner.startsWith(PREFIX)) continue;
      if (!wanted.has(r.owner)) wanted.set(r.owner, new Set());
      wanted.get(r.owner).add(`${r.kind} ${r.name} ${r.desc}`);
      if (live.size && live.has(`${r.owner}\t${r.name}\t${r.desc}`)) reachable.add(`${r.owner}\t${r.name}\t${r.desc}`);
    }
  }
}
scan(fs.readFileSync(JAR));

// ── what the target actually declares ────────────────────────────────────────────────────────
// javap -s prints each member's real descriptor, which is what the JVM matches on. Names alone
// would pass an overload that takes different arguments and fails at runtime anyway.
const declCache = new Map();
// javap takes many class names per invocation, and the JVM startup dominates the cost — batching
// turns hundreds of spawns into a handful. Its output for N classes is just N reports concatenated.
function preload(names) {
  const todo = [...new Set(names)].filter((n) => !declCache.has(n));
  for (let i = 0; i < todo.length; i += 200) {
    const batch = todo.slice(i, i + 200);
    const r = spawnSync('javap', ['-p', '-s', '-cp', cp, ...batch.map((c) => c.replace(/\//g, '.'))], { encoding: 'utf8', maxBuffer: 512e6 });
    if (!r.stdout) continue;
    // Split on each report's header line, keeping the header with its body.
    const parts = r.stdout.split(/\n(?=(?:Compiled from|public |final |abstract |class |interface |@))/);
    for (const p of parts) {
      const h = p.match(/\b(?:class|interface)\s+([\w.$]+)/);
      if (h) declCache.set(h[1].replace(/\./g, '/'), parseReport(p));
    }
    for (const c of batch) if (!declCache.has(c)) declCache.set(c, null);
  }
}
function declared(cls) {
  if (declCache.has(cls)) return declCache.get(cls);
  const r = spawnSync('javap', ['-p', '-s', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 });
  if (r.status !== 0 || !r.stdout) { declCache.set(cls, null); return null; }
  const out = parseReport(r.stdout);
  declCache.set(cls, out);
  return out;
}

// One javap report -> {members, supers}. Members are keyed by name + real descriptor, because the
// JVM matches on the descriptor: an overload taking different arguments is a different method.
function parseReport(text) {
  const members = new Set(), supers = [];
  const head = text.match(/^[^\n]*\b(?:class|interface)\s+([\w.$]+)([^{]*)\{/m);
  const selfName = head ? head[1].replace(/\./g, '/') : null;
  if (head) {
    // Split on the keywords first. Matching names with a class that contains both \w and \s runs
    // straight through `implements` and yields "Player implements ClientAvatarEntity" as one name.
    for (const part of head[2].split(/\b(?:extends|implements)\b/).slice(1))
      for (const t of part.split(',')) {
        const n = t.trim().replace(/<.*/, '').replace(/\./g, '/');
        if (/^[\w/$]+$/.test(n) && n !== 'java/lang/Object') supers.push(n);
      }
  }
  const lines = text.split('\n');
  const simple = selfName ? selfName.split('/').pop() : null;
  for (let i = 0; i < lines.length; i++) {
    const d = lines[i].match(/^\s*descriptor:\s*(\S+)\s*$/);
    if (!d) continue;
    const sig = lines[i - 1] || '';
    const nm = sig.match(/([\w$]+)\s*\(/) || sig.match(/([\w$]+);?\s*$/);
    if (!nm) continue;
    // javap prints a constructor as the class's own name; the JVM calls it <init>.
    members.add(`${nm[1] === simple ? '<init>' : nm[1]} ${d[1]}`);
  }
  return { members, supers };
}
// The JVM resolves a member by walking up the hierarchy, so a call can name a subclass as the owner
// while the method is declared three levels up. Checking only the named class invents failures.
// Every class inherits these, including interfaces, and javap does not list them. Without this,
// a plain `component.equals(x)` is reported as a missing method.
const OBJECT_METHODS = new Set([
  'equals (Ljava/lang/Object;)Z', 'hashCode ()I', 'toString ()Ljava/lang/String;',
  'getClass ()Ljava/lang/Class;', 'clone ()Ljava/lang/Object;', 'notify ()V', 'notifyAll ()V',
  'wait ()V', 'wait (J)V', 'wait (JI)V', 'finalize ()V',
]);
function resolves(cls, key, seen = new Set()) {
  if (OBJECT_METHODS.has(key)) return true;
  if (seen.has(cls)) return false;
  seen.add(cls);
  const d = declared(cls);
  if (!d) return false;
  if (d.members.has(key)) return true;
  return d.supers.some((s) => resolves(s, key, seen));
}

// ── report ───────────────────────────────────────────────────────────────────────────────────
preload([...wanted.keys()]);
// Resolution walks upward, so parents surface only after the first pass. Warming them in waves
// pulled in Minecraft's entire interface graph — thousands of classes for a jar that touches 93.
// Only the ancestors of classes we actually reference matter, so follow those edges alone.
const need = new Set(wanted.keys());
for (let wave = 0; wave < 8; wave++) {
  const parents = [];
  for (const c of need) { const d = declCache.get(c); if (d) for (const s2 of d.supers) if (!need.has(s2)) parents.push(s2); }
  if (!parents.length) break;
  preload(parents);
  for (const p2 of parents) need.add(p2);
}
if (process.env.FOXGRADE_DEBUG) console.error(`  [resolved hierarchy: ${need.size} classes]`);

const missingClasses = [], missingMembers = [], loaderProvided = [], inert = [];
let checked = 0;
for (const [owner, keys] of wanted) {
  if (!declared(owner)) { missingClasses.push([owner, keys.size]); continue; }
  for (const k of keys) {
    checked++;
    const [, name, desc] = k.split(' ');
    if (resolves(owner, `${name} ${desc}`)) continue;
    // Shipping builds for THIS version calling it is evidence something on the loader side provides
    // it. Reported separately rather than hidden, because it is an inference from other people's
    // working mods rather than something proved against the jars.
    if (modProvided.has(`${owner}\t${name}\t${desc}`)) { loaderProvided.push([owner, name, desc]); continue; }
    if (reachable.size && !reachable.has(`${owner}\t${name}\t${desc}`)) { inert.push([owner, name, desc]); continue; }
    missingMembers.push([owner, name, desc]);
  }
}

console.log(`  ${path.basename(JAR)}`);
console.log(`    classes scanned    : ${classes.toLocaleString()}`);
console.log(`    target classes used: ${wanted.size}`);
console.log(`    links checked      : ${checked}`);
// A name the remapper never translated is a different failure from one it translated wrongly: the
// first is a gap in the tables, the second is a bad rule. Reporting them together hides which.
const untranslated = missingClasses.filter(([c]) => /class_\d+|func_\d+/.test(c));
const mistranslated = missingClasses.filter(([c]) => !/class_\d+|func_\d+/.test(c));
console.log(`\n    MISSING CLASSES    : ${missingClasses.length}`);
if (untranslated.length) console.log(`      ${untranslated.length} never translated (no rule covered them):`);
for (const [c, n] of untranslated.slice(0, 8)) console.log(`      ✗ ${c.replace(/\//g, '.')}   (${n} link${n > 1 ? 's' : ''})`);
if (mistranslated.length) console.log(`      ${mistranslated.length} translated to a class that is not there:`);
for (const [c, n] of mistranslated.slice(0, 8)) console.log(`      ✗ ${c.replace(/\//g, '.')}   (${n} link${n > 1 ? 's' : ''})`);
const explained = missingMembers.filter(([o, n, d]) => relocated.has(`${o}\t${n}\t${d}`));
console.log(`    MISSING MEMBERS    : ${missingMembers.length}${explained.length ? `   (${explained.length} of them relocated, and we know where)` : ''}`);
for (const [o, n, d] of missingMembers.slice(0, 12)) {
  const r = relocated.get(`${o}\t${n}\t${d}`);
  console.log(`      ✗ ${o.replace(/\//g, '.')}.${n} ${d}`);
  if (r) console.log(`          moved to ${r.host.replace(/\//g, '.')} — reach it through .${r.via}`);
}
if (missingMembers.length > 12) console.log(`      … ${missingMembers.length - 12} more`);

if (inert.length) {
  console.log(`    inert              : ${inert.length}   (in the pool, but no instruction reaches them — never resolved)`);
}
if (loaderProvided.length) {
  console.log(`    loader-provided    : ${loaderProvided.length}   (absent from the jars, but shipping mods call them on this version)`);
  for (const [o, n] of loaderProvided.slice(0, 5)) console.log(`      ~ ${o.replace(/\//g, '.')}.${n}`);
}
const bad = missingClasses.length + missingMembers.length;
console.log(bad
  ? `\n  ${bad} link(s) will fail at runtime. Every one is a crash on the code path that reaches it.`
  : `\n  every link resolves against the target. That is necessary, not sufficient — behaviour still needs playing.`);
process.exit(bad ? 1 : 0);
