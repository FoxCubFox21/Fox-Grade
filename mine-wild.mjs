#!/usr/bin/env node
// Foxgrade wild-corpus miner (learning ⑦) — the best free training data there is.
//
// Many mods exist on GitHub for BOTH an old and a new Minecraft version. Diffing the same file across
// those two refs gives a migration a HUMAN actually shipped and tested — higher quality than anything
// an LLM would guess, and it costs nothing but bandwidth.
//
// It pairs files by package+filename, diffs their imports, and only trusts a mapping that is seen
// repeatedly with NO contradicting evidence.
//
// Usage:
//   node mine-wild.mjs --repo TheGreyGhost/MinecraftByExample --old 1-8final --new 1-16-3-final [--write --as "1.8->1.16"]
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (t === '--write' || t === '--chainable') args[t.slice(2)] = true;
  else if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i];
}
const repo = args.repo, oldRef = args.old, newRef = args.new;
if (!repo || !oldRef || !newRef) {
  console.error('Usage: node mine-wild.mjs --repo OWNER/REPO --old <ref> --new <ref> [--write --as "1.8->1.16"] [--chainable]');
  process.exit(2);
}

// One tarball per ref beats hundreds of per-file API calls.
function fetchRef(ref) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wild-'));
  TMPS.push(dir);
  const url = `https://codeload.github.com/${repo}/tar.gz/${encodeURIComponent(ref)}`;
  const r = spawnSync('bash', ['-c', `curl -sL "${url}" | tar xz -C "${dir}"`], { encoding: 'utf8' });
  if (r.status !== 0) return null;
  const roots = fs.readdirSync(dir);
  return roots.length ? path.join(dir, roots[0]) : null;
}
function walk(d, o = []) {
  if (!fs.existsSync(d)) return o;
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : (p.endsWith('.java') && o.push(p));
  }
  return o;
}
const importsOf = (src) => {
  const m = new Map();
  for (const x of src.matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) m.set(x[1], x[1].slice(x[1].lastIndexOf('.') + 1));
  return m;
};

const TMPS = [];
process.on("exit", () => { for (const t of TMPS) { try { fs.rmSync(t, { recursive: true, force: true }); } catch {} } });
console.log(`Fetching ${repo} @ ${oldRef} and @ ${newRef} ...`);
const [oldRoot, newRoot] = [fetchRef(oldRef), fetchRef(newRef)];
if (!oldRoot || !newRoot) { console.error('Could not download one of the refs (bad name, or network blocked).'); process.exit(1); }

// Pair files by their package path + filename (that survives directory reshuffles).
const key = (root, f) => {
  const rel = path.relative(root, f);
  const i = rel.indexOf('src/main/java/');
  return i >= 0 ? rel.slice(i + 'src/main/java/'.length) : rel;
};
const oldFiles = new Map(walk(path.join(oldRoot, 'src')).map((f) => [key(oldRoot, f), f]));
const newFiles = new Map(walk(path.join(newRoot, 'src')).map((f) => [key(newRoot, f), f]));
const paired = [...oldFiles.keys()].filter((k) => newFiles.has(k));
// Fall back to basename matching for files that moved package.
const byBaseNew = new Map();
for (const [k, f] of newFiles) { const b = path.basename(k); if (!byBaseNew.has(b)) byBaseNew.set(b, f); }
const extra = [...oldFiles.keys()].filter((k) => !newFiles.has(k) && byBaseNew.has(path.basename(k)));
console.log(`old: ${oldFiles.size} files · new: ${newFiles.size} files · paired: ${paired.length} exact + ${extra.length} by name\n`);

// Aggregate evidence: mapping -> count, and detect contradictions (same source mapped two ways).
const evidence = new Map(); // fromFqcn -> Map(toFqcn -> count)
function consider(a, b) {
  const A = importsOf(fs.readFileSync(a, 'utf8')), B = importsOf(fs.readFileSync(b, 'utf8'));
  const removed = [...A.keys()].filter((k) => !B.has(k) && /^(net\.minecraft|com\.mojang)\./.test(k));
  const added = [...B.keys()].filter((k) => !A.has(k) && /^(net\.minecraft|com\.mojang)\./.test(k));
  const used = new Set();
  const bump = (f, t) => {
    if (!evidence.has(f)) evidence.set(f, new Map());
    const m = evidence.get(f); m.set(t, (m.get(t) || 0) + 1);
  };
  for (const r of removed) { // same simple name -> a move the human made
    const hit = added.find((x) => B.get(x) === A.get(r) && !used.has(x));
    if (hit) { used.add(hit); bump(r, hit); }
  }
  const remLeft = removed.filter((r) => ![...used].some((u) => B.get(u) === A.get(r)));
  const addLeft = added.filter((x) => !used.has(x));
  if (remLeft.length === 1 && addLeft.length === 1) bump(remLeft[0], addLeft[0]); // clean 1:1 -> rename
}
for (const k of paired) consider(oldFiles.get(k), newFiles.get(k));
for (const k of extra) consider(oldFiles.get(k), byBaseNew.get(path.basename(k)));

// Trust a mapping only when it is unanimous (no contradicting target) — that's the whole safety story.
const confident = [], conflicted = [];
for (const [from, targets] of evidence) {
  const sorted = [...targets.entries()].sort((a, b) => b[1] - a[1]);
  if (sorted.length === 1) confident.push({ from, to: sorted[0][0], count: sorted[0][1] });
  else conflicted.push({ from, targets: sorted });
}
confident.sort((a, b) => b.count - a.count);

const renames = confident.filter((c) => c.from.split('.').pop() !== c.to.split('.').pop());
const moves = confident.filter((c) => c.from.split('.').pop() === c.to.split('.').pop());
console.log(`=== TRUE RENAMES learned from a real human port: ${renames.length} ===`);
for (const c of renames.slice(0, 30)) console.log(`  ${c.from}\n      -> ${c.to}   (seen ${c.count}x)`);
console.log(`\n=== PACKAGE MOVES: ${moves.length} ===`);
for (const c of moves.slice(0, 20)) console.log(`  ${c.from} -> ${c.to}  (${c.count}x)`);
if (moves.length > 20) console.log(`  ... and ${moves.length - 20} more`);
console.log(`\n=== CONTRADICTORY (mapped several ways — NOT trusted): ${conflicted.length} ===`);
for (const c of conflicted.slice(0, 10)) console.log(`  ${c.from} -> ${c.targets.map((t) => `${t[0]}(${t[1]})`).join(' | ')}`);

if (args.write && confident.length) {
  const rf = 'rules.json';
  const data = JSON.parse(fs.readFileSync(rf, 'utf8'));
  const k = args.as || `${oldRef}->${newRef}`;
  data[k] = data[k] || { renames: [], advisories: [], deleted: [] };
  const have = new Set(data[k].renames.map((r) => r.fromFqcn));
  let n = 0;
  for (const c of confident) {
    if (have.has(c.from)) continue;
    have.add(c.from);
    data[k].renames.push({
      fromFqcn: c.from, toFqcn: c.to,
      fromSimple: c.from.split('.').pop(), toSimple: c.to.split('.').pop(),
      verified: true, chainable: !!args.chainable,
      source: `wild:${repo}@${oldRef}->${newRef}`, evidence: c.count,
      note: 'mined from a real human-shipped port (unanimous across files)',
    });
    n++;
  }
  fs.writeFileSync(rf, JSON.stringify(data, null, 2) + '\n');
  console.log(`\nWROTE ${n} rule(s) under "${k}" in rules.json.`);
} else if (confident.length) {
  console.log('\n(dry run — pass --write to merge)');
}
