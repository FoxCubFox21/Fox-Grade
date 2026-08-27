#!/usr/bin/env node
// Promote mod-specific observations into a real version-ladder block.
//
// Rules harvested from a human port land in a block named for that mod ("Jade:1.21.9->1.21.11").
// The resolver only walks blocks named "<ver>-><ver>", so those observations sit there unused — the
// ResourceLocation -> Identifier rename was recorded twice and still never applied, because no
// ladder block covers the 1.21.1 -> 26.x hop at all.
//
// Promotion is deliberately stricter than harvesting, since a ladder block applies to every port
// rather than one mod:
//   • a RENAME (simple name changes) needs two independent mods to agree;
//   • a MOVE (same simple name, new package) may come from one mod, because the invariant name plus
//     a destination that verifiably exists is already strong — this is the same reasoning that lets
//     the resolver chain moves but not renames;
//   • either way the destination must exist in the real target jars, and the old name must NOT,
//     since a class that is still present was not renamed to anything.
//
//   node promote-ladder.mjs --from 1.21.1 --to 26.2 --classpath "<target jars>"
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const FROM = args.from || '1.21.1', TO = args.to || '26.2';
const APPLY = process.argv.includes('--apply');

const verOf = (s) => { const m = String(s).match(/(\d+)\.(\d+)(?:\.(\d+))?/); return m ? [+m[1], +m[2], +(m[3] || 0)] : null; };
const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
const fv = verOf(FROM);

const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
const real = new Set();
for (const j of cp.split(':')) {
  if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
  const r = spawnSync('unzip', ['-Z1', j, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status !== 0 || !r.stdout) continue;
  for (const l of r.stdout.split('\n')) { const p = l.trim(); if (p.endsWith('.class')) real.add(p.slice(0, -6).replace(/\//g, '.')); }
}
if (!real.size) { console.error('need --classpath: promotion without checking the destination is how bad rules spread'); process.exit(2); }

const RULES = path.join(HERE, 'rules.json');
const data = JSON.parse(fs.readFileSync(RULES, 'utf8'));

// Gather every observation from mod-specific blocks whose hop LANDS after our starting version.
const votes = new Map();   // "from -> to" -> Set(project)
for (const [k, v] of Object.entries(data)) {
  if (k.startsWith('_') || k.startsWith('scheme:') || k.startsWith('members:')) continue;
  if (/^[\d.]+(->[\d.]+)?$/.test(k)) continue;              // already a ladder or target block
  const m = k.match(/->([\d.]+)/);
  if (!m) continue;
  const landed = verOf(m[1]);
  if (!landed || cmp(landed, fv) <= 0) continue;            // nothing new past where the ladder ends
  const project = k.split(':')[0];
  for (const r of (v.renames || [])) {
    const key = `${r.fromFqcn}\t${r.toFqcn}`;
    if (!votes.has(key)) votes.set(key, new Set());
    votes.get(key).add(project);
  }
}

const kept = [], rejected = [], thin = [];
const byFrom = new Map();
for (const [key, mods] of votes) {
  const [from, to] = key.split('\t');
  const isMove = from.split('.').pop() === to.split('.').pop();
  if (!real.has(to)) { rejected.push([from, to, `${to.split('.').pop()} is not in ${TO}`]); continue; }
  // If the old name still exists in the target, nothing was renamed — this is a mod moving between
  // two classes that both exist, not a version change.
  if (real.has(from)) { rejected.push([from, to, 'the old class still exists in ' + TO]); continue; }
  if (!isMove && mods.size < 2) { thin.push([from, to, [...mods][0]]); continue; }
  if (!byFrom.has(from)) byFrom.set(from, []);
  byFrom.get(from).push({ from, to, isMove, mods });
}
for (const [from, cands] of byFrom) {
  if (cands.length > 1) { rejected.push([from, cands.map((c) => c.to).join(' | '), 'sources disagree about the destination']); continue; }
  const c = cands[0];
  kept.push({
    fromFqcn: c.from, toFqcn: c.to, fromSimple: c.from.split('.').pop(), toSimple: c.to.split('.').pop(),
    verified: true, kind: c.isMove ? 'move' : 'rename', ...(c.isMove ? { chainable: true } : {}),
    source: 'promoted-groundtruth',
    evidence: `${c.mods.size} port${c.mods.size > 1 ? 's' : ''}: ${[...c.mods].join(', ')}; destination verified in ${TO}`,
  });
}

console.log(`  observations past ${FROM} : ${votes.size}`);
console.log(`  PROMOTE                 : ${kept.length}`);
console.log(`  rejected                : ${rejected.length}`);
console.log(`  renames with one source : ${thin.length}  (held: a rename needs corroboration)`);
for (const k of kept) console.log(`    ✓ ${k.fromFqcn}\n        → ${k.toFqcn}   [${k.kind}] ${k.evidence}`);
for (const [f, t, why] of rejected.slice(0, 8)) console.log(`    ✗ ${f} → ${t}   (${why})`);
for (const [f, t, m] of thin.slice(0, 6)) console.log(`    · ${f} → ${t}   (only ${m})`);

if (!kept.length) { console.log('\n  nothing to promote'); process.exit(0); }
const BLOCK = `${FROM}->${TO}`;
if (!APPLY) { console.log(`\n  dry run — --apply writes these into the "${BLOCK}" block`); process.exit(0); }

if (!data[BLOCK]) data[BLOCK] = { renames: [], advisories: [], deleted: [] };
const have = new Set((data[BLOCK].renames || []).map((r) => r.fromFqcn));
let added = 0;
for (const k of kept) if (!have.has(k.fromFqcn)) { data[BLOCK].renames.push(k); added++; }
const out = JSON.stringify(data, null, 2) + '\n';
JSON.parse(out);
fs.copyFileSync(RULES, RULES + '.bak');
fs.writeFileSync(RULES + '.tmp', out);
fs.renameSync(RULES + '.tmp', RULES);
console.log(`\n  added ${added} rule(s) to "${BLOCK}"  (backup: rules.json.bak)`);
