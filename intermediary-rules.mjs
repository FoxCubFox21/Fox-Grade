#!/usr/bin/env node
// Build the intermediary -> mojmap table for one Minecraft version.
//
// Released Fabric mods are compiled against INTERMEDIARY names (class_1799, method_7909) — stable,
// meaningless numbers. Readable names never appear in the jar. Without this table a downloaded mod
// is unportable, because nothing in it matches anything in rules.json.
//
// The join is the same one the whole ruleset rests on:
//     intermediary :  obfuscated -> class_1799     (Fabric publishes this per version)
//     mojmap       :  readable   -> obfuscated     (Mojang publishes this per version)
//   joined on the obfuscated name  =>  class_1799 -> net.minecraft.world.item.ItemStack
//
// Built at the SOURCE version, not the target: from 26.x on, Minecraft is no longer obfuscated and
// its intermediary file is an empty header. Translate the jar into readable names at its own
// version, then let the normal version ladder carry it forward.
//
// Nothing derived from this ships in the repo — Mojang's mappings are theirs. Each install fetches
// from Mojang and Fabric directly and caches the join locally.
//
//   node intermediary-rules.mjs --version 1.21.1
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const VER = args.version;
if (!VER) { console.error('usage: node intermediary-rules.mjs --version 1.21.1'); process.exit(2); }
const OUT = args.out || path.join(HERE, `intermediary.${VER}.json`);
const CACHE = path.join(HERE, '.cache');
fs.mkdirSync(CACHE, { recursive: true });

function fetchTo(url, file, label) {
  if (fs.existsSync(file) && fs.statSync(file).size > 1000) return file;
  process.stderr.write(`  fetching ${label}…\n`);
  const r = spawnSync('curl', ['-sL', '--max-time', '120', '-H', 'User-Agent: FoxGrade', '-o', file, url]);
  if (r.status !== 0 || !fs.existsSync(file) || fs.statSync(file).size < 1000) throw new Error(`could not fetch ${label}`);
  return file;
}

// ── Fabric intermediary, tiny v1 ─────────────────────────────────────────────────────────────
//   CLASS  <obf> <intermediary>
//   FIELD  <ownerObf> <descObf> <obfName> <intermediary>
//   METHOD <ownerObf> <descObf> <obfName> <intermediary>
const interJar = fetchTo(`https://maven.fabricmc.net/net/fabricmc/intermediary/${VER}/intermediary-${VER}.jar`,
  path.join(CACHE, `intermediary-${VER}.jar`), `Fabric intermediary ${VER}`);
const tiny = spawnSync('unzip', ['-p', interJar, 'mappings/mappings.tiny'], { encoding: 'utf8', maxBuffer: 256e6 }).stdout || '';
const classInter = new Map();     // obf -> class_1799
const memberInter = new Map();    // owner \t obfName \t obfDesc -> method_7909
for (const line of tiny.split('\n')) {
  const p = line.split('\t');
  if (p[0] === 'CLASS' && p.length >= 3) classInter.set(p[1], p[2]);
  else if ((p[0] === 'METHOD' || p[0] === 'FIELD') && p.length >= 5) memberInter.set(`${p[1]}\t${p[3]}\t${p[2]}`, p[4]);
}
if (!classInter.size) {
  console.error(`  ${VER} publishes an EMPTY intermediary file — that version is not obfuscated, so no table is needed.`);
  process.exit(1);
}

// ── Mojang mappings, ProGuard txt:  "readable -> obf"  ───────────────────────────────────────
const manifest = JSON.parse(fs.readFileSync(fetchTo('https://launchermeta.mojang.com/mc/game/version_manifest_v2.json', path.join(CACHE, 'version_manifest.json'), 'version manifest'), 'utf8'));
const entry = manifest.versions.find((v) => v.id === VER);
if (!entry) { console.error(`no such Minecraft version: ${VER}`); process.exit(2); }
const meta = JSON.parse(fs.readFileSync(fetchTo(entry.url, path.join(CACHE, `meta-${VER}.json`), `${VER} metadata`), 'utf8'));
if (!meta.downloads?.client_mappings) { console.error(`Mojang published no mappings for ${VER}`); process.exit(1); }
const lines = fs.readFileSync(fetchTo(meta.downloads.client_mappings.url, path.join(CACHE, `mojmap-${VER}.txt`), `Mojang mappings ${VER}`), 'utf8').split('\n');

// Pass 1 — every class, so member descriptors can be obfuscated in pass 2.
const CLASS_LINE = /^([\w.$]+) -> ([\w.$]+):$/;
const classObf = new Map();       // readable dotted -> obf internal
for (const raw of lines) {
  if (!raw || raw[0] === ' ' || raw[0] === '\t' || raw[0] === '#') continue;
  const m = raw.match(CLASS_LINE);
  if (m) classObf.set(m[1], m[2].replace(/\./g, '/'));
}

// Intermediary keys members by their OBFUSCATED descriptor. Mojang gives readable Java types, so
// each one has to be pushed back through the class map — an obfuscator happily reuses the name `a`
// for several methods on one class, and only the descriptor tells them apart.
const PRIM = { void: 'V', boolean: 'Z', byte: 'B', char: 'C', short: 'S', int: 'I', long: 'J', float: 'F', double: 'D' };
function desc(type) {
  let arr = 0;
  while (type.endsWith('[]')) { arr++; type = type.slice(0, -2); }
  const base = PRIM[type] || `L${classObf.get(type) || type.replace(/\./g, '/')};`;
  return '['.repeat(arr) + base;
}

// Pass 2 — the join.
const MEMBER_LINE = /^(?:\d+:\d+:)?([\w.$[\]]+) ([\w$]+)(?:\(([^)]*)\))? -> ([\w$]+)$/;
const renames = [], members = {};
let curObf = null, classHits = 0, memberHits = 0, memberMiss = 0;
for (const raw of lines) {
  if (!raw || raw[0] === '#') continue;
  if (raw[0] !== ' ' && raw[0] !== '\t') {
    const m = raw.match(CLASS_LINE);
    if (!m) { curObf = null; continue; }
    curObf = m[2].replace(/\./g, '/');
    const inter = classInter.get(curObf);
    if (inter) { renames.push([inter.replace(/\//g, '.'), m[1]]); classHits++; }
    continue;
  }
  if (!curObf) continue;
  const mm = raw.trim().match(MEMBER_LINE);
  if (!mm) continue;
  const [, retType, readableName, params, obfName] = mm;
  const d = params === undefined
    ? desc(retType)
    : `(${(params ? params.split(',') : []).map((t) => desc(t.trim())).join('')})${desc(retType)}`;
  const inter = memberInter.get(`${curObf}\t${obfName}\t${d}`);
  if (inter) { if (!members[inter]) { members[inter] = readableName; memberHits++; } }
  else memberMiss++;
}

console.log(`  ${VER}: ${classHits} classes joined of ${classInter.size} intermediary`);
console.log(`         ${memberHits} members joined, ${memberMiss} unmatched`);
if (classHits < classInter.size * 0.5) console.error('  ⚠ less than half the classes joined — the join is probably wrong, not just incomplete');

fs.writeFileSync(OUT, JSON.stringify({
  [`scheme:intermediary@${VER}->mojmap`]: {
    renames: renames.map(([from, to]) => ({ fromFqcn: from, toFqcn: to, fromSimple: from.split('.').pop(), toSimple: to.split('.').pop(), verified: true, source: 'official-mappings' })),
    advisories: [], deleted: [],
  },
  [`members:intermediary@${VER}->mojmap`]: { members },
}, null, 1) + '\n');
console.log(`  wrote ${path.basename(OUT)}`);
