#!/usr/bin/env node
// Gatekeeper for community-contributed rules. Trust NOTHING in the submission except the class-name
// pair itself; re-establish every claim locally against the real target jars and our own anti-facts.
// This is what stops one broken install from poisoning everyone.
//
// The decision itself lives in contrib-gate.mjs, shared with merge-contrib.mjs — the two must never
// be able to disagree about what is trustworthy.
//
//   node verify-contrib.mjs their-rules.json --classpath "<target jars>" --target 26.2
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { indexRules, indexJars, judge } from './contrib-gate.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const file = pos[0];
if (!file) { console.error('usage: node verify-contrib.mjs <contribution.json> [--classpath ...] [--target 26.2]'); process.exit(2); }
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath to verify against the real jars'); process.exit(2); }

const real = indexJars(cp, spawnSync, fs);
if (!real) { console.error('no classes found on that classpath — nothing could be verified'); process.exit(2); }
const data = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
const sub = JSON.parse(fs.readFileSync(file, 'utf8'));
const jarVersion = args.target || sub.target || '26.2';
const entries = (sub.entries || []).map((e) => ({ ...e, block: e.block || sub.target || '26.2', file }));

const { accept, already, reject, held } = judge(entries, { data, index: indexRules(data), real, jarVersion });

console.log(`  submission: ${entries.length} entries  (checked against ${jarVersion} jars)`);
console.log(`    ACCEPT  : ${accept.length}`);
console.log(`    already : ${already.length}`);
console.log(`    REJECT  : ${reject.length}`);
for (const [f, t, why] of reject.slice(0, 10)) console.log(`      ✗ ${f} → ${t}   (${why})`);
if (reject.length > 10) console.log(`      … ${reject.length - 10} more`);
// "Held" is not "fine". These aim at a version whose jar we do not have, so the central claim —
// that the target class exists — was never tested. CI must not wave them through.
if (held.length) {
  const blocks = [...new Set(held.map((h) => h[2]))];
  console.log(`    HELD    : ${held.length}  (aim at ${blocks.slice(0, 4).join(', ')}${blocks.length > 4 ? `, +${blocks.length - 4} more` : ''})`);
  console.log('              re-run with those versions\' jars to verify them');
  fs.writeFileSync('quarantined-contrib.json', JSON.stringify({ jarVersion, entries: held.map(([from, to, block]) => ({ from, to, block })) }, null, 1));
}
if (accept.length) fs.writeFileSync('accepted-contrib.json', JSON.stringify({ target: jarVersion, entries: accept }, null, 1));
console.log(accept.length ? '\n  wrote accepted-contrib.json — merge only these' : '\n  nothing accepted');
process.exit(reject.length ? 1 : 0);   // non-zero fails CI
