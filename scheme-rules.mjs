#!/usr/bin/env node
// SCHEME TRANSLATION — the missing layer.
//
// Naming schemes differ WITHIN a single version: in 1.16.5 a Yarn/MCP-named mod says
// `net.minecraft.util.math.BlockPos` while mojmap calls the identical class `net.minecraft.core.BlockPos`.
// That mismatch is why the authoritative mojmap ladder scored 0% on a Forge/Yarn-named mod.
//
// Fix, again via the stable intermediary id — all authoritative, no inference:
//   yarn_name  <- class_2338 -> obf -> mojmap_name        (same version, both directions available)
//
// Emitting yarn->mojmap for version V lets a Yarn-named mod be normalised into mojmap, after which the
// cross-version authoritative ladder (map-rules.mjs) applies to it.
//
// Usage: node scheme-rules.mjs --ver 1.16.5 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const ver = args.ver;
if (!ver) { console.error('Usage: node scheme-rules.mjs --ver <mcversion> [--write]'); process.exit(2); }
const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });

// 1. mojmap: obf -> readable
function mojmap(v) {
  const man = JSON.parse(sh('curl -sL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json').stdout);
  const e = man.versions.find((x) => x.id === v);
  if (!e) return null;
  const meta = JSON.parse(sh(`curl -sL "${e.url}"`).stdout || '{}');
  const url = meta.downloads?.client_mappings?.url;
  if (!url) return null;
  const m = new Map();
  for (const line of (sh(`curl -sL "${url}"`).stdout || '').split('\n')) {
    const g = line.match(/^([\w.$]+) -> ([\w.$]+):$/);
    if (g && !g[1].includes('$')) m.set(g[2], g[1]);
  }
  return m;
}
// 2. intermediary: obf -> class_NNNN
function intermediary(v) {
  const r = sh(`curl -sfL "https://maven.fabricmc.net/net/fabricmc/intermediary/${v}/intermediary-${v}-v2.jar" -o /tmp/int-${v}.jar && unzip -p /tmp/int-${v}.jar mappings/mappings.tiny`);
  if (r.status !== 0 || !r.stdout) return null;
  const m = new Map();
  for (const line of r.stdout.split('\n')) {
    if (!line.startsWith('c\t')) continue;
    const p = line.split('\t');
    if (p.length >= 3) m.set(p[1].replace(/\//g, '.'), p[2].replace(/\//g, '.'));
  }
  return m;
}
// 3. yarn: class_NNNN -> yarn readable
function yarn(v) {
  const list = JSON.parse(sh(`curl -sL "https://meta.fabricmc.net/v2/versions/yarn/${v}"`).stdout || '[]');
  if (!list.length) return null;
  const build = list[0].version;                       // newest build for this MC version
  const enc = encodeURIComponent(build);
  const r = sh(`curl -sfL "https://maven.fabricmc.net/net/fabricmc/yarn/${enc}/yarn-${enc}-v2.jar" -o /tmp/yarn-${v}.jar && unzip -p /tmp/yarn-${v}.jar mappings/mappings.tiny`);
  if (r.status !== 0 || !r.stdout) return null;
  const m = new Map();
  for (const line of r.stdout.split('\n')) {
    if (!line.startsWith('c\t')) continue;
    const p = line.split('\t');                        // c <intermediary> <yarn>
    if (p.length >= 3 && p[2]) m.set(p[1].replace(/\//g, '.'), p[2].replace(/\//g, '.'));
  }
  return m;
}

console.log(`Fetching mojmap / intermediary / yarn for ${ver} ...`);
const moj = mojmap(ver), inter = intermediary(ver), yrn = yarn(ver);
for (const [n, v] of [['mojmap', moj], ['intermediary', inter], ['yarn', yrn]]) {
  if (!v) { console.error(`  MISSING: ${n} for ${ver} — cannot build a scheme table.`); process.exit(1); }
  console.log(`  ${n}: ${v.size}`);
}

// join: yarn(intermediary) and mojmap(obf) meet at the intermediary id
const intToMoj = new Map();
for (const [obf, readable] of moj) { const i = inter.get(obf); if (i) intToMoj.set(i, readable); }

const same = [], diff = [];
for (const [int, y] of yrn) {
  const mj = intToMoj.get(int);
  if (!mj) continue;
  (y === mj ? same : diff).push({ from: y, to: mj, int });
}
console.log(`\n=== YARN -> MOJMAP for ${ver} ===`);
console.log(`  identical in both schemes : ${same.length}`);
console.log(`  DIFFERENT (need translating): ${diff.length}`);
console.log('\n  --- samples ---');
for (const r of diff.slice(0, 12)) console.log(`   ${r.from}\n       -> ${r.to}`);

if (args.write && diff.length) {
  const rf = 'rules.json';
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = `scheme:yarn@${ver}->mojmap`;
  d[k] = { renames: [], advisories: [], deleted: [] };
  for (const r of diff) {
    d[k].renames.push({
      fromFqcn: r.from, toFqcn: r.to,
      fromSimple: r.from.split('.').pop(), toSimple: r.to.split('.').pop(),
      verified: true, chainable: false, kind: 'scheme',
      source: 'official-mappings', provenance: `yarn⋈intermediary⋈mojmap on ${r.int} @ ${ver}`,
    });
  }
  fs.writeFileSync(rf, JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${diff.length} scheme rule(s) under "${k}".`);
} else if (diff.length) console.log('\n(dry run — pass --write)');
