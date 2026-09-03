#!/usr/bin/env node
// Fetch ONE dependency by its Fabric mod id, for a given Minecraft version.
//
// Split out of fetch-deps so the folder tool can ask for a single library without knowing which
// jar wanted it. The identity rule is the one that matters and is unchanged: a mod id and a
// Modrinth slug are different namespaces (puzzleslib lives at puzzles-lib), search does not
// tokenise concatenated ids, and no search rank is ever trusted — a candidate is downloaded and
// its own fabric.mod.json must declare exactly the requested id.
import fs from 'node:fs';
import path from 'node:path';
import { readZip, inflateEntry } from './zipfile.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const ID = pos[0], TO = args.to || '26.2', OUT = args.out;
if (!ID || !OUT) { console.error('usage: node fetch-deps-by-id.mjs <modid> --to <ver> --out <dir>'); process.exit(2); }
const UA = { 'User-Agent': 'FoxGrade (personal mod updating)' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const grab = async (u) => { try { const r = await fetch(u, { headers: UA }); return r.ok ? r.json() : null; } catch { return null; } };

const install = async (v) => {
  const f = v.files.find((x) => x.primary) || v.files[0];
  const dest = path.join(OUT, f.filename);
  if (!fs.existsSync(dest)) {
    const r = await fetch(f.url, { headers: UA });
    if (!r.ok) return false;
    fs.writeFileSync(dest, Buffer.from(await r.arrayBuffer()));
  }
  console.log(`OK ${ID} -> ${f.filename}`);
  return true;
};

let vs = await grab(`https://api.modrinth.com/v2/project/${ID}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TO}%22%5D`);
if (Array.isArray(vs) && vs.length) { await install(vs[0]); process.exit(0); }

const variants = [...new Set([ID, ID.replace(/[-_]/g, ' '), ID.replace(/(lib|api|port|mod|config|menu|util)/g, ' $1 ').replace(/\s+/g, ' ').trim()])];
const seen = new Map();
for (const q of variants) {
  const sr = await grab(`https://api.modrinth.com/v2/search?query=${encodeURIComponent(q)}&facets=${encodeURIComponent(JSON.stringify([[`versions:${TO}`], ['categories:fabric'], ['project_type:mod']]))}&limit=5`);
  for (const h of (sr && sr.hits) || []) if (!seen.has(h.project_id)) seen.set(h.project_id, h);
  await sleep(200);
}
for (const hit of seen.values()) {
  const cand = await grab(`https://api.modrinth.com/v2/project/${hit.project_id}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TO}%22%5D`);
  await sleep(200);
  if (!Array.isArray(cand) || !cand.length) continue;
  const f = cand[0].files.find((x) => x.primary) || cand[0].files[0];
  const r = await fetch(f.url, { headers: UA });
  if (!r.ok) continue;
  const buf = Buffer.from(await r.arrayBuffer());
  let declared = null;
  try { for (const e of readZip(buf)) if (e.name === 'fabric.mod.json') { declared = JSON.parse(inflateEntry(e).toString('utf8')).id; break; } } catch { /* not it */ }
  if (declared !== ID) continue;
  fs.writeFileSync(path.join(OUT, f.filename), buf);
  console.log(`OK ${ID} -> ${f.filename} (slug ${hit.slug}, identity verified)`);
  process.exit(0);
}
console.log(`MISSING ${ID}`);
