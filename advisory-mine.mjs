#!/usr/bin/env node
// Learn what a redesigned API was actually replaced WITH, from a mod ported by its own author.
//
// Some changes are not renames. In 26.2 GuiGraphics did not move — it was dissolved into
// GuiGraphicsExtractor, Hud and RenderPipelines, and InteractionResult stopped being an enum. No
// rename table can express one class becoming three, which is why the remapper correctly declines
// them and reports them as Tier 2.
//
// But the answer is not unknowable, only unrenameable: the author's own port shows what they reached
// for instead. That is recorded as an ADVISORY — a hint fed to the AI, never applied automatically.
// The distinction matters, because a wrong advisory costs a bad suggestion while a wrong rename
// silently writes a jar that loads and then dies.
//
// Evidence is gathered PER CLASS FILE, not per jar. AppleSkin's HUDOverlayHandler does the same job
// in both versions, so what it stopped using and what it started using are related. Diffing whole
// jars instead drags in every unrelated change the mod made between releases.
//
//   node advisory-mine.mjs --pairs pairs.json --to 26.2 --classpath "<target jars>"
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile, referencedTypes } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const TO = args.to || '26.2';
const APPLY = process.argv.includes('--apply');
const pairs = JSON.parse(fs.readFileSync(args.pairs || 'pairs.json', 'utf8'));

const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
const real = new Set();
for (const j of cp.split(':')) {
  if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
  const r = spawnSync('unzip', ['-Z1', j, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status !== 0 || !r.stdout) continue;
  for (const l of r.stdout.split('\n')) { const p = l.trim(); if (p.endsWith('.class')) real.add(p.slice(0, -6).replace(/\//g, '.')); }
}
if (!real.size) { console.error('need --classpath'); process.exit(2); }

const data = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
// Anything already renameable is not an advisory — say it once, in the strongest form available.
const renameable = new Set();
for (const [k, v] of Object.entries(data)) {
  if (k.startsWith('_')) continue;
  for (const r of (v.renames || [])) renameable.add(r.fromFqcn);
}
const inter = new Map();
for (const f of fs.readdirSync(HERE).filter((x) => /^intermediary\.[\d.]+\.json$/.test(x))) {
  const v = f.match(/^intermediary\.([\d.]+)\.json$/)[1];
  const blk = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'))[`scheme:intermediary@${v}->mojmap`];
  inter.set(v, new Map((blk?.renames || []).map((r) => [r.fromFqcn, r.toFqcn])));
}

// name -> Set(minecraft types it references), for every class file in a jar
function perFile(jarPath, interMap) {
  const out = new Map();
  const scan = (buf) => {
    for (const e of readZip(buf)) {
      if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
      if (!e.name.endsWith('.class')) continue;
      const set = new Set();
      for (const t of referencedTypes(new ClassFile(inflateEntry(e)))) {
        if (!t.startsWith('net/minecraft/')) continue;
        const d = t.replace(/\//g, '.');
        set.add(interMap?.get(d) ?? d);
      }
      if (set.size) out.set(e.name, set);
    }
  };
  scan(fs.readFileSync(jarPath));
  return out;
}

// gone class -> Map(replacement -> Set("mod:file"))
const votes = new Map();
let files = 0;
for (const p of pairs) {
  if (!fs.existsSync(p.old) || !fs.existsSync(p.new)) continue;
  const oldF = perFile(p.old, inter.get(p.from) || null), newF = perFile(p.new, null);
  const mod = path.basename(p.new).replace(/[-_]?\d.*$/, '');
  for (const [name, oldTypes] of oldF) {
    const newTypes = newF.get(name);
    if (!newTypes) continue;                       // the class was renamed or removed; no alignment
    files++;
    const gone = [...oldTypes].filter((t) => !newTypes.has(t) && !/class_\d+/.test(t));
    const arrived = [...newTypes].filter((t) => !oldTypes.has(t) && real.has(t));
    if (!gone.length || !arrived.length) continue;
    // A file that changed wholesale tells us nothing specific — the co-occurrence is coincidence.
    if (gone.length > 6 || arrived.length > 8) continue;
    for (const g of gone) {
      if (renameable.has(g)) continue;             // already handled as a rename
      // The decisive gate: if the old class is still there in the target, nothing replaced it. This
      // file simply stopped using it. Without this the miner emitted "Level -> Consumable", because
      // one file dropped Level for its own reasons in the same release that added consumables.
      if (real.has(g)) continue;
      if (!votes.has(g)) votes.set(g, new Map());
      for (const a of arrived) {
        if (!votes.get(g).has(a)) votes.get(g).set(a, new Set());
        votes.get(g).get(a).add(`${mod}:${path.basename(name)}`);
      }
    }
  }
}

const advisories = [];
for (const [gone, cands] of votes) {
  const all = [...cands].sort((a, b) => b[1].size - a[1].size);
  // Within one class file every dropped name is paired with every added one, so a single file's
  // votes are mostly coincidence. Where two files agree, keep only what they agree on.
  const corroborated = all.filter(([, w]) => w.size >= 2);
  const ranked = (corroborated.length ? corroborated : all).slice(0, 4);
  const witnesses = new Set();
  for (const [, w] of ranked) for (const x of w) witnesses.add(x);
  advisories.push({
    when: gone,
    note: `${gone.split('.').pop()} has no direct replacement in ${TO}. Ports that dropped it reached for: ${ranked.map((r) => r[0]).join(', ')}. Check which of these fits — this is a redesign, not a rename.`,
    candidates: ranked.map(([c, w]) => ({ fqcn: c, seenIn: [...w].slice(0, 3) })),
    source: 'jar-advisory', evidence: `${witnesses.size} aligned class file(s) across ${new Set([...witnesses].map((w) => w.split(':')[0])).size} mod(s)`,
  });
}
advisories.sort((a, b) => b.candidates.length - a.candidates.length);

console.log(`  aligned class files : ${files}`);
console.log(`  ADVISORIES          : ${advisories.length}`);
for (const a of advisories.slice(0, 12)) {
  console.log(`    ${a.when}`);
  for (const c of a.candidates) console.log(`        → ${c.fqcn}`);
}
if (!advisories.length) { console.log('\n  nothing learned'); process.exit(0); }
if (!APPLY) { console.log(`\n  dry run — --apply adds these to the "${TO}" advisories`); process.exit(0); }

if (!data[TO]) data[TO] = { renames: [], advisories: [], deleted: [] };
if (!data[TO].advisories) data[TO].advisories = [];
const have = new Set(data[TO].advisories.map((a) => (typeof a === 'string' ? a : a.when)));
let added = 0;
for (const a of advisories) if (!have.has(a.when)) { data[TO].advisories.push(a); added++; }
const out = JSON.stringify(data, null, 2) + '\n';
JSON.parse(out);
fs.copyFileSync(path.join(HERE, 'rules.json'), path.join(HERE, 'rules.json.bak'));
fs.writeFileSync(path.join(HERE, 'rules.json.tmp'), out);
fs.renameSync(path.join(HERE, 'rules.json.tmp'), path.join(HERE, 'rules.json'));
console.log(`\n  added ${added} advisory(ies) to "${TO}"  (backup: rules.json.bak)`);
