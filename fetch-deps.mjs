#!/usr/bin/env node
// Fetch a mod's DECLARED dependencies at the target version, for Tier 2's compile classpath.
//
// blur-plus taught this the expensive way: without midnightlib on the classpath the model could
// only compile by deleting the config system, and the escalation dutifully refused it. Supplied the
// dependency, it ported the class cleanly on attempt 2. A mod's fabric.mod.json names what it
// depends on; those projects usually have target-version builds; withholding them forces a false
// choice between "delete the feature" and "never compiles".
//
//   node fetch-deps.mjs mod.jar --to 26.2 --out depsdir
import fs from 'node:fs';
import path from 'node:path';
import { readZip, inflateEntry } from './zipfile.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0], TO = args.to || '26.2', OUT = args.out;
if (!JAR || !OUT) { console.error('usage: node fetch-deps.mjs <mod.jar> --to 26.2 --out dir'); process.exit(2); }
fs.mkdirSync(OUT, { recursive: true });
const UA = { 'User-Agent': 'FoxGrade/0.1 (minecraft mod porting research)' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let manifest = null;
for (const e of readZip(fs.readFileSync(JAR))) if (e.name === 'fabric.mod.json') { manifest = JSON.parse(inflateEntry(e).toString('utf8')); break; }
if (!manifest) { console.log('  no fabric.mod.json'); process.exit(0); }
// Loader-level ids are provided by the environment, not by mods on Modrinth.
const SKIP = new Set(['minecraft', 'java', 'fabricloader', 'fabric', 'fabric-api', 'fabric-language-kotlin']);
const wants = Object.keys(manifest.depends || {}).filter((d) => !SKIP.has(d));
console.log(`  ${path.basename(JAR)}: depends on ${wants.length ? wants.join(', ') : '(nothing beyond the loader)'}`);
for (const id of wants) {
  try {
    // A Fabric mod id usually doubles as its Modrinth slug; when it does not, the search endpoint
    // is consulted and only an EXACT slug or id match is accepted — a near-miss dependency is worse
    // than a missing one, because it compiles against the wrong library.
    let vs = await (await fetch(`https://api.modrinth.com/v2/project/${id}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TO}%22%5D`, { headers: UA })).json().catch(() => null);
    if (!Array.isArray(vs) || !vs.length) {
      // A Fabric mod id and a Modrinth slug are different namespaces — puzzleslib lives at the slug
      // puzzles-lib — so a miss here is not evidence of absence. The fallback SEARCHES, but never
      // trusts a search rank: each candidate's jar is downloaded and its own fabric.mod.json must
      // declare exactly the requested id. The dependency proves its identity or it is not used.
      vs = null;
      // Modrinth's search does not tokenise a concatenated id: "puzzleslib" finds nothing while
      // "puzzles lib" finds the project. Query variants split on common suffix words — safe to be
      // liberal here precisely because the identity check below rejects anything that is not the
      // requested mod.
      const variants = [...new Set([id, id.replace(/[-_]/g, ' '),
        id.replace(/(lib|api|port|mod|config|menu|util)/g, ' $1 ').replace(/\s+/g, ' ').trim()])];
      const seenHits = new Map();
      for (const q of variants) {
        const sr = await (await fetch(`https://api.modrinth.com/v2/search?query=${encodeURIComponent(q)}&facets=${encodeURIComponent(JSON.stringify([[`versions:${TO}`], ['categories:fabric'], ['project_type:mod']]))}&limit=5`, { headers: UA })).json().catch(() => ({ hits: [] }));
        for (const h of sr.hits || []) if (!seenHits.has(h.project_id)) seenHits.set(h.project_id, h);
        await sleep(200);
      }
      for (const hit of seenHits.values()) {
        await sleep(250);
        const cand = await (await fetch(`https://api.modrinth.com/v2/project/${hit.project_id}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TO}%22%5D`, { headers: UA })).json().catch(() => null);
        if (!Array.isArray(cand) || !cand.length) continue;
        const cf = cand[0].files.find((x) => x.primary) || cand[0].files[0];
        const buf = Buffer.from(await (await fetch(cf.url, { headers: UA })).arrayBuffer());
        let declared = null;
        try { for (const e of readZip(buf)) if (e.name === 'fabric.mod.json') { declared = JSON.parse(inflateEntry(e).toString('utf8')).id; break; } } catch { /* not it */ }
        if (declared === id) { fs.writeFileSync(path.join(OUT, cf.filename), buf); console.log(`    ✓ ${id} -> ${cf.filename}  (found at slug '${hit.slug}', identity verified from its own manifest)`); vs = 'done'; break; }
      }
      if (vs !== 'done') console.log(`    ✗ ${id}: no ${TO} build found — Tier 2 runs without it`);
      await sleep(250); continue;
    }
    const f = vs[0].files.find((x) => x.primary) || vs[0].files[0];
    const dest = path.join(OUT, f.filename);
    if (!fs.existsSync(dest)) fs.writeFileSync(dest, Buffer.from(await (await fetch(f.url, { headers: UA })).arrayBuffer()));
    console.log(`    ✓ ${id} -> ${f.filename}`);
  } catch (e) { console.log(`    ! ${id}: ${e.message}`); }
  await sleep(300);
}
