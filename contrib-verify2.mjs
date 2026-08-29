#!/usr/bin/env node
// Gatekeeper for pooled facts. Trust nothing in a submission except the names in it; re-establish
// every claim against the real jars for both versions.
//
// The condition for accepting a fact is that the recipient can re-derive it. That is why the packer
// refuses guesses: a guess cannot be checked here, so it cannot be pooled, however good it is.
//
// What CAN be proved:
//   the target member exists in the target version           — checkable, and fatal if false
//   the source member existed in the source version          — checkable
//   the source member is genuinely gone from the target      — checkable
//   a relocation's host really declares it, reachable by the named field of the named type
//
// What CANNOT be proved, and must not be pretended:
//   that an injection point is the SEMANTICALLY right hook. Existence is checkable; intent is not.
//   Those are accepted only on independent agreement, and stay a tier below jar-derived facts.
//
//   node contrib-verify2.mjs contribution.json --classpath <target> --source-classpath <source>
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['quiet']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const FILE = pos[0];
if (!FILE || !fs.existsSync(FILE)) { console.error('usage: node contrib-verify2.mjs <contribution.json> --classpath ... --source-classpath ...'); process.exit(2); }
const MIN_MODS = +(args.min || 2);

function index(cpStr) {
  const out = new Map();
  for (const j of (cpStr || '').split(':')) {
    if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
    for (const e of readZip(fs.readFileSync(j))) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const d = new ClassFile(inflateEntry(e)).declared();
        if (d.name) out.set(d.name, d.members);
      } catch { /* skip */ }
    }
  }
  return out;
}
const target = index(args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : ''));
const source = index(args['source-classpath']);
if (!target.size) { console.error('need --classpath: a fact that cannot be re-checked cannot be accepted'); process.exit(2); }

const has = (idx, cls, name, desc) => (idx.get(cls) || []).some((m) => m.name === name && (!desc || m.desc === desc));
const hasName = (idx, cls, name) => (idx.get(cls) || []).some((m) => m.name === name);

const sub = JSON.parse(fs.readFileSync(FILE, 'utf8'));
const accept = { members: [], relocations: [], hooks: [] };
const reject = [], held = [];
const R = (what, why) => reject.push([what, why]);

// ── member renames ───────────────────────────────────────────────────────────────────────────
for (const r of sub.facts?.members || []) {
  const what = `${r.owner.split('/').pop()}.${r.name} → ${r.becomes}`;
  if (!/^[\w/$]+$/.test(r.owner) || !/^[\w$<>]+$/.test(r.name) || !/^[\w$<>]+$/.test(r.becomes)) { R(what, 'malformed name'); continue; }
  if (!target.has(r.owner)) { held.push([what, `${r.owner.split('/').pop()} is not in the target jars here`]); continue; }
  if (!has(target, r.owner, r.becomes, r.desc)) { R(what, `${r.becomes} does not exist on that class in the target`); continue; }
  if (has(target, r.owner, r.name, r.desc)) { R(what, `${r.name} is still there — nothing was renamed`); continue; }
  if (source.size && !has(source, r.owner, r.name, r.desc)) { R(what, `${r.name} was not on that class in the source version either`); continue; }
  accept.members.push(r);
}

// ── relocations: the member moved to a delegate reachable by one field ───────────────────────
for (const r of sub.facts?.relocations || []) {
  const what = `${r.owner.split('/').pop()}.${r.name} → .${r.via}.${r.name}`;
  if (!target.has(r.owner) || !target.has(r.host)) { held.push([what, 'one of the classes is not in the target jars here']); continue; }
  if (!has(target, r.host, r.name, r.desc)) { R(what, `${r.host.split('/').pop()} does not declare ${r.name}`); continue; }
  if (has(target, r.owner, r.name, r.desc)) { R(what, 'the original still declares it — it did not move'); continue; }
  // The route has to be real: a field of exactly the host's type on the original class.
  const field = (target.get(r.owner) || []).find((m) => m.kind === 'field' && m.name === r.via);
  if (!field) { R(what, `${r.owner.split('/').pop()} has no field called ${r.via}`); continue; }
  if (field.desc !== `L${r.host};`) { R(what, `field ${r.via} is ${field.desc}, not L${r.host};`); continue; }
  accept.relocations.push(r);
}

// ── injection points: existence is provable, correctness is not ──────────────────────────────
for (const r of sub.facts?.hooks || []) {
  const what = `${r.owner.split('/').pop()}.${r.name} → ${r.becomes}`;
  if (!target.has(r.owner)) { held.push([what, 'target class not present here']); continue; }
  if (!hasName(target, r.owner, r.becomes)) { R(what, `${r.becomes} is not a member of that class in the target`); continue; }
  if (hasName(target, r.owner, r.name)) { R(what, `${r.name} still exists — the hook was not deleted`); continue; }
  if (source.size && !hasName(source, r.owner, r.name)) { R(what, 'the old hook never existed in the source version'); continue; }
  // Existence proved. Whether it is the RIGHT hook is a judgement, so require agreement.
  const mods = (r.mods || []).length;
  if (r.corroborated === false || mods < MIN_MODS) { held.push([what, `only ${mods || 1} mod chose this — needs ${MIN_MODS} independent`]); continue; }
  accept.hooks.push(r);
}

const n = accept.members.length + accept.relocations.length + accept.hooks.length;
console.log(`  ${path.basename(FILE)}`);
console.log(`    submitted : ${(sub.facts?.members || []).length} members, ${(sub.facts?.relocations || []).length} relocations, ${(sub.facts?.hooks || []).length} hooks, ${(sub.questions || []).length} questions`);
console.log(`    ACCEPT    : ${n}   (${accept.members.length} members, ${accept.relocations.length} relocations, ${accept.hooks.length} hooks)`);
console.log(`    REJECT    : ${reject.length}`);
for (const [w, why] of reject.slice(0, 10)) console.log(`      ✗ ${w}   (${why})`);
if (reject.length > 10) console.log(`      … ${reject.length - 10} more`);
console.log(`    HELD      : ${held.length}   (not disproved — could not be checked here, or needs corroboration)`);
for (const [w, why] of held.slice(0, 5)) console.log(`      · ${w}   (${why})`);

// Questions carry no claim, so nothing to verify — they are requests, and they pass through.
if ((sub.questions || []).length) console.log(`    questions : ${sub.questions.length} passed through — these are asks, not claims`);

if (n) fs.writeFileSync(args.out || 'accepted-facts.json', JSON.stringify({ schema: 2, facts: accept, questions: sub.questions || [] }, null, 1) + '\n');
console.log(n ? `\n  wrote ${args.out || 'accepted-facts.json'} — merge only these` : '\n  nothing accepted');
process.exit(reject.length ? 1 : 0);
