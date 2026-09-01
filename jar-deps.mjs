#!/usr/bin/env node
// Swap bundled dependencies for the builds their own maintainers already shipped.
//
// blur-plus's four hardest links were never blur's code: they lived in the BUNDLED 26.1 midnightlib,
// whose maintainer had already published 26.2. Porting that code — by table, bridge or model — would
// have been redoing work the upstream author finished, worse. Fabric mods bundle their libraries
// routinely, so "port the mod" often decomposes into "swap the deps that are already ported, then
// port what is left". This automates the swap.
//
// Identification is by SHA-1, not by name. Modrinth's version_file endpoint maps a file hash to the
// exact project and version it came from, so there is no guessing which project a jar named
// `lib-1.9.3.jar` belongs to — either the hash is known and the answer is exact, or the jar is left
// alone and reported. A bundled jar Modrinth has never seen is kept as it is.
//
//   node jar-deps.mjs ported.jar --to 26.2 --out swapped.jar [--deps-dir dir]
//
// --deps-dir also downloads the target build of every swapped dependency into a directory, which is
// exactly what jar-tier2 --deps-dir needs: the compile classpath for code that references them.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0], TO = args.to || '26.2';
if (!JAR) { console.error('usage: node jar-deps.mjs <jar> --to 26.2 --out out.jar [--original unported.jar] [--deps-dir dir]'); process.exit(2); }
const OUT = args.out || JAR.replace(/\.jar$/, '.deps.jar');
const UA = { 'User-Agent': 'FoxGrade/0.1 (minecraft mod porting research)' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(url) {
  const r = await fetch(url, { headers: UA });
  if (r.status === 404) return null;                      // unknown to Modrinth: a fact, not an error
  if (!r.ok) throw new Error(`${r.status} ${url}`);
  return r.json();
}

const jarBuf = fs.readFileSync(JAR);
const entries = [...readZip(jarBuf)];
// Hashes must come from the ORIGINAL mod, not the ported one. Remapping rewrites the bundled jars'
// bytes, so the ported copy's SHA-1 matches nothing Modrinth has ever hosted and every lookup
// comes back unknown. The ported jar is still the one being swapped INTO — the original only
// supplies identities, matched by entry path.
const origEntries = new Map();
if (args.original && fs.existsSync(args.original)) {
  for (const e of readZip(fs.readFileSync(args.original))) if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) origEntries.set(e.name, e);
}
const bundled = entries.filter((e) => /^META-INF\/jars\/.+\.jar$/.test(e.name));
console.log(`  ${path.basename(JAR)}: ${bundled.length} bundled jar(s)`);

const replacements = new Map();
let swapped = 0, current = 0, unknown = 0, missing = 0;
for (const e of bundled) {
  const hashSource = origEntries.get(e.name) || e;
  const sha1 = crypto.createHash('sha1').update(inflateEntry(hashSource)).digest('hex');
  let ver;
  try { ver = await api(`https://api.modrinth.com/v2/version_file/${sha1}?algorithm=sha1`); }
  catch (err) { console.log(`    ! ${path.basename(e.name)}: ${err.message} — kept as is`); unknown++; continue; }
  await sleep(250);
  if (!ver) { console.log(`    ? ${path.basename(e.name)}: not on Modrinth — kept as is`); unknown++; continue; }
  if ((ver.game_versions || []).includes(TO)) { current++; continue; }        // already right for the target
  let builds;
  try { builds = await api(`https://api.modrinth.com/v2/project/${ver.project_id}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TO}%22%5D`); }
  catch (err) { console.log(`    ! ${path.basename(e.name)}: ${err.message} — kept as is`); unknown++; continue; }
  await sleep(250);
  if (!builds || !builds.length) {
    console.log(`    ✗ ${path.basename(e.name)}: no ${TO} build published — kept, and its breakage is real work`);
    missing++; continue;
  }
  const f = builds[0].files.find((x) => x.primary) || builds[0].files[0];
  const r = await fetch(f.url, { headers: UA });
  if (!r.ok) { console.log(`    ! ${path.basename(e.name)}: download failed — kept as is`); unknown++; continue; }
  const fresh = Buffer.from(await r.arrayBuffer());
  // Same entry path: fabric.mod.json lists bundled jars BY PATH, so keeping the name means the
  // manifest needs no edit and nothing can be out of step with it.
  replacements.set(e.name, fresh);
  if (args['deps-dir']) { fs.mkdirSync(args['deps-dir'], { recursive: true }); fs.writeFileSync(path.join(args['deps-dir'], f.filename), fresh); }
  console.log(`    ✓ ${path.basename(e.name)} → ${f.filename}  (official ${TO} build)`);
  swapped++;
}

console.log(`    swapped ${swapped}, already-correct ${current}, no ${TO} build ${missing}, unidentifiable ${unknown}`);
if (!swapped) { console.log('  nothing to swap — output not written.'); process.exit(0); }
fs.writeFileSync(OUT, writeZip(entries, replacements));
console.log(`  wrote ${OUT}`);
