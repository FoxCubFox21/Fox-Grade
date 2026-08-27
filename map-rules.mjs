#!/usr/bin/env node
// AUTHORITATIVE cross-version renames from official mapping data — no inference at all.
//
// Mojmap alone can't do this: it maps readable -> obfuscated for ONE version, and obfuscation is
// regenerated every release, so you cannot join two versions on the obfuscated name.
// Fabric's INTERMEDIARY names are stable across versions by design, so they are the join key:
//
//   verA:  readable_A -> obf_A   (mojmap)      obf_A -> class_2338  (intermediary)
//   verB:  readable_B -> obf_B   (mojmap)      obf_B -> class_2338  (intermediary)
//                       => class_2338 links readable_A to readable_B. Authoritative.
//
// Usage: node map-rules.mjs --from 1.16.5 --to 1.20.1 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const A = args.from, B = args.to;
if (!A || !B) { console.error('Usage: node map-rules.mjs --from <ver> --to <ver> [--write]'); process.exit(2); }

const sh = (cmd) => spawnSync('bash', ['-c', cmd], { encoding: 'utf8', maxBuffer: 512e6 });
const manifest = JSON.parse(sh('curl -sL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json').stdout);

// readable -> obf, from Mojang's ProGuard file ("com.foo.Bar -> xyz:")
function mojmap(ver) {
  const e = manifest.versions.find((v) => v.id === ver);
  if (!e) return null;
  const meta = JSON.parse(sh(`curl -sL "${e.url}"`).stdout || '{}');
  const url = meta.downloads?.client_mappings?.url;
  if (!url) return null;
  const txt = sh(`curl -sL "${url}"`).stdout || '';
  const obfToReadable = new Map();
  for (const line of txt.split('\n')) {
    const m = line.match(/^([\w.$]+) -> ([\w.$]+):$/);
    if (m && !m[1].includes('$')) obfToReadable.set(m[2], m[1]);
  }
  return obfToReadable;
}

// obf -> intermediary (class_NNNN), from Fabric's tiny v2 ("c\t<obf>\tnet/minecraft/class_NNNN")
function intermediary(ver) {
  const jar = `/tmp/int-${ver}.jar`;
  const r = sh(`curl -sfL "https://maven.fabricmc.net/net/fabricmc/intermediary/${ver}/intermediary-${ver}-v2.jar" -o "${jar}" && unzip -p "${jar}" mappings/mappings.tiny`);
  if (r.status !== 0 || !r.stdout) return null;
  const obfToInt = new Map();
  for (const line of r.stdout.split('\n')) {
    if (!line.startsWith('c\t')) continue;
    const p = line.split('\t');
    if (p.length >= 3) obfToInt.set(p[1].replace(/\//g, '.'), p[2].replace(/\//g, '.'));
  }
  return obfToInt;
}

console.log(`Fetching authoritative mappings for ${A} and ${B} ...`);
const [mA, mB, iA, iB] = [mojmap(A), mojmap(B), intermediary(A), intermediary(B)];
for (const [n, v] of [[`mojmap ${A}`, mA], [`mojmap ${B}`, mB], [`intermediary ${A}`, iA], [`intermediary ${B}`, iB]]) {
  if (!v) { console.error(`  MISSING: ${n} — cannot build an authoritative table for this pair.`); process.exit(1); }
  console.log(`  ${n}: ${v.size} entries`);
}

// intermediary -> readable, per version
const byIntA = new Map(), byIntB = new Map();
for (const [obf, readable] of mA) { const i = iA.get(obf); if (i) byIntA.set(i, readable); }
for (const [obf, readable] of mB) { const i = iB.get(obf); if (i) byIntB.set(i, readable); }
console.log(`  joined: ${byIntA.size} classes in ${A}, ${byIntB.size} in ${B}`);

const moves = [], renames = [], same = [];
for (const [int, a] of byIntA) {
  const b = byIntB.get(int);
  if (!b) continue;                                  // class removed in B
  if (a === b) { same.push(a); continue; }
  (a.split('.').pop() === b.split('.').pop() ? moves : renames).push({ from: a, to: b, int });
}
console.log(`\n=== AUTHORITATIVE ${A} -> ${B} ===`);
console.log(`  unchanged      : ${same.length}`);
console.log(`  package MOVES  : ${moves.length}`);
console.log(`  true RENAMES   : ${renames.length}   <- exactly what inference could never get right`);
console.log('\n  --- sample renames ---');
for (const r of renames.slice(0, 15)) console.log(`   ${r.from}\n       -> ${r.to}`);

if (args.write) {
  const rf = 'rules.json';
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = `${A}->${B}`;
  d[k] = { renames: [], advisories: [], deleted: [] };
  for (const r of [...moves, ...renames]) {
    d[k].renames.push({
      fromFqcn: r.from, toFqcn: r.to,
      fromSimple: r.from.split('.').pop(), toSimple: r.to.split('.').pop(),
      verified: true, chainable: true,
      kind: r.from.split('.').pop() === r.to.split('.').pop() ? 'move' : 'rename',
      source: 'official-mappings', provenance: `mojmap+intermediary join on ${r.int}`,
    });
  }
  fs.writeFileSync(rf, JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${d[k].renames.length} AUTHORITATIVE rule(s) under "${k}".`);
}
