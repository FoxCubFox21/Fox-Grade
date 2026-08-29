#!/usr/bin/env node
// Package everything this install learned — and everything it could not answer — for the shared set.
//
// The original export handled class renames only. That was the smallest and least valuable category:
// Mojang publishes the mappings those derive from, so every install can compute them alone and they
// are already as complete as they will get.
//
// The categories worth pooling are the ones that can only be learned by OBSERVING PORTS:
//   member renames      derived by diffing game jars
//   relocations         a member moved to a delegate reachable by one field
//   injection points    which hook a maintainer chose when theirs was deleted
// A small number of Minecraft methods are hooked by a great many mods, so one install that happens
// to hold sodium and iris learns facts that unblock mods it does not have. That is leverage no
// mapping table has.
//
// OPEN QUESTIONS ship too. "18 mods searched, no replacement for Items.PINK_WOOL" is a request
// someone else's collection may answer, and pooling the questions is what lets the set find its own
// gaps instead of waiting for each person to hit them.
//
//   node contrib-pack.mjs --out my-contribution.json
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const OUT = args.out || 'my-contribution.json';

// Provenance decides what may be shared. A fact is contributable only if it can be RE-DERIVED by
// whoever receives it — from published mappings, from the game jars, or from released mod builds.
// Anything resting on inference stays local, because the recipient has no way to check it.
const SHAREABLE = {
  'official-mappings': 'derived from published Mojang/Fabric/MCPConfig data',
  'jar-diff': 'derived by diffing two Minecraft jars',
  'jar-groundtruth': 'observed across released mod builds',
  'promoted-groundtruth': 'observed in ports humans shipped',
  'mixin-groundtruth': "read from maintainers' own ported builds",
  'hand-verified': 'a person checked this against javap and recorded why',
  'direct-jar-resolution': 'resolved against the real target jars',
  'repair-diff': 'a failing compile became a passing compile after this change',
};
const REFUSED = ['wild', 'structural-matcher', 'inference', 'community'];

const out = {
  schema: 2,
  generated: 'local-pack',
  facts: { members: [], relocations: [], hooks: [] },
  questions: [],
  counts: {},
};
let refused = 0;

// ── member renames and relocations, from the per-version tables ──────────────────────────────
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  const m = f.match(/^members\.([\d.]+)-([\d.]+)\.json$/);
  let t; try { t = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')); } catch { continue; }
  for (const r of t.renames || [])
    out.facts.members.push({ from: m[1], to: m[2], owner: r.owner, kind: r.kind, name: r.from, becomes: r.to, desc: r.desc, proof: 'jar-diff' });
  for (const r of t.relocations || [])
    out.facts.relocations.push({ from: m[1], to: m[2], owner: r.owner, name: r.name, desc: r.desc, host: r.host, via: r.via, proof: 'jar-diff' });
  // Guesses are deliberately excluded: they are this install's best effort, not something the
  // recipient can re-derive, and a pool of guesses is indistinguishable from a pool of noise.
  refused += (t.guesses || []).length;
}

// ── injection points, the category with the most leverage ────────────────────────────────────
for (const f of fs.readdirSync(HERE).filter((x) => /^mixin-points\.[\d.]+-[\d.]+\.json$/.test(x))) {
  let t; try { t = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')); } catch { continue; }
  for (const r of t.corroborated || [])
    out.facts.hooks.push({ from: t.from, to: t.to, owner: r.owner, name: r.from, becomes: r.to, mods: r.mods, proof: 'mixin-groundtruth' });
  // Single-mod choices travel too, but flagged: one author's decision is evidence, not yet a fact.
  // Whoever merges can require independent agreement before promoting them.
  for (const r of t.single || [])
    out.facts.hooks.push({ from: t.from, to: t.to, owner: r.owner, name: r.from, becomes: r.to, mods: r.mods, proof: 'mixin-groundtruth', corroborated: false });
}

// ── hand-verified overrides, carried with their reasoning so they can be argued with ─────────
try {
  const ov = JSON.parse(fs.readFileSync(path.join(HERE, 'members.overrides.json'), 'utf8'));
  for (const r of ov.renames || [])
    out.facts.members.push({ from: r.from, to: r.to, owner: r.owner, kind: r.kind, name: r.name, becomes: r.becomes, desc: r.desc, proof: 'hand-verified', evidence: r.evidence, confidence: r.confidence });
} catch { /* none is fine */ }

// ── open questions: what this install needed and nobody could answer ──────────────────────────
for (const f of fs.readdirSync(args.demands || HERE)) {
  if (!/^(demand|dm)[.\-].*\.json$/.test(f)) continue;
  let d; try { d = JSON.parse(fs.readFileSync(path.join(args.demands || HERE, f), 'utf8')); } catch { continue; }
  for (const u of d.unanswered || [])
    out.questions.push({ kind: u.kind, owner: u.owner, name: u.from, desc: u.desc, searched: d.corpus });
  for (const c of d.classGaps || []) out.questions.push({ kind: 'class', owner: c, searched: d.corpus });
}
// One question per thing, however many jars raised it.
const qseen = new Set();
out.questions = out.questions.filter((q) => { const k = `${q.kind}\t${q.owner}\t${q.name || ''}\t${q.desc || ''}`; if (qseen.has(k)) return false; qseen.add(k); return true; });

out.counts = {
  members: out.facts.members.length,
  relocations: out.facts.relocations.length,
  hooks: out.facts.hooks.length,
  hooksCorroborated: out.facts.hooks.filter((h) => h.corroborated !== false).length,
  questions: out.questions.length,
};
fs.writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n');

console.log(`  ${OUT}`);
console.log(`    member renames   : ${out.counts.members}`);
console.log(`    relocations      : ${out.counts.relocations}`);
console.log(`    injection points : ${out.counts.hooks}  (${out.counts.hooksCorroborated} corroborated)`);
console.log(`    open questions   : ${out.counts.questions}   — things this install needed and could not answer`);
console.log(`    refused          : ${refused}  (guesses; not re-derivable by anyone else)`);
console.log(`\n  Contains only name pairs and the versions they apply to. No mod source, no file paths,`);
console.log(`  no list of what you have installed beyond the mod names attached as evidence.`);
console.log(`  Every fact is re-derivable by the recipient — that is the condition for sharing it.`);
