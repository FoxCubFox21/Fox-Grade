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
// Boolean flags must be declared, or "--best-guess --out x.jar" silently eats --out as its value
// and the output is never written — which produced a whole sweep of before==after numbers.
const FLAGS = new Set(['best-guess', 'quiet']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const JAR = pos[0];
if (!JAR || !fs.existsSync(JAR)) { console.error('usage: node mixin-check.mjs <mod.jar> --classpath "<target jars>"'); process.exit(2); }
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath'); process.exit(2); }

// Member renames, so an injection point that was merely renamed can be rewritten rather than just
// reported. A mixin names its target method as a plain string in an annotation, not as a Methodref,
// so the normal member remapping never sees it — the rule can be sitting in the table while the
// mixin still names the old method and Fabric refuses to start.
const memberRenames = new Map();   // "owner\tname" -> newName
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try {
    const t = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'));
    for (const r of t.renames || []) memberRenames.set(`${r.owner}\t${r.from}`, r.to);
    if (args['best-guess']) for (const r of t.guesses || []) if (!memberRenames.has(`${r.owner}\t${r.from}`)) memberRenames.set(`${r.owner}\t${r.from}`, r.to);
  } catch { /* ignore */ }
}
try {
  for (const r of (JSON.parse(fs.readFileSync(path.join(HERE, 'members.overrides.json'), 'utf8')).renames || []))
    memberRenames.set(`${r.owner}\t${r.name}`, r.becomes);
} catch { /* no overrides is fine */ }

// Injection points chosen by the mods' own maintainers, read back out of their released builds.
// These cover hooks that were DELETED rather than renamed, where no mapping exists and the answer
// is a decision — one that has already been made and can simply be reused. Corroborated
// substitutions (several unrelated mods landing on the same one) are treated as facts about the
// game; a single mod's choice is that mod's decision and needs --best-guess.
for (const f of fs.readdirSync(HERE).filter((x) => /^mixin-points\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try {
    const t = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'));
    for (const r of t.corroborated || []) if (!memberRenames.has(`${r.owner}\t${r.from}`)) memberRenames.set(`${r.owner}\t${r.from}`, r.to);
    if (args['best-guess']) for (const r of t.single || []) if (!memberRenames.has(`${r.owner}\t${r.from}`)) memberRenames.set(`${r.owner}\t${r.from}`, r.to);
  } catch { /* ignore */ }
}

// Relocations, so a broken target can be explained rather than merely reported.
const relocs = new Map();   // "owner\tname" -> {host, via}
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try { for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).relocations || []) relocs.set(`${r.owner}\t${r.name}`, r); }
  catch { /* ignore */ }
}

const entries = readZip(fs.readFileSync(JAR));
const byName = new Map(entries.map((e) => [e.name, e]));

// The SOURCE version's classes, which turn a loose guess into a precise test. A string in a mixin's
// pool is only an injection target worth worrying about if it named a member of the target class
// BEFORE and does not now. Without this the checker could only report targets it could explain via a
// relocation, and stayed silent on ones that were merely renamed or deleted — which is most of them.
const sourceMembers = new Map();   // class -> Set(member names)
if (args['source-classpath']) {
  for (const j of args['source-classpath'].split(':')) {
    if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
    for (const e of readZip(fs.readFileSync(j))) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const d = new ClassFile(inflateEntry(e)).declared();
        if (d.name) sourceMembers.set(d.name, new Set(d.members.map((m) => m.name)));
      } catch { /* skip */ }
    }
  }
}

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
// javap takes many classes per call and its startup dominates the cost. One spawn per target class
// left a large mod like iris grinding for minutes; batching makes the whole sweep tractable.
const memberCache = new Map();
function preloadMembers(names) {
  const todo = [...new Set(names)].filter((n) => !memberCache.has(n));
  for (let i = 0; i < todo.length; i += 150) {
    const batch = todo.slice(i, i + 150);
    const r = spawnSync('javap', ['-p', '-cp', cp, ...batch.map((c) => c.replace(/\//g, '.'))], { encoding: 'utf8', maxBuffer: 512e6 });
    if (r.stdout) for (const part of r.stdout.split(/\n(?=(?:Compiled from|public |final |abstract |class |interface |@))/)) {
      const h = part.match(/\b(?:class|interface)\s+([\w.$]+)/);
      if (!h) continue;
      const names2 = new Set();
      for (const l of part.split('\n')) {
        const m = l.match(/([\w$]+)\s*\(/); if (m) { names2.add(m[1]); continue; }
        // Fields too. Collecting only methods left the retarget guard blind to exactly the case it
        // exists for: a mixin @Shadowing a FIELD of its original target. A method line ends in ");"
        // so it cannot be caught here by accident.
        const f = l.match(/^\s+.*?([\w$]+)\s*;\s*$/); if (f) names2.add(f[1]);
      }
      memberCache.set(h[1].replace(/\./g, '/'), names2);
    }
    for (const c of batch) if (!memberCache.has(c)) memberCache.set(c, null);
  }
}
function declaredMembers(cls) {
  if (!memberCache.has(cls)) preloadMembers([cls]);
  return memberCache.get(cls);
}

// Collect every target across every mixin first, so the whole sweep costs a couple of javap calls.
const allTargets = new Set();
for (const { cls } of mixinClasses) {
  const e = byName.get(cls + '.class');
  if (!e) continue;
  for (const { value } of new ClassFile(inflateEntry(e)).utf8()) {
    if (!ClassFile.isPlain(value)) continue;
    for (const m of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) allTargets.add(m[1]);
  }
}
preloadMembers([...allTargets]);

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
      const rel = relocs.get(`${t}\t${s}`);
      if (rel) { problems.push({ cls, kind: 'moved', from: t, host: rel.host, detail: `@Inject target "${s}" is no longer on ${t.split('/').pop()} — it moved to ${rel.host.replace(/\//g, '.')}`, fix: `retarget this mixin at ${rel.host.replace(/\//g, '.')}` }); continue; }
      // Not explainable, but still broken: it named a member of this class in the source version and
      // does not in the target. Fabric will refuse to start on it either way, so report it.
      const was = sourceMembers.get(t);
      if (!was || !was.has(s)) continue;
      const renamed = memberRenames.get(`${t}\t${s}`);
      if (renamed && members.has(renamed)) {
        problems.push({ cls, kind: 'renamed', target: t, from: s, to: renamed,
          detail: `@Inject/@Redirect target "${s}" was renamed to "${renamed}" on ${t.split('/').pop()}`,
          fix: `rewrite the annotation's method selector` });
        continue;
      }
      problems.push({ cls, kind: 'gone', detail: `@Inject/@Redirect target "${s}" existed on ${t.split('/').pop()} before and does not now`, fix: 'needs a new injection point — a decision, not a rewrite' });
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
  // Renamed injection points first: the selector is a plain string, so rewriting it is exact. This
  // is the answer to "why not inject where they do" — we already knew where, and simply never wrote
  // it into the annotation.
  const renamedByCls = new Map();
  for (const p of problems.filter((x) => x.kind === 'renamed')) {
    if (!renamedByCls.has(p.cls)) renamedByCls.set(p.cls, []);
    renamedByCls.get(p.cls).push(p);
  }
  const moved = problems.filter((p) => p.kind === 'moved' && p.host);
  const byMixin = new Map();
  for (const p of moved) { if (!byMixin.has(p.cls)) byMixin.set(p.cls, new Set()); byMixin.get(p.cls).add(`${p.from}\t${p.host}`); }
  const repl = new Map();
  let fixed = 0, split = 0, selectors = 0;
  for (const [cls, ps] of renamedByCls) {
    const e = byName.get(cls + '.class');
    if (!e) continue;
    const map = new Map(ps.map((p) => [p.from, p.to]));
    const out = new ClassFile(inflateEntry(e)).rewrite((v) => map.get(v) || null);
    if (!out) continue;
    repl.set(e.name, out); selectors += ps.length;
    for (const p of ps) console.log(`  ✓ ${cls.split('/').pop()}: injection point ${p.from} → ${p.to}`);
  }
  for (const [cls, moves] of byMixin) {
    const hosts = new Set([...moves].map((m) => m.split('\t')[1]));
    if (hosts.size !== 1) { console.log(`  ! ${cls.split('/').pop()}: targets moved to ${hosts.size} different classes — needs splitting by hand`); split++; continue; }
    const from = [...moves][0].split('\t')[0], to = [...hosts][0];
    const e = byName.get(cls + '.class');
    // One method moving to a delegate does NOT mean the mixin moved. LevelRenderer holds a field of
    // type EntityRenderDispatcher, so a single relocated method along that field made this retarget
    // a mixin whose @Shadow of LevelRenderer's own entityRenderDispatcher field then had nowhere to
    // resolve — and Fabric refused to start. If the mixin still needs anything that lives only on
    // the original target, it still belongs there.
    const oldMembers = memberCache.get(from), newMembers = memberCache.get(to);
    if (oldMembers && newMembers) {
      const cf2 = new ClassFile(inflateEntry(e));
      const own2 = new Set(cf2.declared().members.map((m) => m.name));
      const stillNeeded = [];
      for (const { value } of cf2.utf8()) {
        if (!ClassFile.isPlain(value) || !/^[\w$]+$/.test(value) || value.length < 4) continue;
        if (own2.has(value)) continue;
        if (oldMembers.has(value) && !newMembers.has(value)) stillNeeded.push(value);
      }
      if (stillNeeded.length) {
        console.log(`  ! ${cls.split('/').pop()}: still uses ${[...new Set(stillNeeded)].slice(0, 3).join(', ')} from ${from.split('/').pop()} — not retargeted`);
        continue;
      }
    }
    const out = new ClassFile(inflateEntry(e)).rewrite((v) => (v === `L${from};` ? `L${to};` : null));
    if (!out) { console.log(`  ! ${cls.split('/').pop()}: could not find its @Mixin target constant`); continue; }
    repl.set(e.name, out); fixed++;
    console.log(`  ✓ ${cls.split('/').pop()} retargeted: ${from.replace(/\//g, '.')} → ${to.replace(/\//g, '.')}`);
  }
  if (fixed || selectors) {
    const { writeZip } = await import('./zipfile.mjs');
    fs.writeFileSync(args.out, writeZip(entries, repl));
    console.log(`\n  wrote ${args.out} — ${fixed} mixin(s) retargeted, ${selectors} injection point(s) renamed${split ? `, ${split} left alone` : ''}`);
    console.log('  re-run this check on it, then launch. A mixin that applies is not a mixin that works.');
  }
}
process.exit(problems.length ? 1 : 0);
