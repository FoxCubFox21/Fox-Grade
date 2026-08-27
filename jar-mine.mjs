#!/usr/bin/env node
// Mine version-ladder rules from pairs of COMPILED jars — the same mod, built for two versions by
// the people who maintain it.
//
// This exists because the mapping join runs out. Fabric's intermediary is what makes cross-version
// renames provable, and from 26.x on Minecraft is no longer obfuscated, so its intermediary file is
// an empty header. There is nothing left to join on. What remains is evidence: if a mod referenced
// ResourceLocation before and Identifier after, and it does the same thing in five unrelated mods,
// that is a rename.
//
// Two rules keep this from repeating the earlier garbage-rules episode, where "one import out, one
// import in" produced World -> DataComponents:
//   1. a candidate must be corroborated by N independent mods (default 3), and
//   2. the new name must exist in the real target jars.
// A single mod's evidence is never enough on its own.
//
//   node jar-mine.mjs --pairs pairs.json --to 26.2 --classpath "<target jars>"
//   pairs.json: [{"old":"a-1.21.1.jar","new":"a-26.2.jar"}, …]
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
const MIN_MODS = +(args.min || 3);
const pairs = JSON.parse(fs.readFileSync(args.pairs || 'pairs.json', 'utf8'));

const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
const real = new Set();
for (const j of cp.split(':')) {
  if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
  const r = spawnSync('unzip', ['-Z1', j, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status !== 0 || !r.stdout) continue;
  for (const l of r.stdout.split('\n')) { const p = l.trim(); if (p.endsWith('.class')) real.add(p.slice(0, -6).replace(/\//g, '.')); }
}
if (!real.size) { console.error('need --classpath: a candidate is only worth keeping if its target really exists'); process.exit(2); }

// Intermediary tables, so the old side can be read in the same language as the new side.
const inter = new Map();   // version -> Map(class_1234 -> readable)
for (const f of fs.readdirSync(HERE).filter((x) => /^intermediary\.[\d.]+\.json$/.test(x))) {
  const v = f.match(/^intermediary\.([\d.]+)\.json$/)[1];
  const blk = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'))[`scheme:intermediary@${v}->mojmap`];
  inter.set(v, new Map((blk?.renames || []).map((r) => [r.fromFqcn, r.toFqcn])));
}

function mcTypes(jarPath, interMap) {
  const out = new Set();
  const scan = (buf) => {
    for (const e of readZip(buf)) {
      if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
      if (!e.name.endsWith('.class')) continue;
      for (const t of referencedTypes(new ClassFile(inflateEntry(e)))) {
        if (!t.startsWith('net/minecraft/')) continue;
        const d = t.replace(/\//g, '.');
        out.add(interMap?.get(d) ?? d);
      }
    }
  };
  scan(fs.readFileSync(jarPath));
  return out;
}

// old simple name -> Map(newFqcn -> Set(mods that show it))
const votes = new Map();
let usable = 0;
for (const p of pairs) {
  if (!fs.existsSync(p.old) || !fs.existsSync(p.new)) { console.error(`  ! missing jar for ${path.basename(p.old || '?')}`); continue; }
  const interMap = inter.get(p.from) || null;
  const oldT = mcTypes(p.old, interMap), newT = mcTypes(p.new, null);
  const stillIntermediary = [...oldT].filter((t) => /\.class_\d+/.test(t)).length;
  if (stillIntermediary > oldT.size * 0.5) { console.error(`  ! ${path.basename(p.old)}: no intermediary table for ${p.from}, skipping`); continue; }
  usable++;
  // Only names that DISAPPEARED can have been renamed, and only names that APPEARED can be the
  // destination. Anything present on both sides is untouched and irrelevant.
  const gone = [...oldT].filter((t) => !newT.has(t));
  const arrived = [...newT].filter((t) => !oldT.has(t));
  const bySimple = new Map();
  for (const a of arrived) { const s = a.split('.').pop(); if (!bySimple.has(s)) bySimple.set(s, []); bySimple.get(s).push(a); }
  const mod = path.basename(p.new);
  for (const g of gone) {
    const s = g.split('.').pop();
    // A same-simple-name arrival is a package move — the strongest signal available here.
    for (const cand of bySimple.get(s) || []) {
      if (!votes.has(g)) votes.set(g, new Map());
      if (!votes.get(g).has(cand)) votes.get(g).set(cand, new Set());
      votes.get(g).get(cand).add(mod);
    }
  }
  // A true rename changes the simple name too, so package moves alone cannot find it. Where exactly
  // one name left a package and exactly one arrived in the same package, they are a candidate — the
  // corroboration threshold is what stops that heuristic from inventing rules.
  const byPkg = (t) => t.slice(0, t.lastIndexOf('.'));
  const gonePkg = new Map(), arrPkg = new Map();
  const push = (m, k, v) => { if (!m.has(k)) m.set(k, []); m.get(k).push(v); };
  for (const g of gone) push(gonePkg, byPkg(g), g);
  for (const a of arrived) push(arrPkg, byPkg(a), a);
  for (const [pkg, gs] of gonePkg) {
    const as = arrPkg.get(pkg);
    if (!as || gs.length !== 1 || as.length !== 1) continue;
    if (gs[0].split('.').pop() === as[0].split('.').pop()) continue;   // already counted as a move
    if (!votes.has(gs[0])) votes.set(gs[0], new Map());
    if (!votes.get(gs[0]).has(as[0])) votes.get(gs[0]).set(as[0], new Set());
    votes.get(gs[0]).get(as[0]).add(mod);
  }
}

const kept = [], weak = [], ambiguous = [];
for (const [from, cands] of votes) {
  const ranked = [...cands].sort((a, b) => b[1].size - a[1].size);
  const [to, mods] = ranked[0];
  if (ranked.length > 1 && ranked[1][1].size === mods.size) { ambiguous.push([from, ranked.map((r) => r[0])]); continue; }
  if (!real.has(to)) continue;                       // destination must exist in the target
  // The simple name decides how much evidence is needed, and the first run on real jar pairs showed
  // exactly why. Every single-source MOVE it held back was correct (GameRules -> gamerules.GameRules,
  // FogRenderer -> fog.FogRenderer); every single-source RENAME was invented by the one-in-one-out
  // package heuristic (Tickable -> TextureManager, ConfirmLinkScreen -> GenericMessageScreen). A
  // preserved simple name plus a verified destination is evidence; a changed one is a guess.
  const isMove = from.split('.').pop() === to.split('.').pop();
  if (!isMove && mods.size < MIN_MODS) { weak.push([from, to, mods.size]); continue; }
  kept.push({ fromFqcn: from, toFqcn: to, fromSimple: from.split('.').pop(), toSimple: to.split('.').pop(),
    verified: true, kind: isMove ? 'move' : 'rename', ...(isMove ? { chainable: true } : {}),
    source: 'jar-groundtruth',
    evidence: `${mods.size} mod${mods.size > 1 ? 's' : ''}: ${[...mods].slice(0, 3).join(', ')}; destination verified` });
}

console.log(`  usable jar pairs   : ${usable} of ${pairs.length}`);
console.log(`  candidates         : ${votes.size}`);
console.log(`  KEPT               : ${kept.length}  (${kept.filter((k) => k.kind === 'move').length} moves, ${kept.filter((k) => k.kind === 'rename').length} renames)`);
console.log(`  renames held back  : ${weak.length}  (a rename needs ${MIN_MODS} independent mods)`);
console.log(`  ambiguous          : ${ambiguous.length}`);
for (const k of kept.slice(0, 25)) console.log(`    ✓ ${k.fromFqcn}\n        → ${k.toFqcn}   (${k.evidence})`);
if (weak.length) { console.log(`\n  held back — real but only seen once or twice:`); for (const [f, t, n] of weak.slice(0, 10)) console.log(`    · ${f} → ${t}  (${n})`); }

const BLOCK = `${args.from || 'mined'}->${TO}`;
const OUT = args.out || `ladder.${TO}.json`;
fs.writeFileSync(OUT, JSON.stringify({ [BLOCK]: { renames: kept, advisories: [], deleted: [] } }, null, 1) + '\n');
console.log(`\n  wrote ${OUT}`);

if (!process.argv.includes('--apply')) { console.log(`  --apply folds these into rules.json's "${BLOCK}" block`); process.exit(0); }
const RULES = path.join(HERE, 'rules.json');
const data = JSON.parse(fs.readFileSync(RULES, 'utf8'));
if (!data[BLOCK]) data[BLOCK] = { renames: [], advisories: [], deleted: [] };
const have = new Map((data[BLOCK].renames || []).map((r) => [r.fromFqcn, r.toFqcn]));
let added = 0, clash = 0;
for (const k of kept) {
  const prev = have.get(k.fromFqcn);
  if (prev === k.toFqcn) continue;
  // An existing rule was established some other way; jar evidence does not get to overrule it.
  if (prev) { console.log(`    ! ${k.fromFqcn} already maps to ${prev}, keeping that`); clash++; continue; }
  data[BLOCK].renames.push(k); have.set(k.fromFqcn, k.toFqcn); added++;
}
const out = JSON.stringify(data, null, 2) + '\n';
JSON.parse(out);
fs.copyFileSync(RULES, RULES + '.bak');
fs.writeFileSync(RULES + '.tmp', out);
fs.renameSync(RULES + '.tmp', RULES);
console.log(`  added ${added} rule(s) to "${BLOCK}"${clash ? `, ${clash} left alone` : ''}  (backup: rules.json.bak)`);
