#!/usr/bin/env node
// Last-resort structural surgery, loud and receipted — the same bargain as --last-resort.
//
// Some breaks are not renames, relocations or recipes: a super that became an interface kills the
// class at LOAD time, and an abstract the mod never implemented kills it at first call. The faithful
// fix is a redesign. The honest mechanical fallback, opted into with --allow-shims, is:
//
//   · extends <now-an-interface>  ->  extends Object + implements it, <init> repointed to Object
//     (resolves because Object.<init> shares the ()V descriptor)
//   · unimplemented abstract      ->  a synthesized method that THROWS UnsupportedOperationException
//     naming the mod, the method and this tool — a crash you can trace, at the exact call, instead
//     of IncompatibleClassChangeError at load or silence
//
// Every shim is written into SHIMMED.md inside the jar. A feature that dies must die loudly and on
// the record.
//
//   node jar-shim.mjs mod.jar --classpath "..." --out out.jar --allow-shims
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';
import { transformClass } from './bytecode.mjs';

const FLAGS = new Set(['allow-shims']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (!t.startsWith('--')) { pos.push(t); continue; } const k = t.slice(2); args[k] = FLAGS.has(k) ? true : process.argv[++i]; }
const JAR = pos[0];
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!JAR || !cp) { console.error('usage: node jar-shim.mjs <jar> --classpath "..." --out out.jar --allow-shims'); process.exit(2); }
const OUT = args.out || JAR.replace(/\.jar$/, '.shim.jar');

const kindCache = new Map(), memberCache = new Map();
function targetInfo(cls) {
  if (!kindCache.has(cls)) {
    const r = spawnSync('javap', ['-p', '-s', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 });
    if (r.status !== 0) { kindCache.set(cls, null); }
    else {
      const abstracts = [];
      const lines = r.stdout.split('\n');
      for (let i = 0; i < lines.length; i++) {
        const d = lines[i].match(/^\s*descriptor:\s*(\S+)/);
        if (!d) continue;
        const sig = lines[i - 1] || '';
        const nm = sig.match(/([\w$]+)\s*\(/);
        if (nm && /\babstract\b/.test(sig)) abstracts.push({ name: nm[1], desc: d[1] });
      }
      kindCache.set(cls, { kind: /\binterface\s/.test(r.stdout) ? 'interface' : 'class', abstracts });
    }
  }
  return kindCache.get(cls);
}

// ── stub-class closure ───────────────────────────────────────────────────────────────────────
// A class the target deleted outright — MultiBufferSource$BufferSource and the rest of the old
// renderer — takes the whole mod down with NoClassDefFoundError the first time anything mentions
// it, which forecloses every feature the mod has, not just the rendering ones. With --allow-shims,
// each deleted class the jar reaches for is regenerated from its SOURCE-version surface: same
// package-flattened name under foxgrade/stub/, exact old constructors and method descriptors, and
// every body throws UnsupportedOperationException naming itself. References are renamed to the stub
// with the same pool edit that ships Tuple. The mod loads; the paths that truly needed the old
// renderer die at the exact call, on the record, and everything else lives.
//
// The closure matters: a deleted class's signatures reference other deleted classes, and a stub
// whose descriptor names a missing type just moves the NoClassDefFoundError one hop. Signatures are
// therefore rewritten against the closure, and members whose types stay missing are dropped from
// the stub — reaching for a dropped member is NoSuchMethodError at the call, still loud, still
// closer to the fault than a load-time death.
const SRC_JAR = args['source-classpath'];
function sourceSurface(cls) {
  if (!SRC_JAR || !fs.existsSync(SRC_JAR)) return null;
  if (!sourceSurface.idx) {
    sourceSurface.idx = new Map();
    for (const e of readZip(fs.readFileSync(SRC_JAR))) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const buf = inflateEntry(e);
        const cf = new ClassFile(buf);
        const d = cf.declared();
        // The stub must be the same KIND as the original. A mod class that implemented the old
        // interface keeps it in its interfaces list after the rename; if the stand-in is a class,
        // the verifier's assignability walk fails with Bad return type on every method that returns
        // the mod type as the interface. Four classes in the first shimmed jar did exactly that.
        d.isInterface = (buf.readUInt16BE(cf.poolEnd) & 0x0200) !== 0;
        if (d.name) sourceSurface.idx.set(d.name, d);
      } catch { /* skip */ }
    }
  }
  return sourceSurface.idx.get(cls) || null;
}
const existsInTarget = (cls) => targetInfo(cls) !== null;

const jarBuf = fs.readFileSync(JAR);
const entries = [...readZip(jarBuf)];
const replacements = new Map();
const receipts = [];
for (const e of entries) {
  if (!e.name.endsWith('.class')) continue;
  let d, data; try { data = inflateEntry(e); d = new ClassFile(data).declared(); } catch { continue; }
  if (!d.super || !d.super.startsWith('net/minecraft/')) continue;
  const info = targetInfo(d.super);
  if (!info || info.kind !== 'interface') continue;

  // The super became an interface. Re-parent, and stub every abstract the class does not implement.
  const own = new Set(d.members.filter((m) => m.kind === 'method').map((m) => `${m.name} ${m.desc}`));
  const missing = info.abstracts.filter((a) => !own.has(`${a.name} ${a.desc}`));
  if (!args['allow-shims']) {
    console.log(`  ✗ ${d.name.replace(/\//g, '.')} extends ${d.super.split('/').pop()}, now an interface — needs --allow-shims to shim`);
    continue;
  }
  const methods = missing.map((a) => ({
    name: a.name, desc: a.desc, static: false,
    throws: `FOXGRADE shim: ${d.name.split('/').pop()}.${a.name} was never ported — the ${d.super.split('/').pop()} contract changed in the target version. See SHIMMED.md in this jar.`,
  }));
  try {
    const out = transformClass(ClassFile, data, {
      methods, newSuper: 'java/lang/Object', addInterface: d.super, repointInit: d.super,
    });
    replacements.set(e.name, out);
    receipts.push({ cls: d.name, super: d.super, stubs: missing.map((m) => m.name) });
    console.log(`  ✓ ${d.name.split('/').pop()}: re-parented to Object + implements ${d.super.split('/').pop()}, ${missing.length} abstract(s) stubbed loud`);
  } catch (err) {
    console.log(`  ! ${d.name.split('/').pop()}: surgery failed (${err.message}) — left as it was`);
  }
}
// find deleted classes the jar reaches for
const deadClasses = new Set();
if (args['allow-shims'] && SRC_JAR) {
  for (const e of entries) {
    if (!e.name.endsWith('.class')) continue;
    try {
      const cf = new ClassFile(inflateEntry(e));
      for (const { value } of cf.utf8()) {
        for (const m of String(value).matchAll(/L(net\/minecraft\/[\w/$]+);/g))
          if (!existsInTarget(m[1]) && sourceSurface(m[1])) deadClasses.add(m[1]);
      }
    } catch { /* skip */ }
  }
}
if (deadClasses.size) {
  // closure over signatures
  let grew = true;
  while (grew) {
    grew = false;
    for (const c of [...deadClasses]) {
      const d = sourceSurface(c);
      for (const m of d.members) for (const t of `${m.desc}`.matchAll(/L(net\/minecraft\/[\w/$]+);/g))
        if (!existsInTarget(t[1]) && sourceSurface(t[1]) && !deadClasses.has(t[1])) { deadClasses.add(t[1]); grew = true; }
    }
  }
  const stubName = (c) => 'foxgrade/stub/' + c.slice(c.lastIndexOf('/') + 1).replace(/\$/g, '_');
  const mapType = (desc) => desc.replace(/Lnet\/minecraft\/[\w/$]+;/g, (t) => {
    const c = t.slice(1, -1);
    return deadClasses.has(c) ? `L${stubName(c)};` : existsInTarget(c) ? t : null;
  });
  const jt = (d) => { let dims = 0; while (d[dims] === '[') dims++; const b = d.slice(dims); const prim = { V: 'void', I: 'int', J: 'long', F: 'float', D: 'double', Z: 'boolean', B: 'byte', C: 'char', S: 'short' }[b]; return (prim || b.slice(1, -1).replace(/\//g, '.')) + '[]'.repeat(dims); };
  const paramsOf = (desc) => { const out = []; let i = 1; while (desc[i] !== ')') { let st = i; while (desc[i] === '[') i++; if (desc[i] === 'L') i = desc.indexOf(';', i); out.push(desc.slice(st, i + 1)); i++; } return out; };
  const dir = fs.mkdtempSync('/tmp/foxgrade-shim-');
  const sources = [];
  let dropped = 0;
  for (const c of deadClasses) {
    const d = sourceSurface(c);
    const sn = stubName(c).split('/').pop();
    const iface = !!d.isInterface;
    const lines = [`package foxgrade.stub;`, iface ? `public interface ${sn} {` : `public class ${sn} {`];
    const seen = new Set();
    for (const m of d.members) {
      if (m.name.startsWith('<clinit')) continue;
      const full = mapType(m.desc);
      if (full === null || full.includes('null')) { dropped++; continue; }       // type unrecoverable: drop the member, stay loud at NoSuchMethodError
      if (m.kind === 'field') continue;                                          // fields default to absent: reads fail loud at NoSuchFieldError
      const ps = paramsOf(full).map(jt);
      const key = `${m.name}(${ps.join(',')})`;
      if (seen.has(key)) continue; seen.add(key);
      const argl = ps.map((t, i) => `${t} a${i}`).join(', ');
      const msg = `FOXGRADE stub: ${c.replace(/\//g, '.')} was deleted in the target version. See SHIMMED.md.`;
      if (iface && (m.name === '<init>' || ['toString', 'equals', 'hashCode'].includes(m.name))) continue;   // interfaces have no ctors and cannot default Object's methods
      if (m.name === '<init>') lines.push(`  public ${sn}(${argl}) { throw new UnsupportedOperationException("${msg}"); }`);
      else { const ret = jt(full.slice(full.indexOf(')') + 1)); lines.push(`  public ${iface ? 'default ' : ''}${ret === 'void' ? 'void' : ret} ${m.name}(${argl}) { throw new UnsupportedOperationException("${msg}"); }`); }
    }
    lines.push('}');
    const f = path.join(dir, sn + '.java');
    fs.writeFileSync(f, lines.join('\n'));
    sources.push(f);
  }
  const rc = spawnSync('javac', ['-nowarn', '-proc:none', '-cp', cp, '-d', dir, ...sources], { encoding: 'utf8', maxBuffer: 64e6 });
  if (rc.status !== 0) console.log(`  ! stub compile failed: ${(rc.stderr || '').split('\n')[0]}`);
  else {
    // rename every reference in every class (including ones already replaced above)
    const rename = (v) => { let out = v, hit = false; for (const c of deadClasses) if (out.includes(c)) { out = out.split(c).join(stubName(c)); hit = true; } return hit ? out : null; };
    for (const e of entries) {
      if (!e.name.endsWith('.class')) continue;
      try {
        const base = replacements.get(e.name) || inflateEntry(e);
        const out = new ClassFile(base).rewrite(rename);
        if (out) replacements.set(e.name, out);
      } catch { /* leave it */ }
    }
    const proto2 = entries.find((x) => x.name.endsWith('.class'));
    for (const c of deadClasses) {
      const bin = path.join(dir, 'foxgrade', 'stub', stubName(c).split('/').pop() + '.class');
      if (!fs.existsSync(bin)) continue;
      entries.push({ ...proto2, name: stubName(c) + '.class' });
      replacements.set(stubName(c) + '.class', fs.readFileSync(bin));
    }
    receipts.push({ cls: `(stub closure)`, super: '-', stubs: [...deadClasses].map((c) => c.split('/').pop()) });
    console.log(`  ✓ ${deadClasses.size} deleted class(es) stubbed loud${dropped ? `, ${dropped} member(s) dropped (types unrecoverable)` : ''}`);
  }
}

if (!replacements.size) { console.log('  nothing shimmed.'); process.exit(0); }
const proto = entries.find((x) => x.name.endsWith('.class'));
entries.push({ ...proto, name: 'SHIMMED.md' });
replacements.set('SHIMMED.md', Buffer.from([
  '# Structural shims in this jar', '',
  'These classes extended a Minecraft class that became an interface in the target version. They',
  'were re-parented so the mod can LOAD; contract methods that could not be ported now throw',
  'UnsupportedOperationException naming this file. A crash you can trace beats silence.', '',
  ...receipts.map((r) => `## ${r.cls.replace(/\//g, '.')}\n- was: extends ${r.super.replace(/\//g, '.')}\n- stubbed loud: ${r.stubs.join(', ') || '(none)'}\n`),
].join('\n')));
fs.writeFileSync(OUT, writeZip(entries, replacements));
console.log(`  wrote ${OUT} — receipts in SHIMMED.md`);
