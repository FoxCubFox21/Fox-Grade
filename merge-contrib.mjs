#!/usr/bin/env node
// Fold verified community contributions into rules.json.
//
// verify-contrib.mjs decides what is ACCEPTABLE. This decides what actually LANDS. They are separate
// steps because the rule set moves in between: an anti-fact may have been added, or an authoritative
// mapping may now contradict a claim that was fine last week. So the merge re-asks the whole question
// against the current rules.json rather than trusting a verdict recorded earlier. Both use the same
// gate (contrib-gate.mjs), so neither can be more permissive than the other by accident.
//
//   node merge-contrib.mjs                          # dry run against accepted-contrib.json
//   node merge-contrib.mjs a.json b.json --apply    # merge several PRs at once
//   node merge-contrib.mjs --undo --apply           # remove every community rule ever merged
//
// Merged rules are tagged  source: "community:<proof>"  and that prefix is load-bearing twice:
//   1. export-learned.mjs matches proofs by PREFIX, so "community:*" matches none of them and is
//      never re-exported. A bad rule that slips through therefore cannot be laundered into looking
//      independently corroborated by N installs all re-asserting it.
//   2. --undo uses it to find exactly what came from outside.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { indexRules, indexJars, judge } from './contrib-gate.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['apply', 'undo', 'no-chain', 'strict', 'allow-unchecked']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const RULES = args.rules || path.join(HERE, 'rules.json');
const APPLY = !!args.apply;

if (!fs.existsSync(RULES)) { console.error(`no ruleset at ${RULES}`); process.exit(2); }
const data = JSON.parse(fs.readFileSync(RULES, 'utf8'));

// ── write path ────────────────────────────────────────────────────────────────────────────────
// rules.json round-trips byte-for-byte through JSON.stringify(…, null, 2) + "\n", so a merge shows
// up in git as only the lines it added — not a 23 MB reformat.
function countRules(d) {
  let n = 0;
  for (const [k, v] of Object.entries(d)) { if (k.startsWith('_')) continue; n += (v.renames || []).length; }
  return n;
}
function writeRules(d) {
  const out = JSON.stringify(d, null, 2) + '\n';
  JSON.parse(out);                            // never write something we cannot read back
  fs.copyFileSync(RULES, RULES + '.bak');     // one rolling backup
  fs.writeFileSync(RULES + '.tmp', out);
  fs.renameSync(RULES + '.tmp', RULES);       // atomic: a crash mid-write cannot truncate the ruleset
}

// ── --undo ────────────────────────────────────────────────────────────────────────────────────
if (args.undo) {
  let removed = 0, blocksDropped = 0;
  for (const [k, v] of Object.entries(data)) {
    if (k.startsWith('_') || !v.renames) continue;
    const keep = v.renames.filter((r) => !String(r.source || '').startsWith('community:'));
    removed += v.renames.length - keep.length;
    v.renames = keep;
    // a block left with nothing in it does nothing; drop it so undo really is a clean reversal
    if (!keep.length && !(v.advisories || []).length && !(v.deleted || []).length && !v.members) {
      delete data[k]; blocksDropped++;
    }
  }
  console.log(`  community rules found : ${removed}`);
  if (!APPLY) { console.log('\n  dry run — re-run with --apply to remove them'); process.exit(0); }
  if (removed) {
    writeRules(data);
    console.log(`  removed               : ${removed}  (${blocksDropped} empty block(s) dropped)`);
    console.log(`  backup                : ${path.basename(RULES)}.bak`);
  } else console.log('  nothing to undo');
  process.exit(0);
}

// ── read the submissions ──────────────────────────────────────────────────────────────────────
const files = pos.length ? pos : ['accepted-contrib.json'];
const entries = [];
let subTarget = null;
for (const f of files) {
  if (!fs.existsSync(f)) { console.error(`missing submission: ${f}`); process.exit(2); }
  let sub; try { sub = JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) { console.error(`${f}: not valid JSON — ${e.message}`); process.exit(2); }
  for (const e of (sub.entries || [])) entries.push({ ...e, file: f, block: e.block || sub.target || '26.2' });
  if (!subTarget && sub.target) subTarget = sub.target;
}
const jarVersion = args.target || subTarget || '26.2';
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
const real = indexJars(cp, spawnSync, fs);

const { accept, already, reject, held } = judge(entries, { data, index: indexRules(data), real, jarVersion });

// Held entries passed every check except the one that matters most — that the target class exists —
// because we have no jar for the version they aim at. Landing them anyway is a deliberate act.
const final = args['allow-unchecked'] ? [...accept, ...held.map((h) => entries.find((e) => e.from === h[0] && e.block === h[2]))].filter(Boolean) : accept;

// ── report ────────────────────────────────────────────────────────────────────────────────────
const before = countRules(data);
console.log(`  ruleset   : ${path.basename(RULES)}  (${before.toLocaleString()} rules)`);
console.log(`  submitted : ${entries.length} entries from ${files.length} file(s)`);
console.log(`    MERGE   : ${final.length}`);
console.log(`    already : ${already.length}`);
console.log(`    REJECT  : ${reject.length}`);
for (const [f, t, why, src] of reject.slice(0, 12)) console.log(`      ✗ ${f} → ${t}   (${why})  [${path.basename(src || '')}]`);
if (reject.length > 12) console.log(`      … ${reject.length - 12} more`);
if (held.length) {
  console.log(`    HELD    : ${held.length}  ${args['allow-unchecked'] ? '(merging anyway — --allow-unchecked)' : `(no ${jarVersion} jar covers them; --allow-unchecked to merge regardless)`}`);
}
if (!real) console.log(`\n  no jars given, so nothing could be checked for existence`);
else console.log(`\n  jar-checked against ${jarVersion}`);
const newBlocks = [...new Set(accept.filter((e) => e.blockStatus !== 'existing').map((e) => `${e.block} (${e.blockStatus})`))];
if (newBlocks.length) console.log(`  creates blocks: ${newBlocks.join(', ')}`);

// Rejects are the gate doing its job, so a batch that lands its good rules is still a success.
// --strict is for merging a single PR all-or-nothing: one bad entry and none of it goes in.
if (args.strict && reject.length) { console.log(`\n  --strict: ${reject.length} rejected, so nothing is merged`); process.exit(1); }
if (!final.length) { console.log('\n  nothing to merge'); process.exit(reject.length ? 1 : 0); }
if (!APPLY) { console.log('\n  dry run — re-run with --apply to write'); process.exit(0); }

// ── merge ─────────────────────────────────────────────────────────────────────────────────────
for (const e of final) {
  const fromSimple = e.from.split('.').pop(), toSimple = e.to.split('.').pop();
  const isMove = fromSimple === toSimple;
  if (!data[e.block]) data[e.block] = { renames: [], advisories: [], deleted: [] };
  if (!data[e.block].renames) data[e.block].renames = [];
  data[e.block].renames.push({
    fromFqcn: e.from, toFqcn: e.to, fromSimple, toSimple,
    verified: true,
    kind: isMove ? 'move' : 'rename',
    // only name-preserving moves chain: the simple name is the invariant that keeps a chain from drifting
    ...(isMove && !args['no-chain'] ? { chainable: true } : {}),
    source: `community:${e.proof}`,
    ...(e.why ? { note: e.why } : {}),
  });
}
writeRules(data);
console.log(`\n  merged  : ${final.length} rules  (${before.toLocaleString()} → ${countRules(data).toLocaleString()})`);
console.log(`  backup  : ${path.basename(RULES)}.bak`);
console.log('  tagged  : source="community:<proof>" — not re-exported, and reversible with --undo --apply');
