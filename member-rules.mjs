#!/usr/bin/env node
// MEMBER-LEVEL mappings (methods + fields) — the next compile wall after imports resolve.
//
// Every mapping file we already fetch contains member data; the class-level parsers threw it away.
//   MCPConfig joined.tsrg:  "<obfClass> <srgClass>"  then  "\t<obfMember> [desc] <srgMember>"
//   mojmap ProGuard:        "<readableClass> -> <obfClass>:"  then  "    <ret> <name>(<args>) -> <obf>"
// Joining per class on the OBFUSCATED member name gives  SRG member -> mojmap member.
//
// Why this is safe to apply textually: SRG member names (func_70071_h_, field_70170_p) are GLOBALLY
// UNIQUE tokens — unlike class simple names, they cannot collide, so a plain token rewrite is sound.
//
// Usage: node member-rules.mjs --ver 1.16.5 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const ver = args.ver;
if (!ver) { console.error('Usage: node member-rules.mjs --ver <mcversion> [--write]'); process.exit(2); }
const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });

// ---- mojmap: per obf class -> { obfMember -> readableName } ----
function mojmapMembers(v) {
  const man = JSON.parse(sh('curl -sL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json').stdout);
  const e = man.versions.find((x) => x.id === v);
  if (!e) return null;
  const meta = JSON.parse(sh(`curl -sL "${e.url}"`).stdout || '{}');
  const url = meta.downloads?.client_mappings?.url;
  if (!url) return null;
  const byClass = new Map();
  let cur = null;
  for (const line of (sh(`curl -sL "${url}"`).stdout || '').split('\n')) {
    const cls = line.match(/^([\w.$]+) -> ([\w.$]+):$/);
    if (cls) { cur = new Map(); byClass.set(cls[2], cur); continue; }
    if (!cur || !line.startsWith('    ')) continue;
    // "    <ret> <name>(<args>) -> <obf>"  |  "    <type> <name> -> <obf>"  (line numbers may prefix ret)
    const m = line.trim().match(/^(?:\d+:\d+:)?[\w.$\[\]]+\s+([\w$<>]+)(\([^)]*\))?\s+->\s+([\w$<>]+)$/);
    if (m) cur.set(m[3] + (m[2] ? '()' : ''), m[1]);
  }
  return byClass;
}

// ---- MCPConfig: per obf class -> { obfMember -> srgName } ----
function srgMembers(v) {
  const zip = `/tmp/mcp-${v}.zip`;
  if (!fs.existsSync(zip)) {
    let ok = false;
    for (const host of ['https://maven.minecraftforge.net', 'https://maven.neoforged.net/releases'])
      if (sh(`curl -s -o "${zip}" -w '%{http_code}' -L "${host}/de/oceanlabs/mcp/mcp_config/${v}/mcp_config-${v}.zip"`).stdout.trim() === '200') { ok = true; break; }
    if (!ok) return null;
  }
  const txt = sh(`unzip -p "${zip}" config/joined.tsrg`).stdout || '';
  if (!txt) return null;
  const byClass = new Map();
  let cur = null;
  for (const line of txt.split('\n')) {
    if (!line || line.startsWith('tsrg2')) continue;
    if (!line.startsWith('\t')) {
      const p = line.trim().split(/\s+/);
      if (p.length >= 2) { cur = new Map(); byClass.set(p[0].replace(/\//g, '.'), cur); }
      continue;
    }
    if (!cur) continue;
    const p = line.trim().split(/\s+/);
    // field: "<obf> <srg>"   method: "<obf> <desc> <srg>"
    if (p.length === 2) cur.set(p[0], p[1]);
    else if (p.length === 3) cur.set(p[0] + '()', p[2]);
  }
  return byClass;
}

console.log(`Fetching member mappings for ${ver} ...`);
const moj = mojmapMembers(ver), srg = srgMembers(ver);
for (const [n, v] of [['mojmap', moj], ['MCPConfig', srg]]) {
  if (!v || !v.size) { console.error(`  MISSING: ${n} members for ${ver}`); process.exit(1); }
  console.log(`  ${n}: ${v.size} classes`);
}

// join per class on the obfuscated member name
const table = new Map();       // srgMember -> mojmapMember
let conflicts = 0;
for (const [obfClass, srgMap] of srg) {
  const mojMap = moj.get(obfClass);
  if (!mojMap) continue;
  for (const [obfMember, srgName] of srgMap) {
    const mojName = mojMap.get(obfMember);
    if (!mojName || mojName === srgName) continue;
    if (!/^(func_|field_|p_)/.test(srgName)) continue;   // only rewrite unmistakable SRG tokens
    const prev = table.get(srgName);
    if (prev && prev !== mojName) { conflicts++; table.delete(srgName); continue; }  // ambiguous -> drop
    if (!prev) table.set(srgName, mojName);
  }
}
console.log(`\n=== SRG member -> mojmap member for ${ver} ===`);
console.log(`  unique member mappings : ${table.size}`);
console.log(`  dropped as conflicting : ${conflicts}`);
console.log('\n  --- samples ---');
let n = 0;
for (const [a, b] of table) { if (n++ >= 10) break; console.log(`   ${a.padEnd(22)} -> ${b}`); }

if (args.write && table.size) {
  const rf = 'rules.json';
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = `members:srg@${ver}->mojmap`;
  d[k] = { members: Object.fromEntries(table), advisories: [], renames: [], deleted: [] };
  fs.writeFileSync(rf, JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${table.size} member mapping(s) under "${k}".`);
} else if (table.size) console.log('\n(dry run — pass --write)');
