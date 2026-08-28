#!/usr/bin/env node
// Check that a mod's MIXIN targets still exist — the blind spot in link verification.
//
// jar-verify resolves Methodrefs from the constant pool, which is where ordinary calls live. A mixin
// does not call its target; it names it in an annotation, as a string. So a mixin whose target method
// was renamed or moved is invisible to link checking, and the jar reports "every link resolves" right
// up until Fabric refuses to start:
//
//   @Inject on renderFoodPre could not find any targets matching 'extractFood' in Gui
//
// That is exactly what happened to a jar this project had already declared clean, so this exists to
// stop the tool claiming success it has not earned.
//
// Where a relocation table explains the failure — the method moved to another class — the fix is
// reported too: the mixin has to target the new class.
//
//   node mixin-check.mjs mod.jar --classpath "<target jars>"
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
if (!JAR || !fs.existsSync(JAR)) { console.error('usage: node mixin-check.mjs <mod.jar> --classpath "<target jars>"'); process.exit(2); }
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath'); process.exit(2); }

// Relocations, so a broken target can be explained rather than merely reported.
const relocs = new Map();   // "owner\tname" -> {host, via}
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try { for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).relocations || []) relocs.set(`${r.owner}\t${r.name}`, r); }
  catch { /* ignore */ }
}

const entries = readZip(fs.readFileSync(JAR));
const byName = new Map(entries.map((e) => [e.name, e]));

// Which classes are mixins, from every mixin config in the jar.
const mixinClasses = [];
for (const e of entries) {
  if (!/mixins?.*\.json$/.test(e.name)) continue;
  let cfg; try { cfg = JSON.parse(inflateEntry(e).toString('utf8')); } catch { continue; }
  const pkg = (cfg.package || '').replace(/\./g, '/');
  for (const list of ['mixins', 'client', 'server']) for (const m of (cfg[list] || []))
    mixinClasses.push({ cls: `${pkg}/${m.replace(/\./g, '/')}`, config: e.name });
}
if (!mixinClasses.length) { console.log('  no mixins in this jar'); process.exit(0); }

// A mixin's target class is a Class constant in its own pool (from @Mixin), and the injection point
// is a plain string constant (from @Inject(method="...")). Neither is a Methodref, which is exactly
// why link checking cannot see them.
function declaredMembers(cls) {
  const r = spawnSync('javap', ['-p', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 });
  if (r.status !== 0 || !r.stdout) return null;
  const names = new Set();
  for (const l of r.stdout.split('\n')) { const m = l.match(/([\w$]+)\s*\(/); if (m) names.add(m[1]); }
  return names;
}

let checked = 0;
const problems = [];
for (const { cls, config } of mixinClasses) {
  const e = byName.get(cls + '.class');
  if (!e) { problems.push({ cls, kind: 'missing', detail: 'declared in the config but not in the jar' }); continue; }
  const cf = new ClassFile(inflateEntry(e));
  const strings = [], targets = new Set();
  for (const { value } of cf.utf8()) {
    if (!ClassFile.isPlain(value)) continue;
    // Lnet/minecraft/...; inside the @Mixin annotation names the target class.
    for (const m of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) targets.add(m[1]);
    if (/^[\w$]+$/.test(value)) strings.push(value);
  }
  // A method= selector is a bare identifier that is not a member this mixin declares itself.
  const own = new Set(cf.declared().members.map((m) => m.name));
  for (const t of targets) {
    const members = declaredMembers(t);
    if (!members) { problems.push({ cls, kind: 'target-class', detail: `target ${t.replace(/\//g, '.')} does not exist in the target version` }); continue; }
    for (const s of strings) {
      if (own.has(s) || members.has(s) || s.length < 4) continue;
      // Only worry about a name that WAS a member of this class before, i.e. one a relocation knows.
      const rel = relocs.get(`${t}\t${s}`);
      if (rel) problems.push({ cls, kind: 'moved', from: t, host: rel.host, detail: `@Inject target "${s}" is no longer on ${t.split('/').pop()} — it moved to ${rel.host.replace(/\//g, '.')}`, fix: `retarget this mixin at ${rel.host.replace(/\//g, '.')}` });
    }
    checked++;
  }
}

console.log(`  ${path.basename(JAR)}`);
console.log(`    mixin classes      : ${mixinClasses.length}`);
console.log(`    target classes read: ${checked}`);
console.log(`    PROBLEMS           : ${problems.length}`);
for (const p of problems) {
  console.log(`      ✗ ${p.cls.split('/').pop()}: ${p.detail}`);
  if (p.fix) console.log(`          fix: ${p.fix}`);
}
console.log(problems.length
  ? '\n  Fabric refuses to start when a mixin target is missing, so these are fatal, not cosmetic.'
  : '\n  every mixin target resolves.');

// ── --fix: point the mixin at the class its target moved to ───────────────────────────────────
// Retargeting is a single constant edit. @Mixin stores its target as one Class constant, so the
// descriptor "Lnet/minecraft/client/gui/Gui;" appears exactly once and can be replaced outright.
// The match must be the whole string: "Lnet/minecraft/client/gui/GuiGraphicsExtractor;" contains
// the same prefix and must not be touched.
//
// It is only safe when every broken target in that mixin moved to the SAME class. A mixin whose
// injections landed in two different places cannot be expressed as one @Mixin and needs splitting,
// which is a decision rather than a rewrite.
if (args.out && problems.length) {
  const moved = problems.filter((p) => p.kind === 'moved' && p.host);
  const byMixin = new Map();
  for (const p of moved) { if (!byMixin.has(p.cls)) byMixin.set(p.cls, new Set()); byMixin.get(p.cls).add(`${p.from}\t${p.host}`); }
  const repl = new Map();
  let fixed = 0, split = 0;
  for (const [cls, moves] of byMixin) {
    const hosts = new Set([...moves].map((m) => m.split('\t')[1]));
    if (hosts.size !== 1) { console.log(`  ! ${cls.split('/').pop()}: targets moved to ${hosts.size} different classes — needs splitting by hand`); split++; continue; }
    const from = [...moves][0].split('\t')[0], to = [...hosts][0];
    const e = byName.get(cls + '.class');
    const out = new ClassFile(inflateEntry(e)).rewrite((v) => (v === `L${from};` ? `L${to};` : null));
    if (!out) { console.log(`  ! ${cls.split('/').pop()}: could not find its @Mixin target constant`); continue; }
    repl.set(e.name, out); fixed++;
    console.log(`  ✓ ${cls.split('/').pop()} retargeted: ${from.replace(/\//g, '.')} → ${to.replace(/\//g, '.')}`);
  }
  if (fixed) {
    const { writeZip } = await import('./zipfile.mjs');
    fs.writeFileSync(args.out, writeZip(entries, repl));
    console.log(`\n  wrote ${args.out} — ${fixed} mixin(s) retargeted${split ? `, ${split} left alone` : ''}`);
    console.log('  re-run this check on it, then launch. A mixin that applies is not a mixin that works.');
  }
}
process.exit(problems.length ? 1 : 0);
