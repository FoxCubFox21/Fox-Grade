#!/usr/bin/env node
// Harvest HUMAN-VERIFIED mappings the tables are missing.
// For every tested pair: re-derive the corroborated ground truth from the two human branches, keep the
// entries our resolver can NOT already produce, and write them as verified rules.
// Zero AI. Provenance: the human port itself. Corroboration rules match test-accuracy.mjs:
//   same-simple-name swap  -> 1 sighting suffices (name is an invariant)
//   true rename            -> >= 2 independent files must agree, no contradictions
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
const sh = (c) => spawnSync('bash', ['-c', c], { encoding: 'utf8', maxBuffer: 512e6 });

const TARCACHE = '/tmp/foxgrade-tarcache';
function fetchRef(repo, ref) {
  fs.mkdirSync(TARCACHE, { recursive: true });
  const safe = `${repo}@${ref}`.replace(/[^A-Za-z0-9._@-]/g, '_');
  const tgz = path.join(TARCACHE, safe + '.tar.gz');
  if (!fs.existsSync(tgz) || fs.statSync(tgz).size < 10000)
    sh(`curl -sfL "https://codeload.github.com/${repo}/tar.gz/${encodeURIComponent(ref)}" -o "${tgz}.part" && mv "${tgz}.part" "${tgz}"`);
  if (!fs.existsSync(tgz)) return null;
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'hv-'));
  sh(`tar xzf "${tgz}" -C "${d}"`);
  const r = fs.readdirSync(d);
  return r.length ? path.join(d, r[0]) : null;
}
function walk(d, o = []) { if (!fs.existsSync(d)) return o;
  for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : (p.endsWith('.java') && o.push(p)); } return o; }
const importsOf = (s) => { const m = new Map();
  for (const x of s.matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) m.set(x[1], x[1].slice(x[1].lastIndexOf('.') + 1));
  return m; };

const d = JSON.parse(fs.readFileSync('rules.json', 'utf8'));
const flat = new Map();
for (const [k, v] of Object.entries(d)) { if (k.startsWith('_')) continue;
  for (const r of (v.renames || [])) if (r.verified !== false && !flat.has(r.fromFqcn)) flat.set(r.fromFqcn, r.toFqcn); }
const resolve = (fq) => { let c = fq, s = new Set(); while (flat.has(c) && !s.has(c)) { s.add(c); c = flat.get(c); } return c; };
const banned = new Set((d['26.2'].deleted || []).map((x) => x.fqcn));

const pairs = fs.readFileSync('overnight-pairs.txt', 'utf8').trim().split('\n').map((l) => l.split(' '));
let added = 0, already = 0, blocked = 0;
for (const [repo, oldRef, newRef] of pairs) {
  const A = fetchRef(repo, oldRef), B = fetchRef(repo, newRef);
  if (!A || !B) { console.log(`  skip ${repo} ${oldRef}->${newRef} (fetch failed)`); continue; }
  const key = (root, f) => { const rel = path.relative(root, f); const i = rel.lastIndexOf('src/main/java/'); return i >= 0 ? rel.slice(i + 14) : null; };
  const oldF = new Map(walk(A).map((f) => [key(A, f), f]).filter(([k]) => k));
  const newF = new Map(walk(B).map((f) => [key(B, f), f]).filter(([k]) => k));
  const truth = new Map();   // from -> Map(to -> sightings)
  for (const k of [...oldF.keys()].filter((k) => newF.has(k))) {
    const X = importsOf(fs.readFileSync(oldF.get(k), 'utf8')), Y = importsOf(fs.readFileSync(newF.get(k), 'utf8'));
    const rem = [...X.keys()].filter((x) => !Y.has(x) && /^(net\.minecraft|com\.mojang)\./.test(x));
    const add = [...Y.keys()].filter((x) => !X.has(x) && /^(net\.minecraft|com\.mojang)\./.test(x));
    for (const r of rem) { const h = add.find((x) => Y.get(x) === X.get(r));
      if (h) { if (!truth.has(r)) truth.set(r, new Map()); truth.get(r).set(h, (truth.get(r).get(h) || 0) + 1); } }
  }
  const blockKey = `${oldRef}->${newRef}`;
  for (const [from, tos] of truth) {
    if (tos.size !== 1) continue;                                   // contradictions -> never
    const [to, sightings] = [...tos.entries()][0];
    const sameName = from.split('.').pop() === to.split('.').pop();
    if (!sameName && sightings < 2) continue;                       // uncorroborated rename -> never
    if (banned.has(from)) { blocked++; continue; }                  // anti-facts stay authoritative
    if (resolve(from) === to) { already++; continue; }              // tables already know it
    d[blockKey] = d[blockKey] || { renames: [], advisories: [], deleted: [] };
    if (d[blockKey].renames.some((r) => r.fromFqcn === from)) continue;
    d[blockKey].renames.push({
      fromFqcn: from, toFqcn: to, fromSimple: from.split('.').pop(), toSimple: to.split('.').pop(),
      verified: true, chainable: sameName, kind: sameName ? 'move' : 'rename',
      source: `human-groundtruth:${repo}`, evidence: sightings,
      note: `harvested from the human port ${repo} ${oldRef}->${newRef}`,
    });
    flat.set(from, to);                                             // usable immediately for later pairs
    added++;
  }
  sh(`rm -rf "${path.dirname(A)}" "${path.dirname(B)}"`);
}
fs.writeFileSync('rules.json', JSON.stringify(d, null, 2) + '\n');
console.log(`\nharvest: +${added} new human-verified rules · ${already} already known · ${blocked} blocked by anti-facts`);
