#!/usr/bin/env node
// MCP/SRG -> MOJMAP scheme translation (authoritative, no inference).
//
// Forge mods up to ~1.16 are written against MCP names. MCP *class* names ARE the SRG class names
// (net/minecraft/util/math/BlockPos); only members are func_NNNNN/field_NNNNN. MCPConfig publishes
// obf -> SRG, and Mojang publishes obf -> mojmap, so the OBFUSCATED name joins them within one version:
//
//   SRG/MCP name  <- obf ->  mojmap name
//
// (Parchment cannot do this: it layers parameter names + javadocs ON TOP of mojmap and never renames
// classes, so it offers no class-level scheme translation.)
//
// Usage: node mcp-scheme.mjs --ver 1.16.5 [--write]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write') args.write = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const ver = args.ver;
if (!ver) { console.error('Usage: node mcp-scheme.mjs --ver <mcversion> [--write]'); process.exit(2); }
const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });

// --- mojmap: obf -> readable ---
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
    if (g && !g[1].includes('$')) m.set(g[2], g[1]);   // obf -> readable
  }
  return m;
}

// --- MCPConfig: obf -> SRG (handles both tsrg v1 and tsrg2) ---
function srg(v) {
  const zip = `/tmp/mcp-${v}.zip`;
  let ok = false;
  for (const host of ['https://maven.minecraftforge.net', 'https://maven.neoforged.net/releases']) {
    const code = sh(`curl -s -o "${zip}" -w '%{http_code}' -L "${host}/de/oceanlabs/mcp/mcp_config/${v}/mcp_config-${v}.zip"`).stdout.trim();
    if (code === '200') { ok = true; break; }
  }
  if (!ok) return null;
  const txt = sh(`unzip -p "${zip}" config/joined.tsrg`).stdout || '';
  if (!txt) return null;
  const m = new Map();
  const tsrg2 = txt.startsWith('tsrg2');
  for (const line of txt.split('\n')) {
    if (!line || line.startsWith('\t') || line.startsWith(' ') || line.startsWith('tsrg2')) continue;
    const p = line.trim().split(/\s+/);
    // tsrg v1: "<obf> <srg>"   tsrg2: "<obf> <srg> ..." (same first two columns for classes)
    if (p.length >= 2 && /\//.test(p[1])) m.set(p[0].replace(/\//g, '.'), p[1].replace(/\//g, '.'));
  }
  return m;
}

console.log(`Fetching mojmap + MCPConfig(SRG) for ${ver} ...`);
const moj = mojmap(ver), sg = srg(ver);
for (const [n, v] of [['mojmap', moj], ['SRG (MCPConfig)', sg]]) {
  if (!v || !v.size) { console.error(`  MISSING: ${n} for ${ver} — cannot build an MCP scheme table.`); process.exit(1); }
  console.log(`  ${n}: ${v.size} classes`);
}

// join on the obfuscated name
const same = [], diff = [];
for (const [obf, srgName] of sg) {
  const mj = moj.get(obf);
  if (!mj) continue;
  (srgName === mj ? same : diff).push({ from: srgName, to: mj, obf });
}
console.log(`\n=== MCP/SRG -> MOJMAP for ${ver} ===`);
console.log(`  identical in both schemes   : ${same.length}`);
console.log(`  DIFFERENT (need translating): ${diff.length}`);
console.log('\n  --- samples ---');
for (const r of diff.slice(0, 12)) console.log(`   ${r.from}\n       -> ${r.to}`);

if (args.write && diff.length) {
  const rf = 'rules.json';
  const d = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = `scheme:mcp@${ver}->mojmap`;
  d[k] = { renames: [], advisories: [], deleted: [] };
  for (const r of diff) {
    d[k].renames.push({
      fromFqcn: r.from, toFqcn: r.to,
      fromSimple: r.from.split('.').pop(), toSimple: r.to.split('.').pop(),
      verified: true, chainable: false, kind: 'scheme',
      source: 'official-mappings', provenance: `MCPConfig SRG ⋈ mojmap on obf "${r.obf}" @ ${ver}`,
    });
  }
  fs.writeFileSync(rf, JSON.stringify(d, null, 2) + '\n');
  console.log(`\nWROTE ${diff.length} MCP scheme rule(s) under "${k}".`);
} else if (diff.length) console.log('\n(dry run — pass --write)');
