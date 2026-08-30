#!/usr/bin/env node
// Build a large corpus of mod version-pairs, WITHOUT keeping the mods.
//
// Mining needs two things from a jar: which Minecraft members it references, and which methods its
// mixins inject into. Both are a few tens of KB of names. The jar they came from is megabytes and is
// never needed again — so each pair is downloaded, reduced to its index, and deleted. A thousand
// pairs costs roughly a hundred MB instead of five gigabytes, and later mining reads the index
// rather than re-opening archives.
//
// Resumable by design: every mod is written as its own file and skipped if already present, so this
// can be stopped and restarted freely. Network failures skip that mod rather than ending the run.
//
//   node corpus-build.mjs --from 26.1 --to 26.2 --limit 1000 --out corpus/
import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const FROM = args.from || '26.1', TO = args.to || '26.2';
const LIMIT = +(args.limit || 200);
const OUT = args.out || path.join(HERE, `corpus.${FROM}-${TO}`);
const TMP = fs.mkdtempSync('/tmp/foxgrade-corpus-');
fs.mkdirSync(OUT, { recursive: true });

const UA = { 'User-Agent': 'FoxGrade/0.1 (minecraft mod porting research)' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let reqs = 0;
async function api(url) {
  // Modrinth allows 300 requests a minute. Staying well under it is the difference between a corpus
  // and a ban, and this run is long enough that being impolite would be noticed.
  if (++reqs % 20 === 0) await sleep(1500);
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await fetch(url, { headers: UA });
      if (r.status === 429) { await sleep(10000); continue; }
      if (!r.ok) return null;
      return await r.json();
    } catch { await sleep(2000); }
  }
  return null;
}

// What a jar contributes: Minecraft members it calls, and the hooks its mixins name.
function indexJar(buf) {
  const refs = new Set(), sels = new Set();
  const scan = (b) => {
    let entries; try { entries = readZip(b); } catch { return; }
    for (const e of entries) {
      if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
      if (!e.name.endsWith('.class')) continue;
      let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
      for (const r of cf.refs()) if (r.owner.startsWith('net/minecraft/')) refs.add(`${r.owner}\t${r.name}\t${r.desc}`);
      if (!/Mixin[\w$]*\.class$/.test(e.name)) continue;
      let own; try { own = new Set(cf.declared().members.map((m) => m.name)); } catch { own = new Set(); }
      const targets = new Set();
      for (const { value } of cf.utf8()) {
        if (!ClassFile.isPlain(value)) continue;
        for (const g of value.matchAll(/L(net\/minecraft\/[\w/$]+);/g)) targets.add(g[1]);
      }
      for (const { value } of cf.utf8()) {
        if (!ClassFile.isPlain(value) || !/^[a-z][\w$]{3,}$/.test(value) || own.has(value)) continue;
        for (const t of targets) sels.add(`${t}\t${value}`);
      }
    }
  };
  scan(buf);
  return { refs: [...refs], sels: [...sels] };
}

async function download(url) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try { const r = await fetch(url, { headers: UA }); if (r.ok) return Buffer.from(await r.arrayBuffer()); }
    catch { await sleep(1500); }
  }
  return null;
}

const facet = (o) => Object.entries(o).map(([k, v]) => `${k}=${encodeURIComponent(typeof v === 'string' ? v : JSON.stringify(v))}`).join('&');
let done = fs.readdirSync(OUT).filter((f) => f.endsWith('.json.gz')).length;
let scanned = 0, added = 0, skipped = 0, failed = 0;
console.log(`  corpus dir : ${OUT}  (${done} already present)`);
console.log(`  target     : ${LIMIT} pairs, ${FROM} -> ${TO}\n`);

for (let offset = 0; added + done < LIMIT && offset < 5000; offset += 100) {
  const search = await api(`https://api.modrinth.com/v2/search?${facet({
    facets: [['project_type:mod'], ['categories:fabric'], [`versions:${TO}`]],
    limit: 100, offset, index: 'downloads',
  })}`);
  if (!search?.hits?.length) break;
  for (const hit of search.hits) {
    if (added + done >= LIMIT) break;
    scanned++;
    const slug = hit.slug;
    const dest = path.join(OUT, `${slug}.json.gz`);
    if (fs.existsSync(dest)) { skipped++; continue; }
    const versions = await api(`https://api.modrinth.com/v2/project/${slug}/version`);
    if (!versions) { failed++; continue; }
    const fab = versions.filter((v) => v.loaders.includes('fabric'));
    const oldV = fab.find((v) => v.game_versions.includes(FROM));
    const newV = fab.find((v) => v.game_versions.includes(TO));
    // Both builds must exist, and be different builds — the same file listed for both versions
    // teaches nothing, because nothing changed between them.
    if (!oldV || !newV || oldV.id === newV.id) { skipped++; continue; }
    const of = oldV.files.find((f) => f.primary) || oldV.files[0];
    const nf = newV.files.find((f) => f.primary) || newV.files[0];
    if (!of || !nf || of.size > 60e6 || nf.size > 60e6) { skipped++; continue; }
    const [ob, nb] = [await download(of.url), await download(nf.url)];
    if (!ob || !nb) { failed++; continue; }
    let rec;
    try { rec = { slug, from: FROM, to: TO, oldVersion: oldV.version_number, newVersion: newV.version_number, old: indexJar(ob), new: indexJar(nb) }; }
    catch { failed++; continue; }
    // A pair with no Minecraft references on either side has nothing to teach.
    if (!rec.old.refs.length && !rec.old.sels.length) { skipped++; continue; }
    // Gzip: these indexes are almost entirely repeated class and member names, so they compress
    // roughly tenfold. That matters twice — a thousand pairs stays in the low hundreds of MB, and an
    // index small enough to share is the difference between a corpus and a personal cache.
    fs.writeFileSync(dest, zlib.gzipSync(Buffer.from(JSON.stringify(rec)), { level: 9 }));
    added++;
    if (added % 10 === 0) {
      const mb = fs.readdirSync(OUT).reduce((t, f) => t + fs.statSync(path.join(OUT, f)).size, 0) / 1048576;
      console.log(`  ${(added + done).toString().padStart(4)} pairs   ${mb.toFixed(0)} MB index   (${scanned} scanned, ${skipped} skipped, ${failed} failed)`);
    }
  }
}
fs.rmSync(TMP, { recursive: true, force: true });
const total = fs.readdirSync(OUT).filter((f) => f.endsWith('.json.gz')).length;
const mb = fs.readdirSync(OUT).reduce((t, f) => t + fs.statSync(path.join(OUT, f)).size, 0) / 1048576;
console.log(`\n  ${total} pairs indexed, ${mb.toFixed(0)} MB total. No jars kept.`);
console.log(`  ${scanned} projects examined, ${skipped} had no usable pair, ${failed} failed to fetch.`);
