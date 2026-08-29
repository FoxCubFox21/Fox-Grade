#!/usr/bin/env node
// Mine for what a specific jar actually needs, rather than sweeping categories and hoping.
//
// The other miners are supply-driven: diff everything, emit every rename or injection point that
// falls out, hope the useful ones are among them. That is backwards. jar-verify already produces the
// exact list of what is missing, and that list is a set of QUESTIONS the corpus may be able to
// answer:
//
//   net.minecraft.util.Tuple                     — which mod stopped using this, and what for?
//   net.minecraft.ChatFormatting.getColor()      — same question
//
// Searching per question changes three things. Effort goes where it blocks instead of into thousands
// of facts about classes nobody touches. Failure becomes precise — "18 mods searched, no replacement
// for Tuple found anywhere" tells you to add corpus rather than leaving a bare count. And a fix that
// matches no known pattern still surfaces, which is the only way a MISSING CATEGORY announces
// itself; every category in this project so far came from me noticing a crash.
//
//   node demand-mine.mjs mod.jar --pairs pairs261.json --classpath "<target>" --source-classpath <src>
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['quiet', 'json']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const JAR = pos[0];
if (!JAR || !fs.existsSync(JAR)) { console.error('usage: node demand-mine.mjs <jar> --pairs pairs.json --classpath ... --source-classpath ...'); process.exit(2); }
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath'); process.exit(2); }

// ── class/member indexes at both versions ────────────────────────────────────────────────────
function indexJars(cpStr) {
  const out = new Map();
  for (const j of (cpStr || '').split(':')) {
    if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
    for (const e of readZip(fs.readFileSync(j))) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const d = new ClassFile(inflateEntry(e)).declared();
        if (d.name) out.set(d.name, { members: new Set(d.members.map((m) => `${m.name}\t${m.desc}`)), supers: [d.super, ...(d.interfaces || [])].filter(Boolean) });
      } catch { /* skip */ }
    }
  }
  return out;
}
// The JVM resolves a member by walking up the hierarchy, so a call can name a subclass while the
// member is declared three levels above. Checking only declared members reported Button.active and
// MutableComponent.getString as missing — 40 phantom needs against a real count of 3.
// Every class inherits these, and javap-style indexes do not list them.
const OBJECT_METHODS = new Set(['equals\t(Ljava/lang/Object;)Z', 'hashCode\t()I', 'toString\t()Ljava/lang/String;',
  'getClass\t()Ljava/lang/Class;', 'clone\t()Ljava/lang/Object;', 'notify\t()V', 'notifyAll\t()V',
  'wait\t()V', 'wait\t(J)V', 'wait\t(JI)V', 'finalize\t()V']);
// Returns true if present, false if provably absent, and null if the hierarchy leaves the indexed
// world — a JDK superclass like Enum or Record, whose members we cannot see. Treating that third
// case as "absent" claimed ScreenDirection.ordinal() was missing; it comes from java.lang.Enum.
// Not being able to check is not the same as having checked.
function hasMember(idx, cls, key, seen = new Set()) {
  if (OBJECT_METHODS.has(key)) return true;
  if (seen.has(cls)) return false;
  seen.add(cls);
  // Object is a known endpoint: it declares only the methods above, so reaching it is a definite
  // absence rather than an unknown. Without this every walk ends outside the index and NOTHING is
  // ever reported missing — the opposite failure, and just as silent.
  if (cls === 'java/lang/Object') return false;
  const d = idx.get(cls);
  if (!d) return null;                      // outside what we indexed: unknowable, not absent
  if (d.members.has(key)) return true;
  let unknown = false;
  for (const s of d.supers) {
    const r = hasMember(idx, s, key, seen);
    if (r === true) return true;
    if (r === null) unknown = true;
  }
  return unknown ? null : false;
}
function memberNames(idx, cls, seen = new Set()) {
  const out = new Set();
  if (seen.has(cls)) return out;
  seen.add(cls);
  const d = idx.get(cls);
  if (!d) return out;
  for (const m of d.members) out.add(m);
  for (const s of d.supers) for (const m of memberNames(idx, s, seen)) out.add(m);
  return out;
}
const targetIdx = indexJars(cp);
const sourceIdx = indexJars(args['source-classpath']);
if (!targetIdx.size) { console.error('no classes on --classpath'); process.exit(2); }

// ── 1. THE DEMAND: what does this jar reach for that is not there? ───────────────────────────
const demandClasses = new Set(), demandMembers = new Map();   // "owner\tname\tdesc" -> count
function collect(buf) {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { collect(inflateEntry(e)); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
    for (const r of cf.refs()) {
      if (!r.owner.startsWith('net/minecraft/')) continue;
      if (!targetIdx.has(r.owner)) { demandClasses.add(r.owner); continue; }
      if (hasMember(targetIdx, r.owner, `${r.name}\t${r.desc}`) === false) {
        const k = `${r.owner}\t${r.name}\t${r.desc}`;
        demandMembers.set(k, (demandMembers.get(k) || 0) + 1);
      }
    }
  }
}
collect(fs.readFileSync(JAR));

// Mixin injection points: named as strings, so they need the source index to be recognised at all.
const demandHooks = new Map();   // "owner\tselector" -> count
{
  const entries = readZip(fs.readFileSync(JAR));
  const byName = new Map(entries.map((e) => [e.name, e]));
  const cfgs = entries.filter((e) => /mixins?.*\.json$/.test(e.name));
  for (const c of cfgs) {
    let cfg; try { cfg = JSON.parse(inflateEntry(c).toString('utf8')); } catch { continue; }
    const pkg = (cfg.package || '').replace(/\./g, '/');
    for (const list of ['mixins', 'client', 'server']) for (const m of (cfg[list] || [])) {
      const e = byName.get(`${pkg}/${m.replace(/\./g, '/')}.class`);
      if (!e) continue;
      let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
      const own = new Set(cf.declared().members.map((x) => x.name));
      const targets = new Set(), strs = [];
      for (const { value } of cf.utf8()) {
        if (!ClassFile.isPlain(value)) continue;
        for (const g of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) targets.add(g[1]);
        if (/^[a-z][\w$]{3,}$/.test(value) && !own.has(value)) strs.push(value);
      }
      for (const t of targets) {
        if (!targetIdx.has(t) || !sourceIdx.has(t)) continue;
        const nowNames = new Set([...memberNames(targetIdx, t)].map((x) => x.split('\t')[0]));
        const wasNames = new Set([...memberNames(sourceIdx, t)].map((x) => x.split('\t')[0]));
        for (const sel of strs) if (wasNames.has(sel) && !nowNames.has(sel)) {
          const k = `${t}\t${sel}`;
          demandHooks.set(k, (demandHooks.get(k) || 0) + 1);
        }
      }
    }
  }
}

// ── 2. THE SEARCH: what did other mods do about each of these? ───────────────────────────────
const pairs = fs.existsSync(args.pairs || '') ? JSON.parse(fs.readFileSync(args.pairs, 'utf8')) : [];
// For each corpus mod: everything its old build referenced, and everything its new build referenced.
const corpus = [];
for (const p of pairs) {
  if (!fs.existsSync(p.old) || !fs.existsSync(p.new)) continue;
  const read = (jar) => {
    const refs = new Set(), sels = new Map();
    const scan = (buf) => {
      for (const e of readZip(buf)) {
        if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
        if (!e.name.endsWith('.class')) continue;
        let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
        for (const r of cf.refs()) if (r.owner.startsWith('net/minecraft/')) refs.add(`${r.owner}\t${r.name}\t${r.desc}`);
        if (/Mixin[\w$]*\.class$/.test(e.name)) {
          const own = new Set(cf.declared().members.map((x) => x.name));
          const tg = new Set();
          for (const { value } of cf.utf8()) {
            if (!ClassFile.isPlain(value)) continue;
            for (const g of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) tg.add(g[1]);
          }
          for (const { value } of cf.utf8())
            if (ClassFile.isPlain(value) && /^[a-z][\w$]{3,}$/.test(value) && !own.has(value))
              for (const t of tg) { const k = `${t}\t${value}`; sels.set(k, (sels.get(k) || 0) + 1); }
        }
      }
    };
    scan(fs.readFileSync(jar));
    return { refs, sels };
  };
  corpus.push({ mod: path.basename(p.new).replace(/[-_]?\d.*$/, ''), old: read(p.old), new: read(p.new) });
}

// A question is answered when a mod that USED the missing thing stopped using it, and started using
// something on the same class with the same shape. Requiring the mod to have used it is what keeps
// this from matching coincidences across unrelated code.
function answerMember(owner, name, desc) {
  const votes = new Map();
  for (const c of corpus) {
    if (!c.old.refs.has(`${owner}\t${name}\t${desc}`)) continue;      // this mod never used it
    if (c.new.refs.has(`${owner}\t${name}\t${desc}`)) continue;       // still uses it; nothing learned
    if (!targetIdx.has(owner)) continue;
    // Compiler-generated members are not anybody's replacement for anything.
    const synthetic = (n) => n.startsWith('<') || n.startsWith('lambda$') || n.startsWith('access$') || n.startsWith('$');
    const candidates = [...c.new.refs]
      .map((r) => r.split('\t'))
      .filter(([o, n, d]) => o === owner && d === desc && n !== name && !synthetic(n) && !c.old.refs.has(`${o}\t${n}\t${d}`));
    for (const [, n] of candidates) {
      if (!votes.has(n)) votes.set(n, new Set());
      votes.get(n).add(c.mod);
    }
  }
  return [...votes].map(([to, mods]) => ({ to, mods: [...mods] })).sort((a, b) => b.mods.length - a.mods.length);
}
function answerHook(owner, sel) {
  const votes = new Map();
  for (const c of corpus) {
    if (!c.old.sels.has(`${owner}\t${sel}`)) continue;
    if (c.new.sels.has(`${owner}\t${sel}`)) continue;
    const nowNames = new Set([...memberNames(targetIdx, owner)].map((x) => x.split('\t')[0]));
    for (const k of c.new.sels.keys()) {
      const [o, s] = k.split('\t');
      if (o !== owner || c.old.sels.has(k) || !nowNames.has(s)) continue;
      if (s.startsWith('lambda$') || s.startsWith('access$') || s.startsWith('$')) continue;
      if (!votes.has(s)) votes.set(s, new Set());
      votes.get(s).add(c.mod);
    }
  }
  return [...votes].map(([to, mods]) => ({ to, mods: [...mods] })).sort((a, b) => b.mods.length - a.mods.length);
}

// ── 3. REPORT: answered, and — just as important — what was searched for and not found ───────
const answered = [], unanswered = [], classGaps = [];
for (const [k, uses] of [...demandMembers].sort((a, b) => b[1] - a[1])) {
  const [owner, name, desc] = k.split('\t');
  const cands = answerMember(owner, name, desc);
  if (cands.length && cands[0].mods.length) answered.push({ kind: 'member', owner, from: name, desc, to: cands[0].to, mods: cands[0].mods, rivals: cands.slice(1, 3).map((c) => c.to), uses });
  else unanswered.push({ kind: 'member', owner, from: name, desc, uses });
}
for (const [k, uses] of [...demandHooks].sort((a, b) => b[1] - a[1])) {
  const [owner, sel] = k.split('\t');
  const cands = answerHook(owner, sel);
  if (cands.length && cands[0].mods.length) answered.push({ kind: 'hook', owner, from: sel, to: cands[0].to, mods: cands[0].mods, rivals: cands.slice(1, 3).map((c) => c.to), uses });
  else unanswered.push({ kind: 'hook', owner, from: sel, uses });
}
for (const c of demandClasses) classGaps.push(c);

const short = (x) => x.replace(/\//g, '.').split('.').slice(-2).join('.');
console.log(`  ${path.basename(JAR)}`);
console.log(`    corpus              : ${corpus.length} mod pairs`);
console.log(`    things it needs     : ${demandMembers.size} members, ${demandHooks.size} hooks, ${demandClasses.size} classes`);
console.log(`\n    ANSWERED by the corpus : ${answered.length}`);
for (const a of answered.slice(0, 14)) {
  console.log(`      ✓ ${short(a.owner)}.${a.from} → ${a.to}   [${a.mods.length} mod${a.mods.length > 1 ? 's' : ''}: ${a.mods.slice(0, 3).join(', ')}]${a.rivals.length ? `  (also saw: ${a.rivals.join(', ')})` : ''}`);
}
console.log(`\n    NO EVIDENCE ANYWHERE   : ${unanswered.length}`);
for (const u of unanswered.slice(0, 10)) console.log(`      ? ${short(u.owner)}.${u.from}${u.desc ? ' ' + u.desc : ''}   (used ${u.uses}×)`);
if (classGaps.length) { console.log(`\n    classes absent entirely: ${classGaps.length}`); for (const c of classGaps.slice(0, 6)) console.log(`      ✗ ${c.replace(/\//g, '.')}`); }
console.log(`\n  "No evidence" means no mod in this corpus solved it — add mod pairs, or it needs a person.`);

const OUT = args.out || path.join(HERE, `demand.${path.basename(JAR, '.jar')}.json`);
fs.writeFileSync(OUT, JSON.stringify({ schema: 1, jar: path.basename(JAR), corpus: corpus.length, answered, unanswered, classGaps }, null, 1) + '\n');
console.log(`  wrote ${path.basename(OUT)}`);
