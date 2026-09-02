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

// Nested jars carry their own mixins, and amecs proved it the hard way: the crash came from
// amecs_mouse_inputs.mixins.json, a config inside META-INF/jars, which this check never opened —
// jar-verify recurses, this did not, so every bundled mixin in every mod was invisible. Entries are
// flattened with a path prefix so configs and classes pair up within their own jar.
const entries = [];
const flatten = (buf, prefix) => {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { flatten(inflateEntry(e), prefix + e.name + '\u0000'); } catch { /* skip */ } continue; }
    entries.push({ ...e, name: prefix + e.name, raw: e.raw, method: e.method });
  }
};
flatten(fs.readFileSync(JAR), '');
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
  const prefix = e.name.includes('\u0000') ? e.name.slice(0, e.name.lastIndexOf('\u0000') + 1) : '';
  for (const list of ['mixins', 'client', 'server']) for (const m of (cfg[list] || []))
    mixinClasses.push({ cls: `${prefix}${pkg}/${m.replace(/\./g, '/')}`, config: e.name });
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
  // Reference OWNERS come from Class constants, which are stored as bare internal names and so are
  // invisible to the descriptor scan above. They are needed now that a selector is only reported
  // when it is not a resolved reference — and without adding them here every one is a cache miss
  // and its own javap, which turns a couple of spawns into hundreds.
  for (const r of new ClassFile(inflateEntry(e)).refs())
    if (r.owner.startsWith('net/minecraft/')) allTargets.add(r.owner);
}
preloadMembers([...allTargets]);

let checked = 0;
const problems = [];
const preExisting = [];
for (const { cls, config } of mixinClasses) {
  const e = byName.get(cls + '.class');
  if (!e) { problems.push({ cls, kind: 'missing', detail: 'declared in the config but not in the jar' }); continue; }
  const cf = new ClassFile(inflateEntry(e));
  const strings = [], targets = new Set(), atTargets = [];
  for (const { value } of cf.utf8()) {
    if (!ClassFile.isPlain(value)) continue;
    // Lnet/minecraft/...; inside the @Mixin annotation names the target class.
    for (const m of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) targets.add(m[1]);
    if (/^[\w$]+$/.test(value)) strings.push(value);
    // A selector may be written in full-signature form — "onScroll(DDLnet/...;)V" — which the bare
    // identifier collector never matched, so those injections were checked against nothing at all.
    const sig = value.match(/^([\w$]+)\(.*\)[\w\[/;$]*$/);
    if (sig && sig[1].length >= 4) strings.push(sig[1]);
    // @At target strings are owner-QUALIFIED member references — "Lnet/.../Minecraft;screen:L...;"
    // for a field, "Lnet/.../Gui;render(...)V" for a method — and were invisible to every audit:
    // blur-plus shipped an @At still naming Minecraft.screen, a field this project knows relocated,
    // and crashed through two clean verdicts. Owner-qualified beats the bare cross-product: the
    // member is checked against exactly the class the annotation names.
    const at = value.match(/^L([\w/$]+);([\w$<>]+)[:(]/);
    if (at && at[1].startsWith('net/minecraft/')) atTargets.push({ owner: at[1], name: at[2], raw: value });
  }
  // A method= selector is a bare identifier that is not a member this mixin declares itself.
  const own = new Set(cf.declared().members.map((m) => m.name));
  // ...and, crucially, not a member the mixin actually REFERENCES. An annotation selector and a
  // field name are both plain UTF-8 constants with nothing to tell them apart by tag, so scanning
  // the pool for identifier-shaped strings picks up both. Lithium's GolemRandomStrollInVillageGoal
  // holds "VILLAGER" only as the name half of a NameAndType for a real Fieldref — the port had
  // already correctly rewritten it to EntityTypes.VILLAGER — and it was reported as a fatal missing
  // injection point because EntityType.VILLAGER no longer exists.
  //
  // Only names that RESOLVE are skipped. A reference that does not resolve is still a genuine
  // failure; it is simply jar-verify's to report, with the owner and descriptor that make it
  // actionable, rather than being guessed at here against every class the mixin happens to mention.
  const resolvedRefs = new Set();
  for (const r of cf.refs()) {
    if (!r.owner.startsWith('net/minecraft/')) continue;
    const dm = declaredMembers(r.owner);
    if (dm && dm.has(r.name)) resolvedRefs.add(r.name);
  }
  for (const a of atTargets) {
    const tm = declaredMembers(a.owner);
    if (tm && tm.has(a.name)) continue;                              // still there: fine
    const was = sourceMembers.get(a.owner);
    if (was && !was.has(a.name)) continue;                           // never existed: not port breakage
    const rel = relocs.get(`${a.owner}\t${a.name}`);
    problems.push({ cls, kind: 'at-target', detail: `@At target "${a.owner.split('/').pop()}.${a.name}" ${tm ? 'is gone from the target version' : 'names a class javap cannot see'}${rel ? ` — it moved to ${rel.host.replace(/\//g, '.')}` : ''}`,
      fix: rel ? 'the @At string needs rewriting at its new home — and if the member became a method, a decision' : 'needs a new anchor — a decision, not a rewrite' });
  }
  for (const t of targets) {
    const members = declaredMembers(t);
    if (!members) {
      // A target class that is ALSO absent from the SOURCE version was never going to apply there
      // either — boids ships a RealFishingMixin against MobSpawnType, which exists in neither 26.1
      // nor 26.2, gated behind a mixin plugin for older Minecraft. That is a version-conditional
      // mixin, not something this port broke, and counting it as fatal blames the port for the
      // mod's own multi-version scaffolding. Reported separately so it is visible, not hidden.
      if (sourceMembers.size && !sourceMembers.has(t)) { preExisting.push({ cls, t }); continue; }
      problems.push({ cls, kind: 'target-class', detail: `target ${t.replace(/\//g, '.')} does not exist in the target version` }); continue; }
    for (const s of strings) {
      if (own.has(s) || members.has(s) || s.length < 4 || resolvedRefs.has(s)) continue;
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
if (preExisting.length) console.log(`    pre-existing       : ${preExisting.length}   (target absent in the SOURCE version too — conditional mixin, not port breakage)`);
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
    // A corroborated successor is evidence about @Inject hooks. ModifyVariable and Redirect bind to
    // the target's internals — variables, exact instructions — and blur-plus showed what a
    // name-successor rewrite does to them: extractGui (a worker routine) was retargeted onto
    // gameRenderState (an accessor), and Mixin refused the whole class at apply time. If the mixin
    // class carries either annotation, its selectors are reported but never rewritten.
    const eCls = byName.get(p.cls + '.class');
    if (eCls) {
      try {
        let tight = false;
        for (const { value } of new ClassFile(inflateEntry(eCls)).utf8())
          if (/ModifyVariable|Redirect;$/.test(value)) { tight = true; break; }
        if (tight) { console.log(`  ! ${p.cls.split('/').pop()}: uses ModifyVariable/Redirect — selector ${p.from} NOT rewritten (needs a decision)`); continue; }
      } catch { /* unreadable: fall through to the rewrite */ }
    }
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
    // The pool stores one UTF-8 entry per distinct string, so an annotation selector "foo" and a
    // NameAndType name "foo" on some unrelated class are THE SAME ENTRY. Rewriting it to fix the
    // selector would silently repoint that reference too — the class would still verify and would
    // call the wrong method. Only rewrite a selector no surviving reference is using.
    const cfm = new ClassFile(inflateEntry(e));
    const refNames = new Set([...cfm.refs()].map((r) => r.name));
    const safe = ps.filter((p) => !refNames.has(p.from));
    for (const p of ps.filter((p) => refNames.has(p.from)))
      console.log(`  ! ${cls.split('/').pop()}: selector ${p.from} is also a live member reference — not rewritten, needs hand work`);
    if (!safe.length) continue;
    const map = new Map(safe.map((p) => [p.from, p.to]));
    const out = cfm.rewrite((v) => map.get(v) || null);
    if (!out) continue;
    repl.set(e.name, out); selectors += safe.length;
    for (const p of safe) console.log(`  ✓ ${cls.split('/').pop()}: injection point ${p.from} → ${p.to}`);
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
    // Not equality: a fully-qualified @At/@Inject selector EMBEDS the class descriptor inside a
    // longer string — target="Lnet/.../Gui;extractHearts(...)" — so an exact match rewrites the
    // @Mixin value, leaves the selector aimed at the old class, and ships a half-retargeted mixin
    // that still fails. Every embedded occurrence moves together or the retarget is a lie. The
    // guard above already established the mixin uses nothing that stayed behind on the old class.
    const out = new ClassFile(inflateEntry(e)).rewrite((v) => (v.includes(`L${from};`) ? v.split(`L${from};`).join(`L${to};`) : null));
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
