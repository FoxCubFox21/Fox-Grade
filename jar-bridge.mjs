#!/usr/bin/env node
// Ship a compat layer inside the ported jar, and point the mod's call sites at it.
//
// The census found the ceiling is not knowledge: of 131 dead member references across 40 mods, most
// are two changes we already understand — the Minecraft/Gui/LevelRenderer relocation cluster, and
// Blocks/Items collapsing into ColorCollection. We know the answer for every one and could not apply
// it, because a relocation is a different INSTRUCTION and the remapper only edits names.
//
// It is our build of the mod, so it can carry our code. For each relocation this generates a static
// method implementing the old shape in terms of the new one, compiles it against the target jars,
// and redirects the call site to it — a same-size, same-stack-effect substitution, so nothing moves.
//
//   node jar-bridge.mjs ported.jar --from 26.1 --to 26.2 --classpath "..." --out fixed.jar
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';
import { retargetCallSites } from './bytecode.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
if (!JAR || !args.classpath) { console.error('usage: node jar-bridge.mjs <jar> --from 26.1 --to 26.2 --classpath "..." --out out.jar'); process.exit(2); }
const FROM = args.from || '26.1', TO = args.to || '26.2';
const OUT = args.out || JAR.replace(/\.jar$/, '.bridged.jar');

// A JVM descriptor back into source-level Java, so the compat method can actually be written out.
function javaType(d) {
  let dims = 0; while (d[dims] === '[') dims++;
  const base = d.slice(dims);
  const prim = { V: 'void', I: 'int', J: 'long', F: 'float', D: 'double', Z: 'boolean', B: 'byte', C: 'char', S: 'short' }[base];
  const name = prim || base.slice(1, -1).replace(/\//g, '.');
  return name + '[]'.repeat(dims);
}
const paramsOf = (desc) => {
  const out = []; let i = 1;
  while (desc[i] !== ')') { let s = i; while (desc[i] === '[') i++; if (desc[i] === 'L') i = desc.indexOf(';', i); out.push(desc.slice(s, i + 1)); i++; }
  return out;
};
const returnOf = (desc) => desc.slice(desc.indexOf(')') + 1);

// Every relocation we know about, from the mined table.
const table = path.join(HERE, `members.${FROM}-${TO}.json`);
if (!fs.existsSync(table)) { console.error(`no members.${FROM}-${TO}.json to work from`); process.exit(2); }
const relocations = JSON.parse(fs.readFileSync(table, 'utf8')).relocations || [];
const byKey = new Map(relocations.map((r) => [`${r.owner}\t${r.name}\t${r.desc}`, r]));

// 26.2 collapsed the sixteen per-colour constants into one record. Blocks.RED_BED is gone; there is
// a ColorCollection called Blocks.BED with a component accessor per colour, so the value now lives at
// Blocks.BED.red(). registry-mine was right to report the old name as removed rather than invent a
// destination for it — the destination is not a constant at all, it is a method call, which is
// exactly what a compat layer can express and a renamer cannot.
const COLOURS = [['WHITE','white'],['ORANGE','orange'],['MAGENTA','magenta'],['LIGHT_BLUE','lightBlue'],
  ['YELLOW','yellow'],['LIME','lime'],['PINK','pink'],['GRAY','gray'],['LIGHT_GRAY','lightGray'],
  ['CYAN','cyan'],['PURPLE','purple'],['BLUE','blue'],['BROWN','brown'],['GREEN','green'],
  ['RED','red'],['BLACK','black']];
const COLOR_COLLECTION = 'Lnet/minecraft/world/level/block/ColorCollection;';

// javap once per owner: what does the TARGET declare, and with which descriptor.
const declCache = new Map();
function targetFields(owner) {
  if (declCache.has(owner)) return declCache.get(owner);
  const r = spawnSync('javap', ['-p', '-s', '-cp', args.classpath, owner.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 64e6 });
  const out = new Map();
  const lines = (r.stdout || '').split('\n');
  for (let i = 0; i < lines.length; i++) {
    const d = lines[i].match(/^\s*descriptor:\s*(\S+)\s*$/);
    if (!d) continue;
    const nm = (lines[i - 1] || '').match(/([\w$]+);?\s*$/);
    if (nm && !/\(/.test(lines[i - 1])) out.set(nm[1], d[1]);
  }
  declCache.set(owner, out);
  return out;
}

// owner.NAME -> owner.BASE.colour(), when NAME is <COLOUR>_<BASE> and BASE is a ColorCollection.
function colourFix(owner, name, desc) {
  if (desc.startsWith('(')) return null;                 // a method, not a constant
  const fields = targetFields(owner);
  if (!fields.size || fields.has(name)) return null;     // still present: nothing to fix
  for (const [CONST, accessor] of COLOURS) {
    if (!name.startsWith(CONST + '_')) continue;
    const base = name.slice(CONST.length + 1);
    if (fields.get(base) !== COLOR_COLLECTION) continue;
    return { expr: `${owner.replace(/\//g, '.')}.${base}.${accessor}()`, ret: desc };
  }
  return null;
}

// Which of them does this jar actually reach for?
const needed = new Map();
const colourNeeded = new Map();
const colourSeen = new Set();
const scan = (buf) => {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    try { for (const r of new ClassFile(inflateEntry(e)).refs()) {
      const k = `${r.owner}\t${r.name}\t${r.desc}`;
      if (byKey.has(k)) { needed.set(k, byKey.get(k)); continue; }
      if (colourSeen.has(k)) continue;
      colourSeen.add(k);
      if (!r.owner.startsWith('net/minecraft/')) continue;
      const c = colourFix(r.owner, r.name, r.desc);
      if (c) colourNeeded.set(k, c);
    } } catch { /* skip */ }
  }
};
const jarBuf = fs.readFileSync(JAR);
scan(jarBuf);
console.log(`  ${path.basename(JAR)}`);
console.log(`    relocations known   : ${relocations.length}`);
console.log(`    reached by this jar : ${needed.size} relocation(s), ${colourNeeded.size} colour constant(s)`);
if (!needed.size && !colourNeeded.size) { console.log('    nothing to bridge.'); process.exit(0); }

// Generate one static method per relocation.
const sigs = [];
let n = 0;
for (const [key, r] of needed) {
  const isField = !r.desc.startsWith('(');
  const ret = isField ? r.desc : returnOf(r.desc);
  const ps = isField ? [] : paramsOf(r.desc);
  const name = `b${n++}`;
  const argDecl = [`${javaType('L' + r.owner + ';')} self`, ...ps.map((p, i) => `${javaType(p)} a${i}`)].join(', ');
  const call = r.becomes ? `self.${r.via}.${r.becomes.replace(/\(\)$/, '()')}` : `self.${r.via}.${r.name}(${ps.map((_, i) => `a${i}`).join(', ')})`;
  const body = ret === 'V' ? `${call};` : `return ${call};`;
  sigs.push({ key, r, name, desc: `(L${r.owner};${ps.join('')})${ret}`,
    src: `  public static ${javaType(ret)} ${name}(${argDecl}) { ${body} }` });
}

for (const [key, c] of colourNeeded) {
  const name = `b${n++}`;
  sigs.push({ key, name, desc: `()${c.ret}`,
    src: `  public static ${javaType(c.ret)} ${name}() { return ${c.expr}; }` });
}

const dir = fs.mkdtempSync('/tmp/foxgrade-bridge-');
const write = (list) => fs.writeFileSync(path.join(dir, 'Compat.java'),
  `package foxgrade;\npublic final class Compat {\n  private Compat() {}\n${list.map((s) => s.src).join('\n')}\n}\n`);

// Not every relocation compiles: a delegate field can be private, or the destination may need an
// argument we do not have. Rather than refuse the whole jar for one of them, drop what javac
// rejects and keep the rest — a partial bridge still removes real breakage.
let keep = sigs;
for (let round = 0; round < 4 && keep.length; round++) {
  write(keep);
  const r = spawnSync('javac', ['-nowarn', '-proc:none', '-cp', args.classpath, '-d', dir, path.join(dir, 'Compat.java')], { encoding: 'utf8', maxBuffer: 64e6 });
  if (r.status === 0) break;
  const bad = new Set();
  for (const m of (r.stderr || '').matchAll(/Compat\.java:(\d+): error/g)) bad.add(+m[1] - 3);   // 3 header lines
  const before = keep.length;
  keep = keep.filter((_, i) => !bad.has(i + 1));
  if (keep.length === before) { keep = []; break; }                 // errors we cannot attribute
  console.log(`    dropped ${before - keep.length} that would not compile`);
}
if (!keep.length) { console.log('    none of them could be compiled — jar unchanged.'); process.exit(0); }

const compiled = path.join(dir, 'foxgrade', 'Compat.class');
if (!fs.existsSync(compiled)) { console.log('    compat class did not build — jar unchanged.'); process.exit(1); }
const fix = new Map(keep.map((s) => [s.key, { owner: 'foxgrade/Compat', name: s.name, desc: s.desc }]));

// Rewrite the call sites.
// Fabric mods routinely ship their libraries nested in META-INF/jars, and for 3dskinlayers every
// single relocation reference lived in one — a rewrite that only walked the top level found nothing
// to do and reported success. So this recurses, rebuilding each bundled jar that needed changes.
// The compat class goes into every jar that references it, because a nested jar cannot be assumed to
// see a class that only exists in its parent.
const compatBytes = fs.readFileSync(compiled);
let touched = 0, jarsTouched = 0;

function bridgeJar(buf) {
  const entries = [], replacements = new Map();
  let changed = false;
  for (const e of readZip(buf)) {
    entries.push(e);
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) {
      try {
        const inner = bridgeJar(inflateEntry(e));
        if (inner) { replacements.set(e.name, inner); changed = true; }
      } catch { /* an unreadable bundle is left exactly as it was */ }
      continue;
    }
    if (!e.name.endsWith('.class')) continue;
    try {
      const out = retargetCallSites(ClassFile, inflateEntry(e), ({ owner, name, desc }) => fix.get(`${owner}\t${name}\t${desc}`) || null);
      if (out) { replacements.set(e.name, out); touched++; changed = true; }
    } catch { /* leave the class exactly as it was */ }
  }
  if (!changed) return null;
  const proto = entries.find((e) => e.name.endsWith('.class')) || entries[0];
  entries.push({ ...proto, name: 'foxgrade/Compat.class' });
  replacements.set('foxgrade/Compat.class', compatBytes);
  jarsTouched++;
  return writeZip(entries, replacements);
}

const result = bridgeJar(jarBuf);
if (!result) { console.log('    no call site matched — jar unchanged.'); process.exit(0); }
fs.writeFileSync(OUT, result);
console.log(`    bridged ${keep.length} relocation(s): ${touched} class(es) rewritten across ${jarsTouched} jar(s)`);
console.log(`  wrote ${OUT}`);
