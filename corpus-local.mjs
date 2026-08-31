#!/usr/bin/env node
// Index jars you already have into corpus records, instead of downloading pairs.
//
// corpus-build fetches version PAIRS from Modrinth to mine renames. This answers a different and
// smaller question: what does a shipping mod for this version reference? That is what jar-verify
// needs to tell "absent from the jars because it was deleted" apart from "absent from the jars
// because the loader adds it at runtime" — and it needs no pair, no network and no second version.
//
// It exists because the Fabric corpus cannot speak for NeoForge. NeoForge adds its own methods to
// vanilla interfaces — BlockAndTintGetter.getModelData, .getAuxLightManager — which appear in no
// Minecraft jar and in no Fabric mod, so every one is reported as a broken link. Four of athena's
// five apparent regressions were exactly that. A corpus built from the loader's own ecosystem is
// the only evidence that separates them.
//
//   node corpus-local.mjs ~/path/to/mods --version 1.21.1 --loader neoforge --out ~/foxgrade-corpus-neoforge
import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const DIR = pos[0];
if (!DIR || !fs.existsSync(DIR)) { console.error('usage: node corpus-local.mjs <mods-dir> --version 1.21.1 --loader neoforge --out <dir>'); process.exit(2); }
const VERSION = args.version || 'unknown', LOADER = args.loader || 'unknown';
const OUT = args.out || path.join(DIR, 'corpus');
fs.mkdirSync(OUT, { recursive: true });

// Mixin injection selectors, kept alongside the refs because mixin-check wants the same evidence:
// a selector many shipping mods use is a real injection point, whatever the jars say.
function indexJar(buf, refs, sels) {
  for (const e of readZip(buf)) {
    // Bundled jars are where loaders put their own API, so they carry exactly the references this
    // is trying to record. Skipping them would leave the corpus blind to the thing it is for.
    if (/\.jar$/.test(e.name)) { try { indexJar(inflateEntry(e), refs, sels); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
    for (const r of cf.refs()) {
      if (!r.owner.startsWith('net/minecraft/') && !r.owner.startsWith('com/mojang/')) continue;
      refs.add(`${r.owner}\t${r.name}\t${r.desc}`);
    }
    try { for (const { value } of cf.utf8()) if (/^[\w$]{4,}$/.test(value)) sels.add(value); } catch { /* skip */ }
  }
}

const jars = fs.readdirSync(DIR).filter((f) => f.endsWith('.jar'));
let done = 0, failed = 0, totalRefs = 0;
for (const f of jars) {
  const slug = f.replace(/\.jar$/, '').replace(/[^\w.-]+/g, '_');
  const dest = path.join(OUT, `${slug}.json.gz`);
  if (fs.existsSync(dest)) { done++; continue; }
  const refs = new Set(), sels = new Set();
  try { indexJar(fs.readFileSync(path.join(DIR, f)), refs, sels); }
  catch (e) { console.log(`  ! ${f}: ${e.message}`); failed++; continue; }
  if (!refs.size) { failed++; continue; }        // a resource-only jar says nothing about the API
  fs.writeFileSync(dest, zlib.gzipSync(JSON.stringify({
    slug, from: VERSION, to: VERSION, loader: LOADER,
    source: 'local jar index — references only, no version pair',
    new: { refs: [...refs], sels: [...sels].slice(0, 4000) },
  })));
  totalRefs += refs.size; done++;
}
console.log(`  indexed ${done}/${jars.length} jar(s) from ${path.basename(DIR)}${failed ? `, ${failed} skipped` : ''}`);
console.log(`  ${totalRefs.toLocaleString()} member reference(s) recorded for ${LOADER} ${VERSION}`);
console.log(`  wrote ${OUT}`);
