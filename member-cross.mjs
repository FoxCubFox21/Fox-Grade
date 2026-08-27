#!/usr/bin/env node
// CROSS-VERSION MEMBER RENAMES — authoritative, no inference.
//
// Our 113k member rules only translate SRG->mojmap *within one version*. But intermediary assigns
// STABLE ids to methods and fields too (method_1234 / field_5678), so the same join that produced the
// authoritative class table works at member level:
//
//   verA:  readable_A <- obfMember -> method_1234     (mojmap ⋈ intermediary, per class)
//   verB:  readable_B <- obfMember -> method_1234
//                        => readable_A -> readable_B  across versions. Authoritative.
//
// Safety: an obfuscated member name can be overloaded within a class. Any obf name that maps to more
// than one readable name (or more than one intermediary id) inside its class is DROPPED, never guessed.
//
// Usage: node member-cross.mjs --from 1.16.5 --to 1.20.1 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const A = args.from, B = args.to;
if (!A || !B) { console.error('usage: node member-cross.mjs --from <ver> --to <ver> [--write]'); process.exit(2); }
const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });

// mojmap: per obf class -> Map(obfMember -> readableName), dropping overloaded/ambiguous obf names
function mojmapMembers(v) {
  const man = JSON.parse(sh('curl -sL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json').stdout);
  const e = man.versions.find((x) => x.id === v);
  if (!e) return null;
  const meta = JSON.parse(sh(`curl -sL "${e.url}"`).stdout || '{}');
  const url = meta.downloads?.client_mappings?.url;
  if (!url) return null;
  const byClass = new Map();
  let cur = null, curName = null;
  const dupes = new Map();
  for (const line of (sh(`curl -sL "${url}"`).stdout || '').split('\n')) {
    const cls = line.match(/^([\w.$]+) -> ([\w.$]+):$/);
    if (cls) { cur = new Map(); dupes.set(cls[2], new Set()); curName = cls[2]; byClass.set(cls[2], cur); continue; }
    if (!cur || !line.startsWith('    ')) continue;
    const m = line.trim().match(/^(?:\d+:\d+:)?[\w.$\[\]]+\s+([\w$<>]+)(\([^)]*\))?\s+->\s+([\w$<>]+)$/);
    if (!m) continue;
    const obf = m[3], readable = m[1];
    if (cur.has(obf) && cur.get(obf) !== readable) dupes.get(curName).add(obf);  // overloaded -> unsafe
    cur.set(obf, readable);
  }
  for (const [cn, bad] of dupes) { const c = byClass.get(cn); if (c) for (const b of bad) c.delete(b); }
  return byClass;
}

// intermediary: per obf class -> { name: intermediaryClass, members: Map(obfMember -> intermediaryId) }
function intermediaryMembers(v) {
  const jar = `/tmp/int-${v}.jar`;
  if (!fs.existsSync(jar))
    sh(`curl -sfL "https://maven.fabricmc.net/net/fabricmc/intermediary/${v}/intermediary-${v}-v2.jar" -o "${jar}"`);
  const txt = sh(`unzip -p "${jar}" mappings/mappings.tiny`).stdout || '';
  if (!txt) return null;
  const byClass = new Map();
  let cur = null;
  const seen = new Map();
  for (const line of txt.split('\n')) {
    if (line.startsWith('c\t')) {
      const p = line.split('\t');
      cur = { name: p[2], members: new Map() };
      seen.set(p[1], new Set());
      byClass.set(p[1], cur);
      continue;
    }
    if (!cur || !line.startsWith('\t')) continue;
    const p = line.split('\t');            // "", f|m, desc, obfName, intermediaryName
    if (p.length >= 5 && (p[1] === 'f' || p[1] === 'm')) {
      const obf = p[3], id = p[4];
      if (cur.members.has(obf) && cur.members.get(obf) !== id) cur.members.set(obf, null); // ambiguous
      else if (!cur.members.has(obf)) cur.members.set(obf, id);
    }
  }
  return byClass;
}

console.log(`Fetching member mappings for ${A} and ${B} ...`);
const [mA, mB, iA, iB] = [mojmapMembers(A), mojmapMembers(B), intermediaryMembers(A), intermediaryMembers(B)];
for (const [n, v] of [[`mojmap ${A}`, mA], [`mojmap ${B}`, mB], [`intermediary ${A}`, iA], [`intermediary ${B}`, iB]]) {
  if (!v || !v.size) { console.error(`  MISSING: ${n}`); process.exit(1); }
  console.log(`  ${n}: ${v.size} classes`);
}

// intermediaryMemberId -> readable name, per version
function indexByIntermediary(moj, inter) {
  const out = new Map();
  const conflict = new Set();
  for (const [obfClass, im] of inter) {
    const mm = moj.get(obfClass);
    if (!mm) continue;
    for (const [obfMember, id] of im.members) {
      if (!id) continue;
      const readable = mm.get(obfMember);
      if (!readable) continue;
      if (out.has(id) && out.get(id) !== readable) conflict.add(id);
      out.set(id, readable);
    }
  }
  for (const c of conflict) out.delete(c);   // same id -> two names: never trust
  return out;
}
const byIdA = indexByIntermediary(mA, iA);
const byIdB = indexByIntermediary(mB, iB);
console.log(`  joined: ${byIdA.size} members in ${A}, ${byIdB.size} in ${B}`);

const renames = [];
let same = 0;
// Compiler-generated members are not API — no mod source ever references them, and rewriting a token
// like `lambda$null$2` in user code would be actively harmful.
const SYNTHETIC = /^(lambda\$|\$SwitchMap\$|access\$|this\$|val\$|<init>|<clinit>|\$VALUES$|\$assertionsDisabled$)/;
let synthetic = 0;
for (const [id, a] of byIdA) {
  const b = byIdB.get(id);
  if (!b) continue;
  if (a === b) { same++; continue; }
  if (SYNTHETIC.test(a) || SYNTHETIC.test(b)) { synthetic++; continue; }
  if (a.length < 3) continue;                       // ultra-short names are too collision-prone to rewrite
  renames.push({ from: a, to: b, id });
}
console.log(`\n=== AUTHORITATIVE MEMBER RENAMES ${A} -> ${B} ===`);
console.log(`  unchanged : ${same}`);
console.log(`  RENAMED   : ${renames.length}  (${synthetic} synthetic/compiler members filtered out)`);
console.log('\n  --- samples ---');
for (const r of renames.slice(0, 15)) console.log(`   ${r.from.padEnd(34)} -> ${r.to}`);

if (args.write && renames.length) {
  const rf = 'rules.json';
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = `membersX:${A}->${B}`;
  // Ambiguity guard across the whole table: a source name that maps two ways anywhere is dropped.
  const counts = new Map();
  for (const r of renames) {
    if (!counts.has(r.from)) counts.set(r.from, new Set());
    counts.get(r.from).add(r.to);
  }
  const table = {};
  let dropped = 0;
  for (const r of renames) {
    if (counts.get(r.from).size > 1) { dropped++; continue; }
    table[r.from] = r.to;
  }
  d[k] = { members: table, renames: [], advisories: [], deleted: [], source: 'official-mappings-crossversion' };
  fs.writeFileSync(rf, JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${Object.keys(table).length} cross-version member rename(s) under "${k}" (${dropped} dropped as ambiguous).`);
} else if (renames.length) console.log('\n(dry run — pass --write)');
