#!/usr/bin/env node
// Foxgrade auto-miner — discovery + laddering at scale.
// For each repo: list branches, keep the version-like ones, sort them into a ladder, and mine every
// adjacent pair. All free (public tarballs), all unanimity-checked by mine-wild.
//
// Usage: node mine-auto.mjs [--limit N] [--dry] [--repos "a/b,c/d"]
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--dry') args.dry = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}

const REPOS = (args.repos ? args.repos.split(',') : [
  'AppliedEnergistics/Applied-Energistics-2',
  'BluSunrize/ImmersiveEngineering',
  'mezz/JustEnoughItems',
  'refinedmods/refinedstorage',
  'VazkiiMods/Botania',
  'blay09/Waystones',
  'SleepyTrousers/EnderIO',
  'shedaniel/RoughlyEnoughItems',
  'TerraformersMC/ModMenu',
  'CoFH/ThermalExpansion',
  'Darkhax-Minecraft/Bookshelf',
  'MehVahdJukaar/Supplementaries',
  'TeamTwilight/twilightforest',
  'mekanism/Mekanism',
  'SlimeKnights/TinkersConstruct',
  'CoFH/CoFHCore',
]).map((s) => s.trim()).filter(Boolean);

const gh = (url) => {
  const r = spawnSync('curl', ['-s', '-H', 'Accept: application/vnd.github+json', url], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  try { return JSON.parse(r.stdout); } catch { return null; }
};

// Pull a comparable MC version out of a branch name like "1.20.1", "mc1.19/dev", "1.16.x", "MC1.12"
function branchVersion(name) {
  const m = name.match(/(?:^|[^0-9])(?:mc)?(1)\.(\d{1,2})(?:\.(\d{1,2}))?/i);
  if (!m) return null;
  const minor = +m[2];
  if (minor < 6 || minor > 25) return null;         // outside the plausible MC range
  return [1, minor, m[3] ? +m[3] : 0];
}
const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
const key = (v) => v.join('.');

const plan = [];
for (const repo of REPOS) {
  const branches = gh(`https://api.github.com/repos/${repo}/branches?per_page=100`);
  if (!Array.isArray(branches)) { console.log(`  ${repo}: unreachable`); continue; }
  // Prefer one branch per MC version; favour names that look like a mainline (dev/release/main/x).
  const best = new Map();
  for (const b of branches) {
    const v = branchVersion(b.name);
    if (!v) continue;
    const k = key(v);
    const score = /dev|release|main|master|\.x$/i.test(b.name) ? 2 : 1;
    const cur = best.get(k);
    if (!cur || score > cur.score) best.set(k, { name: b.name, v, score });
  }
  const ladder = [...best.values()].sort((a, b) => cmp(a.v, b.v));
  if (ladder.length < 2) { console.log(`  ${repo}: ${ladder.length} version branch(es) — skipped`); continue; }
  console.log(`  ${repo}: ${ladder.length} versions -> ${ladder.map((x) => x.name).join(' , ')}`);
  for (let i = 0; i + 1 < ladder.length; i++) plan.push({ repo, old: ladder[i].name, new: ladder[i + 1].name });
}

const limit = parseInt(args.limit || '40', 10);
console.log(`\n=== PLAN: ${plan.length} adjacent pairs across ${REPOS.length} repos (running ${Math.min(limit, plan.length)}) ===\n`);
if (args.dry) process.exit(0);

let done = 0, wrote = 0;
for (const p of plan.slice(0, limit)) {
  console.log(`\n########## ${p.repo}  ${p.old} -> ${p.new} ##########`);
  const r = spawnSync('node', ['mine-wild.mjs', '--repo', p.repo, '--old', p.old, '--new', p.new,
    '--write', '--chainable', '--as', `${p.repo.split('/')[1]}:${p.old}->${p.new}`], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  const out = (r.stdout || '') + (r.stderr || '');
  for (const line of out.split('\n')) if (/paired:|TRUE RENAMES|CONTRADICTORY|WROTE|Could not/.test(line)) console.log('  ' + line.trim());
  const m = out.match(/WROTE (\d+)/);
  if (m) wrote += +m[1];
  done++;
}

console.log(`\n=== ${done} pairs mined, ${wrote} new rules written ===`);
const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
let tot = 0, blocks = 0;
for (const [k, v] of Object.entries(d)) { if (k.startsWith('_')) continue; tot += (v.renames || []).length; blocks++; }
console.log(`rules.json now: ${blocks} version blocks, ${tot} total rules`);
