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
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

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
const modProvided = new Set();
if (args.corpus && fs.existsSync(args.corpus)) {
  for (const f of fs.readdirSync(args.corpus)) {
    if (!f.endsWith('.json.gz')) continue;
    let rec; try { rec = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(args.corpus, f)))); } catch { continue; }
    for (const r of rec.new.refs || []) modProvided.add(r);
  }
}

// ── collect every link the jar makes into the target ─────────────────────────────────────────
const wanted = new Map();   // owner -> Set("kind name desc")
let classes = 0;
function scan(buf) {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    classes++;
    for (const r of new ClassFile(inflateEntry(e)).refs()) {
      if (!r.owner.startsWith(PREFIX)) continue;
      if (!wanted.has(r.owner)) wanted.set(r.owner, new Set());
      wanted.get(r.owner).add(`${r.kind} ${r.name} ${r.desc}`);
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

const missingClasses = [], missingMembers = [], loaderProvided = [];
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
console.log(`    MISSING MEMBERS    : ${missingMembers.length}`);
for (const [o, n, d] of missingMembers.slice(0, 12)) console.log(`      ✗ ${o.replace(/\//g, '.')}.${n} ${d}`);
if (missingMembers.length > 12) console.log(`      … ${missingMembers.length - 12} more`);

if (loaderProvided.length) {
  console.log(`    loader-provided    : ${loaderProvided.length}   (absent from the jars, but shipping mods call them on this version)`);
  for (const [o, n] of loaderProvided.slice(0, 5)) console.log(`      ~ ${o.replace(/\//g, '.')}.${n}`);
}
const bad = missingClasses.length + missingMembers.length;
console.log(bad
  ? `\n  ${bad} link(s) will fail at runtime. Every one is a crash on the code path that reaches it.`
  : `\n  every link resolves against the target. That is necessary, not sufficient — behaviour still needs playing.`);
process.exit(bad ? 1 : 0);
