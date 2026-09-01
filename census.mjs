#!/usr/bin/env node
// Where does generality actually get lost? Classify every failure across a set of real mods.
//
// "Works for a few mods" and "works for almost any mod" differ by knowing WHICH obstacle stops the
// median mod, and that had been guessed at from a sample of eighteen. This ports a set, then sorts
// every remaining failure into the category that decides who can fix it: the tables, a bytecode
// rewrite, an AI pass, or nobody.
//
//   node census.mjs --dir ~/foxgrade-work/census --from 26.1 --to 26.2 --classpath "..." --corpus ...
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const DIR = args.dir, FROM = args.from || '26.1', TO = args.to || '26.2';
if (!DIR || !fs.existsSync(DIR)) { console.error('usage: node census.mjs --dir <jars> --from 26.1 --to 26.2 --classpath "..."'); process.exit(2); }
const OUT = args.out || path.join(DIR, 'ported');
fs.mkdirSync(OUT, { recursive: true });
const run = (a) => spawnSync('node', a, { encoding: 'utf8', maxBuffer: 128e6 }).stdout || '';

const jars = fs.readdirSync(DIR).filter((f) => f.endsWith('.jar'));
const tally = new Map(), owners = new Map(), perMod = [];
const bump = (k) => tally.set(k, (tally.get(k) || 0) + 1);

for (const f of jars) {
  const name = f.replace(/@.*$/, '');
  // One driver, one artifact. The suffix-chained stages this replaces produced two wrong
  // measurements in one afternoon, both from reading a stale stage file.
  const finalJar = path.join(OUT, `${name}.final.jar`);
  const pipeOut = run(['port-pipeline.mjs', path.join(DIR, f), '--from', FROM, '--to', TO, '--classpath', args.classpath,
       ...(args.source ? ['--source', args.source] : []), '--out', finalJar, '--work', OUT]);
  if (!fs.existsSync(finalJar)) { perMod.push({ name, state: 'pipeline failed' }); bump('pipeline failed'); continue; }
  // The pipeline's verifier gate prints its verdict and the census used to ignore it — which is how
  // a jar the JVM rejects at runtime was counted CLEAN and reached a play session before anything
  // objected. A verify regression outranks every other measurement.
  if (/REGRESSION:/.test(pipeOut)) { perMod.push({ name, state: 'VERIFY REGRESSED' }); bump('~VERIFY REGRESSED — port broke bytecode'); continue; }

  const v = run(['jar-verify.mjs', finalJar, '--classpath', args.classpath, ...(args.corpus ? ['--corpus', args.corpus] : []), '--from', FROM, '--to', TO]);
  const m = run(['mixin-check.mjs', finalJar, '--classpath', args.classpath, ...(args.source ? ['--source-classpath', args.source] : [])]);
  const links = +(v.match(/^\s*(\d+) link\(s\) will fail/m)?.[1] ?? (/every link resolves/.test(v) ? 0 : NaN));
  const mix = /no mixins in this jar/.test(m) ? 0 : +(m.match(/PROBLEMS\s*:\s*(\d+)/)?.[1] ?? NaN);
  if (Number.isNaN(links) || Number.isNaN(mix)) { perMod.push({ name, state: 'NOT MEASURED' }); bump('not measured'); continue; }

  for (const line of v.split('\n')) {
    if (!/^\s+✗/.test(line)) continue;
    if (/\(\d+ links?\)$/.test(line)) bump('link: class gone (renamed+redesigned, or deleted)');
    else {
      bump('link: member gone');
      const owner = line.match(/✗\s+([\w.$]+)\.[\w$<>]+\s/)?.[1];
      if (owner) owners.set(owner, (owners.get(owner) || 0) + 1);
    }
  }
  const reloc = +(v.match(/\((\d+) of them relocated/)?.[1] || 0);
  if (reloc) tally.set('link: RELOCATED (we know the destination)', (tally.get('link: RELOCATED (we know the destination)') || 0) + reloc);
  for (const line of m.split('\n')) {
    if (!/^\s+✗/.test(line)) continue;
    if (/does not exist in the target version/.test(line)) bump('mixin: target class gone');
    else if (/was renamed to/.test(line)) bump('mixin: injection point RENAMED (rewritable)');
    else if (/moved to/.test(line)) bump('mixin: target MOVED (retargetable)');
    else bump('mixin: injection point gone (needs a decision)');
  }
  const state = links === 0 && mix === 0 ? 'CLEAN' : mix === 0 ? 'links only' : 'mixins blocked';
  perMod.push({ name, state, links, mix });
  if (state === 'CLEAN') bump('~mods fully clean');
}

perMod.sort((a, b) => (a.links ?? 999) - (b.links ?? 999));
console.log(`\n  ${jars.length} mods, ${FROM} -> ${TO}\n`);
for (const p of perMod) console.log(`    ${String(p.links ?? '-').padStart(4)} links  ${String(p.mix ?? '-').padStart(4)} mixins   ${p.name}  ${p.state === 'CLEAN' ? '✓ CLEAN' : ''}`);
const clean = perMod.filter((p) => p.state === 'CLEAN').length;
const linksOnly = perMod.filter((p) => p.state === 'links only').length;
console.log(`\n  fully clean : ${clean}/${jars.length}`);
console.log(`  no mixin blocker (would load, may misbehave): ${clean + linksOnly}/${jars.length}`);
console.log('\n  ── where the failures are ──');
for (const [k, n] of [...tally].sort((a, b) => b[1] - a[1])) console.log(`    ${String(n).padStart(5)}  ${k}`);
console.log('\n  ── which classes lost members (a pattern here is a rule we are missing) ──');
for (const [k, n] of [...owners].sort((a, b) => b[1] - a[1]).slice(0, 18)) console.log(`    ${String(n).padStart(4)}  ${k}`);
