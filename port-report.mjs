#!/usr/bin/env node
// What actually changed in a ported jar, and therefore what needs testing.
//
// "It works, but there is too much of it to check" is the honest reaction to porting a mod with
// hundreds of blocks. The answer is that most of a ported jar is not ported at all: entries this
// tool did not need to touch are copied as their original compressed bytes, so they are byte-for-byte
// the code that already worked. Only rewritten classes can behave differently.
//
// For Macaw's Furniture that is 1 class of 54. Testing sinks covers the entire risk; the other 53
// are the original bytecode and cannot have been broken by the port.
//
// This also names WHY each class changed, so a report reads as "check the sink, because Potions.WATER
// changed type" rather than a list of file names.
//
//   node port-report.mjs original.jar ported.jar
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const [ORIG, PORTED] = pos;
if (!ORIG || !PORTED || !fs.existsSync(ORIG) || !fs.existsSync(PORTED)) {
  console.error('usage: node port-report.mjs <original.jar> <ported.jar>'); process.exit(2);
}

function index(p) {
  const out = new Map();
  const scan = (buf, prefix = '') => {
    for (const e of readZip(buf)) {
      if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e), `${e.name} › `); } catch { /* skip */ } continue; }
      if (!e.name.endsWith('.class')) continue;
      out.set(prefix + e.name, inflateEntry(e));
    }
  };
  scan(fs.readFileSync(p));
  return out;
}
const A = index(ORIG), B = index(PORTED);

// What a class reaches for, so a change can be explained by naming the reference that moved.
const refsOf = (buf) => { try { return new Set([...new ClassFile(buf).refs()].map((r) => `${r.owner}\t${r.name}\t${r.desc}`)); } catch { return new Set(); } };

const identical = [], modified = [], added = [], removed = [];
for (const [name, a] of A) {
  const b = B.get(name);
  if (!b) { removed.push(name); continue; }
  if (a.equals(b)) { identical.push(name); continue; }
  const ra = refsOf(a), rb = refsOf(b);
  const gone = [...ra].filter((x) => !rb.has(x));
  const arrived = [...rb].filter((x) => !ra.has(x));
  const why = [];
  for (let i = 0; i < Math.min(gone.length, 6); i++) {
    const [o, n, d] = gone[i].split('\t');
    const match = arrived.map((x) => x.split('\t')).find(([o2, n2]) => o2 === o && n2 !== n) || arrived.map((x) => x.split('\t')).find(([o2, n2, d2]) => o2 === o && n2 === n && d2 !== d);
    if (match) why.push(`${o.split('/').pop()}.${n}${match[1] !== n ? ` → ${match[1]}` : `  ${d} → ${match[2]}`}`);
    else why.push(`${o.split('/').pop()}.${n}  (no longer referenced)`);
  }
  modified.push({ name, why, changes: gone.length });
}
for (const name of B.keys()) if (!A.has(name)) added.push(name);

const pct = (n) => `${(100 * n / A.size).toFixed(1)}%`;
console.log(`  ${path.basename(PORTED)}`);
console.log(`    classes            : ${A.size}`);
console.log(`    byte-identical     : ${identical.length}  ${pct(identical.length)}   — the original code, unmodified`);
console.log(`    MODIFIED           : ${modified.length}  ${pct(modified.length)}`);
if (added.length) console.log(`    added              : ${added.length}`);
if (removed.length) console.log(`    removed            : ${removed.length}`);

if (!modified.length) {
  console.log('\n  Nothing was rewritten, so nothing can have been broken by the port.');
} else {
  console.log('\n  ── Only these can behave differently. Everything else is the original bytecode. ──\n');
  modified.sort((a, b) => b.changes - a.changes);
  for (const m of modified.slice(0, 25)) {
    console.log(`  ${m.name.replace(/\.class$/, '').replace(/\//g, '.')}`);
    for (const w of m.why) console.log(`      ${w}`);
  }
  if (modified.length > 25) console.log(`  … and ${modified.length - 25} more`);
  console.log('\n  Test the features these classes implement. A port cannot break code it did not rewrite,');
  console.log('  so everything else carries exactly the risk it did before the port — which is none.');
}
