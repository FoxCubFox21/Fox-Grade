#!/usr/bin/env node
// Tier 2: fix what renaming cannot, in a compiled jar.
//
// Tier 1 rewrites names in the constant pool and stops there, because a rename table cannot express
// GuiGraphics dissolving into GuiGraphicsExtractor + Hud + RenderPipelines. Those need real code
// changes, which means source — so this decompiles ONLY the classes that still have broken links,
// ports them, and compiles the result back. The blast radius of lossy decompilation is limited to
// the files that were already broken.
//
// The loop is what makes it trustworthy, not the model:
//   javac must accept the port      — a hallucinated method fails to compile
//   the link checker must improve   — it must fix more than it breaks
// A file that fails either is discarded and the original bytecode is kept, so a bad suggestion
// costs time rather than correctness.
//
//   node jar-tier2.mjs remapped.jar --classpath "<target jars>" --to 26.2 --dry
//   node jar-tier2.mjs remapped.jar --classpath ... --to 26.2 --out fixed.jar [--foxai]
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['dry', 'foxai', 'ollama', 'keep', 'allow-stubs']);
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) {
  const t = process.argv[i];
  if (!t.startsWith('--')) { pos.push(t); continue; }
  const k = t.slice(2);
  args[k] = FLAGS.has(k) ? true : process.argv[++i];
}
const JAR = pos[0];
if (!JAR || !fs.existsSync(JAR)) { console.error('usage: node jar-tier2.mjs <jar> --classpath "<target jars>" [--out fixed.jar]'); process.exit(2); }
const TO = args.to || '26.2';
const MAX_FILES = +(args.max || 6);
const REPAIRS = +(args.repair || 2);
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!cp) { console.error('need --classpath'); process.exit(2); }
const CFR = args.cfr || [path.join(process.env.HOME, 'camsnap-recovery/cfr.jar'), path.join(HERE, 'cfr.jar')].find((p) => fs.existsSync(p));

const WORK = args.work || fs.mkdtempSync('/tmp/foxgrade-t2-');
const say = (s) => process.stderr.write(s + '\n');

// ── 1. which class FILES still have broken links, and exactly what is broken ──────────────────
// jar-verify answers this per owner class; here it must be per file, because a file is the unit
// that gets decompiled, ported and recompiled.
const declCache = new Map();
function parseReport(text) {
  const members = new Set(), supers = [];
  const head = text.match(/^[^\n]*\b(?:class|interface)\s+([\w.$]+)([^{]*)\{/m);
  const self = head ? head[1].split('.').pop() : null;
  if (head) for (const part of head[2].split(/\b(?:extends|implements)\b/).slice(1))
    for (const t of part.split(',')) {
      const n = t.trim().replace(/<.*/, '').replace(/\./g, '/');
      if (/^[\w/$]+$/.test(n) && n !== 'java/lang/Object') supers.push(n);
    }
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const d = lines[i].match(/^\s*descriptor:\s*(\S+)\s*$/);
    if (!d) continue;
    const nm = (lines[i - 1] || '').match(/([\w$]+)\s*\(/) || (lines[i - 1] || '').match(/([\w$]+);?\s*$/);
    if (nm) members.add(`${nm[1] === self ? '<init>' : nm[1]} ${d[1]}`);
  }
  return { members, supers };
}
function preload(names) {
  const todo = [...new Set(names)].filter((n) => !declCache.has(n));
  for (let i = 0; i < todo.length; i += 200) {
    const batch = todo.slice(i, i + 200);
    const r = spawnSync('javap', ['-p', '-s', '-cp', cp, ...batch.map((c) => c.replace(/\//g, '.'))], { encoding: 'utf8', maxBuffer: 512e6 });
    if (r.stdout) for (const p of r.stdout.split(/\n(?=(?:Compiled from|public |final |abstract |class |interface |@))/)) {
      const h = p.match(/\b(?:class|interface)\s+([\w.$]+)/);
      if (h) declCache.set(h[1].replace(/\./g, '/'), parseReport(p));
    }
    for (const c of batch) if (!declCache.has(c)) declCache.set(c, null);
  }
}
const OBJECT_METHODS = new Set(['equals (Ljava/lang/Object;)Z', 'hashCode ()I', 'toString ()Ljava/lang/String;', 'getClass ()Ljava/lang/Class;', 'clone ()Ljava/lang/Object;', 'notify ()V', 'notifyAll ()V', 'wait ()V', 'wait (J)V', 'wait (JI)V', 'finalize ()V']);
function resolves(cls, key, seen = new Set()) {
  if (OBJECT_METHODS.has(key)) return true;
  if (seen.has(cls)) return false;
  seen.add(cls);
  const d = declCache.get(cls);
  if (!d) return false;
  if (d.members.has(key)) return true;
  return d.supers.some((s) => resolves(s, key, seen));
}

const rootBuf = fs.readFileSync(JAR);
const entries = readZip(rootBuf);
const fileRefs = new Map();   // entry name -> [{owner,name,desc}]
const owners = new Set();
for (const e of entries) {
  if (!e.name.endsWith('.class')) continue;
  const list = [];
  for (const r of new ClassFile(inflateEntry(e)).refs()) {
    if (!r.owner.startsWith('net/minecraft/')) continue;
    list.push(r); owners.add(r.owner);
  }
  if (list.length) fileRefs.set(e.name, list);
}
preload([...owners]);
for (let w = 0; w < 8; w++) {
  const parents = [];
  for (const [, d] of declCache) if (d) for (const s of d.supers) if (!declCache.has(s)) parents.push(s);
  if (!parents.length) break;
  preload(parents);
}
const broken = [];
for (const [name, refs] of fileRefs) {
  const bad = [];
  for (const r of refs) {
    if (!declCache.get(r.owner)) { bad.push({ ...r, why: 'class missing' }); continue; }
    if (!resolves(r.owner, `${r.name} ${r.desc}`)) bad.push({ ...r, why: 'member missing' });
  }
  if (bad.length) broken.push({ name, bad });
}
broken.sort((a, b) => b.bad.length - a.bad.length);
const totalBad = broken.reduce((n, b) => n + b.bad.length, 0);

console.log(`  ${path.basename(JAR)}`);
console.log(`    class files with broken links : ${broken.length}`);
console.log(`    broken links total            : ${totalBad}`);
for (const b of broken.slice(0, 10)) console.log(`      ${b.bad.length.toString().padStart(3)}  ${b.name}`);
if (broken.length > 10) console.log(`      … ${broken.length - 10} more files`);

// Mixins target a method by descriptor. If that method is gone the fix is a different injection
// point, which is a design decision — say so instead of letting the model invent one.
const mixinCfgs = entries.filter((e) => /mixins?.*\.json$/.test(e.name)).map((e) => { try { return JSON.parse(inflateEntry(e).toString('utf8')); } catch { return null; } }).filter(Boolean);
const mixinPkgs = new Set(mixinCfgs.map((c) => (c.package || '').replace(/\./g, '/')).filter(Boolean));
const isMixin = (n) => [...mixinPkgs].some((p) => n.startsWith(p + '/'));
const brokenMixins = broken.filter((b) => isMixin(b.name));
if (brokenMixins.length) {
  console.log(`\n    ${brokenMixins.length} of these are MIXINS. A mixin whose target method was removed needs a new`);
  console.log(`    injection point, not new code — that is a decision for you, so they are left alone.`);
}
const candidates = broken.filter((b) => !isMixin(b.name)).slice(0, MAX_FILES);
if (!candidates.length) { console.log('\n  nothing here that Tier 2 can safely attempt'); process.exit(0); }
console.log(`\n    will attempt : ${candidates.length} file(s)`);
if (args.dry) { for (const c of candidates) { console.log(`\n  ── ${c.name}`); for (const b of c.bad.slice(0, 6)) console.log(`      ${b.owner.replace(/\//g, '.')}.${b.name} ${b.desc}   (${b.why})`); } process.exit(0); }
if (!CFR) { console.error('\n  no decompiler found — pass --cfr /path/to/cfr.jar'); process.exit(2); }

// ── 2. advisories + real signatures, so the model is told facts rather than asked to recall them ──
const rules = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
const advisories = new Map();
for (const a of (rules[TO]?.advisories || [])) if (a && typeof a === 'object' && a.when) advisories.set(a.when, a);
// Relocations are the precise kind of fact worth handing a model: the member still exists, it just
// moved behind a field. Applying that is a one-token source edit and impossible as a rename, because
// inserting a getfield shifts every later bytecode offset and invalidates the stack map frames.
const relocations = new Map();   // owner \t name \t desc -> {via, host}
for (const f of fs.readdirSync(HERE).filter((x) => /^members\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try {
    for (const r of JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8')).relocations || [])
      relocations.set(`${r.owner}\t${r.name}\t${r.desc}`, r);
  } catch { /* ignore */ }
}
function signaturesOf(cls) {
  const r = spawnSync('javap', ['-p', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 });
  return r.status === 0 && r.stdout ? r.stdout.split('\n').filter((l) => /^\s{2}\S/.test(l)).slice(0, 40).join('\n') : null;
}

// ── 2b. a classpath javac can actually use ───────────────────────────────────────────────────
// Fabric API ships as a jar of jars: its classes live in nested META-INF/jars entries, invisible to
// javac. Without unpacking them every port fails with "cannot access Event" and the model gets the
// blame for a harness problem. Extract once, reuse for every file.
const LIBS = path.join(WORK, 'libs');
fs.mkdirSync(LIBS, { recursive: true });
const extra = [];
for (const j of cp.split(':')) {
  if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
  let inner;
  try { inner = readZip(fs.readFileSync(j)).filter((e) => /^META-INF\/jars\/.+\.jar$/.test(e.name)); } catch { continue; }
  for (const e of inner) {
    const dest = path.join(LIBS, path.basename(e.name));
    if (!fs.existsSync(dest)) { try { fs.writeFileSync(dest, inflateEntry(e)); } catch { continue; } }
    extra.push(dest);
  }
}
if (extra.length) say(`  unpacked ${extra.length} bundled library jar(s) for compilation`);
const COMPILE_CP = [JAR, cp, ...extra].join(':');

// Annotations like @Nullable are compile-time only and their jar is not shipped with the game, so
// they break the build without affecting behaviour. Dropping them is safer than inventing a stub.
const stripAnnotations = (src) => src
  .replace(/^\s*import\s+(?:org\.jetbrains\.annotations|javax\.annotation|org\.jspecify)\..*;\s*$/gm, '')
  .replace(/@(?:Nullable|NotNull|NonNull|Unmodifiable|ApiStatus\.\w+)\b(\s*\([^)]*\))?/g, '');

// ── 3. the model ─────────────────────────────────────────────────────────────────────────────
const useOllama = args.foxai || args.ollama;
const MODEL = args.model || 'qwen2.5-coder:14b';
function callAI(prompt) {
  if (useOllama) {
    const body = JSON.stringify({ model: MODEL, prompt, stream: false, keep_alive: '30m', options: { temperature: 0, num_ctx: 16384 } });
    const r = spawnSync('curl', ['-s', '--max-time', '900', 'http://localhost:11434/api/generate', '-H', 'content-type: application/json', '-d', body], { encoding: 'utf8', maxBuffer: 64e6 });
    try { return JSON.parse(r.stdout).response || ''; } catch { return ''; }
  }
  const r = spawnSync('claude', ['-p', prompt], { encoding: 'utf8', maxBuffer: 64e6, timeout: 900000 });
  return r.stdout || '';
}
const extractJava = (s) => {
  const fence = s.match(/```(?:java)?\n([\s\S]*?)```/);
  const code = fence ? fence[1] : s;
  const at = code.search(/^\s*(package|import|@|public|final|abstract|class|interface|enum)\b/m);
  return at >= 0 ? code.slice(at).trim() : '';
};

// ── 4. decompile → port → compile → keep only what verifies ──────────────────────────────────
const replacements = new Map();
const results = [];
for (const c of candidates) {
  const cls = c.name.replace(/\.class$/, '');
  const simple = cls.split('/').pop();
  say(`\n  ── ${cls}`);
  const outDir = path.join(WORK, simple);
  fs.mkdirSync(outDir, { recursive: true });
  const d = spawnSync('java', ['-jar', CFR, JAR, '--extraclasspath', cp, '--outputdir', outDir, '--comments', 'false', '--silent', 'true', '--jarfilter', cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 128e6, timeout: 300000 });
  void d;
  const srcPath = path.join(outDir, cls + '.java');
  if (!fs.existsSync(srcPath)) { say('     decompile produced nothing — skipping'); results.push([cls, 'no source']); continue; }
  let source = stripAnnotations(fs.readFileSync(srcPath, 'utf8'));
  // Obfuscators emit identifiers the JVM accepts but Java source cannot express — a keyword as a
  // method name, or a non-ASCII character. Such a class can never be recompiled however good the
  // port is, so it is not worth a model call. Checked PER LINE: joining lines first and testing for
  // characters below \x20 matches the newline separator itself and rejects every file.
  const KEYWORD_ID = /\b(?:if|else|for|while|new|null|class|int|void|return|this|super)\s*\(\)|\.\s*(?:if|else|for|while|new|null|class|int|void|return)\s*\(/;
  const badLine = source.split('\n').find((l) => KEYWORD_ID.test(l) || /[^\x09\x20-\x7E]/.test(l));
  if (badLine) {
    say(`     source has identifiers Java cannot express — skipping: ${badLine.trim().slice(0, 60)}`);
    results.push([cls, 'unrecompilable']); continue;
  }

  // If a class that no longer exists appears in this file's OWN method signatures, no body-only
  // edit can fix it: the signature must change, and every caller in the jar links to the old one as
  // compiled bytecode. That is a coordinated change across several files, which is a decision rather
  // than a repair — so say so instead of spending three model calls rediscovering it.
  const ownSigs = spawnSync('javap', ['-p', '-s', '-cp', `${JAR}:${cp}`, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 }).stdout || '';
  const missingClasses = [...new Set(c.bad.filter((b) => b.why === 'class missing').map((b) => b.owner))];
  const inSignature = missingClasses.filter((m) => ownSigs.includes(`L${m};`));
  if (inSignature.length) {
    say(`     ${inSignature.map((m) => m.replace(/\//g, '.')).join(', ')} appears in this class's own method signatures.`);
    say('     Fixing it means changing signatures other classes link to — a coordinated change, not a repair.');
    results.push([cls, `needs signature change (${inSignature.map((m) => m.split('/').pop()).join(', ')})`]);
    continue;
  }

  const facts = [];
  for (const b of c.bad.slice(0, 12)) {
    const dotted = b.owner.replace(/\//g, '.');
    const reloc = relocations.get(`${b.owner}\t${b.name}\t${b.desc}`);
    if (reloc) {
      facts.push(`- ${dotted}.${b.name}${b.desc} moved. It now lives on ${reloc.host.replace(/\//g, '.')}, reached through the`
        + ` field \`${reloc.via}\`. Change the call to  <receiver>.${reloc.via}.${b.name}(...)  — same name, same arguments.`);
      continue;
    }
    facts.push(`- ${dotted}.${b.name} ${b.desc}  → ${b.why}`);
    const adv = advisories.get(dotted);
    if (adv) facts.push(`    ${dotted} has no direct replacement in ${TO}. Real ports used: ${adv.candidates.map((x) => x.fqcn).join(', ')}`);
  }
  const sigBlocks = [];
  for (const dotted of [...new Set(c.bad.map((b) => b.owner.replace(/\//g, '.')))].slice(0, 4)) {
    const adv = advisories.get(dotted);
    for (const cand of (adv?.candidates || []).slice(0, 2)) {
      const sig = signaturesOf(cand.fqcn);
      if (sig) sigBlocks.push(`// ${cand.fqcn} — the REAL API in ${TO}:\n${sig}`);
    }
  }

  const base = `You are porting one Java class of a Minecraft mod to ${TO}. The class was already
name-remapped; what remains are genuine API redesigns.

BROKEN LINKS — every one of these fails at runtime today:
${facts.join('\n')}

${sigBlocks.length ? `THE ACTUAL TARGET API (from javap — these signatures are ground truth, do not guess):\n${sigBlocks.join('\n\n')}\n` : ''}
RULES:
- Do NOT change the class name, or the name/parameters/return type of any public or protected
  method. Other classes in this jar are still compiled bytecode and link to these exact signatures;
  changing one breaks callers you cannot see. Change method BODIES.
- Use only members shown above or already in the file. If you cannot fix something without changing
  a signature, leave that part as it is and add // FOXGRADE: <what is needed>.
- Output the complete file, nothing else.

SOURCE:
\`\`\`java
${source}
\`\`\``;

  let ported = null, lastErr = '';
  for (let attempt = 0; attempt <= REPAIRS; attempt++) {
    const prompt = attempt === 0 ? base
      : `${base}\n\nYour previous attempt did not compile. Fix exactly these errors:\n${lastErr.slice(0, 4000)}`;
    const out = extractJava(callAI(prompt));
    if (!out) { say(`     attempt ${attempt + 1}: no code returned`); continue; }
    const cDir = path.join(outDir, 'build' + attempt);
    fs.mkdirSync(cDir, { recursive: true });
    const f = path.join(cDir, simple + '.java');
    fs.writeFileSync(f, stripAnnotations(out));
    // The original jar is on the classpath, so the class compiles against its own mod's other
    // classes exactly as it will be linked at runtime.
    const jc = spawnSync('javac', ['-nowarn', '-proc:none', '-cp', COMPILE_CP, '-d', cDir, f], { encoding: 'utf8', maxBuffer: 32e6, timeout: 300000 });
    if (jc.status === 0) {
      const built = path.join(cDir, cls + '.class');
      if (fs.existsSync(built)) { ported = { source: out, bytes: fs.readFileSync(built), dir: cDir }; say(`     attempt ${attempt + 1}: compiles ✓`); break; }
      say(`     attempt ${attempt + 1}: compiled but produced no ${simple}.class`);
    } else {
      lastErr = (jc.stderr || '').split('\n').filter((l) => /error:/.test(l)).slice(0, 12).join('\n');
      say(`     attempt ${attempt + 1}: ${(lastErr.match(/error:/g) || []).length} compile error(s)`);
    }
  }
  if (!ported) { results.push([cls, 'never compiled']); continue; }

  // Compiling is necessary, not sufficient: check the ported class actually has fewer broken links.
  const after = [];
  for (const r of new ClassFile(ported.bytes).refs()) {
    if (!r.owner.startsWith('net/minecraft/')) continue;
    if (!declCache.has(r.owner)) preload([r.owner]);
    if (!declCache.get(r.owner) || !resolves(r.owner, `${r.name} ${r.desc}`)) after.push(r);
  }
  if (after.length >= c.bad.length) { say(`     rejected: ${c.bad.length} broken links before, ${after.length} after`); results.push([cls, `no improvement (${c.bad.length}→${after.length})`]); continue; }
  // "Compiles, and fewer broken links" is NOT the same as "ported". A model that cannot find the
  // replacement API can always satisfy both gates by deleting the feature — and it did exactly that
  // here, turning isRotten() into a constant false and assuming naturalRegeneration is on. Both
  // gates went green while the mod quietly stopped working.
  //
  // A broken link at least crashes where it is reached: loud, and traceable. A stub returns
  // plausible wrong data forever. So stubs are refused by default, keeping the original bytecode,
  // and never hidden when they are allowed.
  const stubs = (ported.source.match(/\/\/\s*FOXGRADE:/g) || []).length;
  if (stubs) {
    const what = (ported.source.match(/\/\/\s*FOXGRADE:[^\n]*/g) || []).slice(0, 3).map((l) => l.replace(/\/\/\s*FOXGRADE:\s*/, '').trim());
    say(`     compiles and resolves, but REMOVES ${stubs} piece(s) of behaviour rather than porting them:`);
    for (const w of what) say(`       · ${w.slice(0, 96)}`);
    if (!args['allow-stubs']) {
      say('     refused — a crash you can trace beats silently wrong behaviour. --allow-stubs to keep it.');
      results.push([cls, `refused: would remove ${stubs} behaviour(s)`]);
      continue;
    }
    say('     kept anyway (--allow-stubs)');
  }
  say(`     accepted: ${c.bad.length} → ${after.length} broken links${stubs ? `, with ${stubs} stub(s)` : ''}`);
  replacements.set(c.name, ported.bytes);
  // Inner classes are compiled alongside and must travel with it.
  for (const f of fs.readdirSync(path.dirname(path.join(ported.dir, cls + '.class')))) {
    if (!f.startsWith(simple + '$') || !f.endsWith('.class')) continue;
    replacements.set(path.dirname(c.name) + '/' + f, fs.readFileSync(path.join(path.dirname(path.join(ported.dir, cls + '.class')), f)));
  }
  results.push([cls, `fixed ${c.bad.length - after.length} link(s)${stubs ? ` (${stubs} STUBBED — behaviour removed)` : ''}`]);
}

console.log(`\n  ── results ──`);
for (const [cls, r] of results) console.log(`    ${r.startsWith('fixed') ? '✓' : '·'} ${cls.split('/').pop().padEnd(28)} ${r}`);
if (!replacements.size) { console.log('\n  nothing was accepted; the jar is unchanged'); process.exit(1); }
if (!args.out) { console.log(`\n  ${replacements.size} class(es) would be replaced — pass --out to write`); process.exit(0); }
fs.writeFileSync(args.out, writeZip(entries, replacements));
console.log(`\n  wrote ${args.out} — ${replacements.size} class(es) replaced`);
console.log(`  re-run jar-verify.mjs on it, then launch the game. Compiling clean is not the same as working.`);
if (!args.keep) fs.rmSync(WORK, { recursive: true, force: true });
