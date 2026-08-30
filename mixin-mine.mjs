#!/usr/bin/env node
// Mine NEW INJECTION POINTS from mods whose authors already ported them.
//
// When a hook is deleted rather than renamed, no mapping table helps: choosing where to inject
// instead requires knowing what the mod is trying to do. But that choice has already been made —
// by the maintainer, in their own 26.2 build. This reads it back out.
//
// The unit of evidence is one mixin class present in both versions. If freecam's
// ItemInHandRendererMixin injected into renderHandsWithItems before and submitHandsWithItems after,
// that pair is the author's answer for that hook.
//
// A single mod's choice is a mod-specific decision. The SAME substitution appearing in several
// unrelated mods is a fact about Minecraft, and only those are worth applying generally — so
// per-mod answers are reported separately from corroborated ones rather than mixed together.
//
//   node mixin-mine.mjs --pairs pairs261.json --classpath "<26.2 jars>" --source-classpath mc-26.1.jar
import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['quiet']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
// --corpus reads pre-extracted indexes, so this scales to thousands of pairs without keeping jars.
const corpusDir = args.corpus;
const pairs = corpusDir ? [] : JSON.parse(fs.readFileSync(args.pairs || 'pairs261.json', 'utf8'));
const MIN = +(args.min || 2);

// Members of every class, at both versions — the test for whether a selector is a real hook.
function indexJars(cpStr) {
  const out = new Map();
  for (const j of (cpStr || '').split(':')) {
    if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
    for (const e of readZip(fs.readFileSync(j))) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const d = new ClassFile(inflateEntry(e)).declared();
        if (d.name) out.set(d.name, new Set(d.members.map((m) => m.name)));
      } catch { /* skip */ }
    }
  }
  return out;
}
const newIdx = indexJars(args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : ''));
const oldIdx = indexJars(args['source-classpath']);
if (!newIdx.size || !oldIdx.size) { console.error('need --classpath (target) and --source-classpath (source jar)'); process.exit(2); }

// One mixin class -> {targets it names, selector strings it carries}
function mixinsOf(jarPath) {
  const out = new Map();
  const scan = (buf) => {
    for (const e of readZip(buf)) {
      if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
      if (!e.name.endsWith('.class') || !/Mixin[\w$]*\.class$/.test(e.name)) continue;
      let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
      const targets = new Set(), selectors = new Set();
      const own = new Set(cf.declared().members.map((m) => m.name));
      for (const { value } of cf.utf8()) {
        if (!ClassFile.isPlain(value)) continue;
        for (const m of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) targets.add(m[1]);
        if (/^[a-z][\w$]{3,}$/.test(value) && !own.has(value)) selectors.add(value);
      }
      out.set(e.name.replace(/^.*\//, ''), { targets: [...targets], selectors });
    }
  };
  scan(fs.readFileSync(jarPath));
  return out;
}

function commonRun(a, b) {
  a = a.toLowerCase(); b = b.toLowerCase();
  let best = 0, prev = new Array(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    const cur = new Array(b.length + 1).fill(0);
    for (let j = 1; j <= b.length; j++) if (a[i - 1] === b[j - 1]) { cur[j] = prev[j - 1] + 1; if (cur[j] > best) best = cur[j]; }
    prev = cur;
  }
  return best;
}

// Member renames already derived from diffing the game jars — stronger evidence than a mixin diff.
const knownRenames = new Map();
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try { for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).renames || []) knownRenames.set(`${r.owner}\t${r.from}`, r.to); }
  catch { /* ignore */ }
}
try { for (const r of JSON.parse(fs.readFileSync(path.join(HERE, 'members.overrides.json'), 'utf8')).renames || []) knownRenames.set(`${r.owner}\t${r.name}`, r.becomes); }
catch { /* ignore */ }

// "owner\told\tnew" -> Set(mods)
const votes = new Map();
let mixinsCompared = 0, modsUsed = 0;

// A corpus record holds every (targetClass, selector) pair its mixins named, already extracted.
// Alignment is per target class rather than per mixin class — the index does not keep which mixin a
// selector came from — so the 1:1 rule below carries more of the weight than it does with jars.
if (corpusDir) {
  for (const f of fs.readdirSync(corpusDir)) {
    if (!f.endsWith('.json.gz')) continue;
    let rec; try { rec = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(corpusDir, f)))); } catch { continue; }
    const byTarget = (arr) => { const m = new Map(); for (const k of arr || []) { const [t, sel] = k.split('\t'); if (!m.has(t)) m.set(t, new Set()); m.get(t).add(sel); } return m; };
    const A = byTarget(rec.old.sels), B = byTarget(rec.new.sels);
    let used = false;
    for (const [t, aSels] of A) {
      const bSels = B.get(t); if (!bSels) continue;
      const was = oldIdx.get(t), now = newIdx.get(t);
      if (!was || !now) continue;
      const wasN = new Set([...was].map((x) => x.split('\t')[0])), nowN = new Set([...now].map((x) => x.split('\t')[0]));
      const gone = [...aSels].filter((s2) => wasN.has(s2) && !nowN.has(s2) && !bSels.has(s2));
      const arrived = [...bSels].filter((s2) => nowN.has(s2) && !aSels.has(s2));
      if (!gone.length || !arrived.length) continue;
      mixinsCompared++;
      const cands = [];
      if (gone.length === 1 && arrived.length === 1) cands.push([gone[0], arrived[0]]);
      else for (const g of gone) {
        const ranked = arrived.map((x) => ({ x, run: commonRun(g, x) })).sort((u, v) => v.run - u.run);
        if (ranked[0].run >= 6 && (ranked.length === 1 || ranked[0].run > ranked[1].run + 1)) cands.push([g, ranked[0].x]);
      }
      for (const [g, a2] of cands) {
        const known = knownRenames.get(`${t}\t${g}`);
        if (known && known !== a2) continue;
        vote(t, g, a2, rec.slug); used = true;
      }
    }
    if (used) modsUsed++;
  }
}

for (const p of pairs) {
  if (!fs.existsSync(p.old) || !fs.existsSync(p.new)) continue;
  const A = mixinsOf(p.old), B = mixinsOf(p.new);
  const mod = path.basename(p.new).replace(/[-_]?\d.*$/, '');
  let used = false;
  for (const [name, a] of A) {
    const b = B.get(name);
    if (!b) continue;                       // the mixin itself was removed or renamed; no alignment
    mixinsCompared++;
    // A selector is only interesting if it named a real member of a target class before and does
    // not now. Anything else is a string that happens to look like an identifier.
    for (const t of a.targets) {
      const was = oldIdx.get(t), now = newIdx.get(t);
      if (!was || !now) continue;
      const gone = [...a.selectors].filter((s) => was.has(s) && !now.has(s) && !b.selectors.has(s));
      if (!gone.length) continue;
      // The replacement must be a selector the new version uses that really exists on the target.
      const arrived = [...b.selectors].filter((s) => now.has(s) && !a.selectors.has(s));
      if (!arrived.length) continue;
      // A clean 1:1 within one mixin and one target class is the only unambiguous case. Voting for
      // every removed hook whenever a single new one arrived produced setScreen -> exitWorldAndClose
      // and getMainCamera -> render: several things changed at once and the pairing was arbitrary.
      // Where it is not 1:1, only a decisively closest name counts, and ties are dropped.
      const cands = [];
      if (gone.length === 1 && arrived.length === 1) cands.push([gone[0], arrived[0]]);
      else for (const g of gone) {
        const ranked = arrived.map((x) => ({ x, run: commonRun(g, x) })).sort((u, v) => v.run - u.run);
        if (ranked[0].run >= 6 && (ranked.length === 1 || ranked[0].run > ranked[1].run + 1)) cands.push([g, ranked[0].x]);
      }
      for (const [g, a2] of cands) {
        // Never contradict a member rename already derived from the game jars themselves; that is
        // stronger evidence than one mod's mixin diff.
        const known = knownRenames.get(`${t}\t${g}`);
        if (known && known !== a2) continue;
        vote(t, g, a2, mod); used = true;
      }
    }
  }
  if (used) modsUsed++;
}
function vote(owner, from, to, mod) {
  const k = `${owner}\t${from}\t${to}`;
  if (!votes.has(k)) votes.set(k, new Set());
  votes.get(k).add(mod);
}

const corroborated = [], single = [], ambiguous = [];
const byFrom = new Map();
for (const [k, mods] of votes) {
  const [owner, from, to] = k.split('\t');
  const fk = `${owner}\t${from}`;
  if (!byFrom.has(fk)) byFrom.set(fk, []);
  byFrom.get(fk).push({ owner, from, to, mods });
}
for (const [, cands] of byFrom) {
  cands.sort((a, b) => b.mods.size - a.mods.size);
  // Mods disagreeing about the replacement means it was a mod-specific decision, not a fact.
  if (cands.length > 1 && cands[0].mods.size === cands[1].mods.size) { ambiguous.push(cands); continue; }
  const c = cands[0];
  const rec = { owner: c.owner, from: c.from, to: c.to, mods: [...c.mods], source: 'mixin-groundtruth' };
  (c.mods.size >= MIN ? corroborated : single).push(rec);
}

console.log(`  mods compared        : ${modsUsed}`);
console.log(`  mixin classes aligned: ${mixinsCompared}`);
console.log(`  CORROBORATED (>=${MIN})  : ${corroborated.length}   — same substitution in several unrelated mods`);
console.log(`  single-mod answers   : ${single.length}   — that author's decision, not necessarily a general fact`);
console.log(`  contested            : ${ambiguous.length}`);
for (const c of corroborated.slice(0, 20)) console.log(`    ✓ ${c.owner.split('/').pop()}.${c.from} → ${c.to}   [${c.mods.length} mods: ${c.mods.slice(0, 3).join(', ')}]`);
for (const c of single.slice(0, 10)) console.log(`    · ${c.owner.split('/').pop()}.${c.from} → ${c.to}   (${c.mods[0]})`);

const OUT = args.out || path.join(HERE, `mixin-points.${args.from || '26.1'}-${args.to || '26.2'}.json`);
fs.writeFileSync(OUT, JSON.stringify({ schema: 1, from: args.from || '26.1', to: args.to || '26.2', corroborated, single }, null, 1) + '\n');
console.log(`\n  wrote ${path.basename(OUT)}`);
