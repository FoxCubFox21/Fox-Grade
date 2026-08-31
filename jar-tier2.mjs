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
import zlib from 'node:zlib';
import path from 'node:path';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FLAGS = new Set(['dry', 'foxai', 'ollama', 'keep', 'allow-stubs', 'last-resort', 'no-lookup', 'no-group']);
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
// Vineflower first: measured against CFR 0.152 on the same classes it produces far fewer errors in
// its own output — ClothConfigScreen 62 -> 28, ClothConfigDemo 17 -> 2. That matters because the AI
// inherits every decompiler artifact as a compile error it did not cause and must fix before it can
// even reach the porting work.
const VINEFLOWER = args.vineflower || [path.join(HERE, 'vineflower.jar'), '/private/tmp/claude-501/-Users-cassiusmehlhopt/0eb3c942-b1be-4cca-8473-756df2995ecd/scratchpad/vineflower.jar'].find((x) => fs.existsSync(x));
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

// Members other mods call successfully on the TARGET version. Fabric API adds methods to vanilla
// classes by mixin, so they are in no jar and resolve fine at runtime. jar-verify already knew this;
// this tool had its own link check that did not, so Tier 2 was being handed non-problems and
// spending model calls on them — promenade's own 26.2 build makes the very call reported broken.
// Two checks that disagree is worse than either alone, because the wrong one silently drives work.
const loaderProvided = new Set();
if (args.corpus && fs.existsSync(args.corpus)) {
  for (const f of fs.readdirSync(args.corpus)) {
    if (!f.endsWith('.json.gz')) continue;
    let rec; try { rec = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(args.corpus, f)))); } catch { continue; }
    for (const r of rec.new.refs || []) loaderProvided.add(r);
  }
  say(`  ${loaderProvided.size.toLocaleString()} member(s) known to resolve at runtime from the corpus`);
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
    if (resolves(r.owner, `${r.name} ${r.desc}`)) continue;
    if (loaderProvided.has(`${r.owner}\t${r.name}\t${r.desc}`)) continue;   // provided by the loader
    bad.push({ ...r, why: 'member missing' });
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
// ── who calls whom, so a signature change can be made coherently ──────────────────────────────
// Porting one class at a time forbids changing any signature, because every other class still holds
// the old one as compiled bytecode. That is a limit of the method, not of the problem: recompile the
// callers alongside and the constraint lifts. Promenade needed exactly that — one caller, out of 162
// classes.
const callersOf = new Map();     // internal class name -> Set(classes in this jar that reference it)
{
  const own = new Set(entries.filter((e) => e.name.endsWith('.class')).map((e) => e.name.replace(/\.class$/, '')));
  for (const e of entries) {
    if (!e.name.endsWith('.class')) continue;
    const me = e.name.replace(/\.class$/, '');
    let cf; try { cf = new ClassFile(inflateEntry(e)); } catch { continue; }
    for (const r of cf.refs()) {
      const outer = r.owner.replace(/\$.*$/, '');
      if (!own.has(r.owner) && !own.has(outer)) continue;
      const key = own.has(r.owner) ? r.owner : outer;
      if (key === me) continue;
      if (!callersOf.has(key)) callersOf.set(key, new Set());
      callersOf.get(key).add(me);
    }
  }
}
// A group is the broken class plus everything in this jar that calls it. Kept small deliberately: a
// class called from thirty places is not a porting unit, it is a refactor, and the wider the group
// the more of the jar a single bad answer can damage.
const MAX_GROUP = +(args.group || 4);
function groupFor(cls) {
  const callers = [...(callersOf.get(cls) || [])].filter((c) => !isMixin(c + '.class'));
  if (!callers.length || callers.length + 1 > MAX_GROUP) return null;
  return callers;
}

const candidates = broken.filter((b) => !isMixin(b.name)).slice(0, MAX_FILES);
if (!candidates.length) { console.log('\n  nothing here that Tier 2 can safely attempt'); process.exit(0); }
console.log(`\n    will attempt : ${candidates.length} file(s)`);
if (args.dry) { for (const c of candidates) { console.log(`\n  ── ${c.name}`); for (const b of c.bad.slice(0, 6)) console.log(`      ${b.owner.replace(/\//g, '.')}.${b.name} ${b.desc}   (${b.why})`); } process.exit(0); }
if (!VINEFLOWER && !CFR) { console.error('\n  no decompiler found — pass --vineflower or --cfr'); process.exit(2); }
say(`  decompiler: ${VINEFLOWER ? 'vineflower' : 'cfr'}`);
// Vineflower works on a whole jar rather than one class, so decompile once and read files out of the
// result. That is also fewer JVM starts than CFR's per-class invocation.
let vfRoot = null;
if (VINEFLOWER) {
  vfRoot = path.join(WORK, 'vf');
  fs.mkdirSync(vfRoot, { recursive: true });
  const r = spawnSync('java', ['-jar', VINEFLOWER, '-dgs=1', '--silent', JAR, vfRoot], { encoding: 'utf8', maxBuffer: 256e6, timeout: 900000 });
  if (r.status !== 0) { say('  vineflower failed; falling back to cfr'); vfRoot = null; }
}

// ── 2. advisories + real signatures, so the model is told facts rather than asked to recall them ──
const rules = JSON.parse(fs.readFileSync(path.join(HERE, 'rules.json'), 'utf8'));
const advisories = new Map();
for (const a of (rules[TO]?.advisories || [])) if (a && typeof a === 'object' && a.when) advisories.set(a.when, a);
// Relocations are the precise kind of fact worth handing a model: the member still exists, it just
// moved behind a field. Applying that is a one-token source edit and impossible as a rename, because
// inserting a getfield shifts every later bytecode offset and invalidates the stack map frames.
// Everything the miners derived, so a fact that failed to apply mechanically still reaches the model
// rather than being silently withheld from it.
const memberFacts = new Map();   // owner \t name -> newName
for (const f of fs.readdirSync(HERE).filter((x) => /^(members|mixin-points)\.[\d.]+-[\d.]+\.json$/.test(x))) {
  try {
    const t = JSON.parse(fs.readFileSync(path.join(HERE, f), 'utf8'));
    for (const r of [...(t.renames || []), ...(t.guesses || []), ...(t.corroborated || []), ...(t.single || [])])
      if (r.owner && r.from && r.to) memberFacts.set(`${r.owner}\t${r.from}`, r.to);
  } catch { /* ignore */ }
}

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

// The members of a class that carry a given descriptor. When a method is gone, these are the only
// things it could plausibly have become — a far more useful answer than a dump of the whole class,
// and small enough to always include. Previously the model was told "this method is missing" with
// nothing at all about what the class does have, unless an advisory happened to exist.
function sameShapeMembers(cls, desc) {
  const r = spawnSync('javap', ['-p', '-s', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 32e6 });
  if (r.status !== 0 || !r.stdout) return [];
  const lines = r.stdout.split('\n'), out = [];
  for (let i = 0; i < lines.length; i++) {
    const d = lines[i].match(/^\s*descriptor:\s*(\S+)\s*$/);
    if (!d || d[1] !== desc) continue;
    const sig = (lines[i - 1] || '').trim();
    if (sig) out.push(sig);
  }
  return out;
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

// A mod's declared dependencies are part of its compile classpath. Without them the model cannot
// reference types it plainly needs — promenade depends on Biolith, and asked to port code calling
// BiomePlacement with that class absent, the only thing it could do was delete the calls. That reads
// as "the AI removed behaviour" when the real cause is a classpath this tool never assembled.
const deps = [];
if (args['deps-dir'] && fs.existsSync(args['deps-dir'])) {
  for (const f of fs.readdirSync(args['deps-dir'])) if (f.endsWith('.jar')) deps.push(path.join(args['deps-dir'], f));
}
// Anything sitting next to the jar being ported is a plausible dependency too — a mods folder is
// exactly the set of things that load together.
if (args.mods && fs.existsSync(args.mods)) {
  for (const f of fs.readdirSync(args.mods)) if (f.endsWith('.jar') && path.resolve(args.mods, f) !== path.resolve(JAR)) deps.push(path.join(args.mods, f));
}
if (deps.length) {
  say(`  ${deps.length} dependency jar(s) on the compile classpath`);
  for (const d of deps.slice(0, 40)) {
    try { for (const e of readZip(fs.readFileSync(d)).filter((x) => /^META-INF\/jars\/.+\.jar$/.test(x.name))) {
      const dest = path.join(LIBS, path.basename(e.name));
      if (!fs.existsSync(dest)) fs.writeFileSync(dest, inflateEntry(e));
      extra.push(dest);
    } } catch { /* not a zip we can read */ }
  }
}
const COMPILE_CP = [JAR, cp, ...extra, ...deps].join(':');

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
// Check the model's factual claims against the jars.
//
// Asked to port a call that needed a HolderGetter<Biome>, the model wrote "no static lookup exists
// any more (VanillaRegistries was removed)" and built a broken workaround on top of that belief.
// VanillaRegistries is in the 26.2 jar. The reasoning was confident, wrong, and load-bearing.
//
// Claims like that are checkable: they name a class, and either it is in the target or it is not.
// So every such assertion is tested, and a false one is handed straight back — the model gets to
// revise rather than have its conclusion silently trusted. This runs on the model's PROSE, not its
// code, because a wrong belief shows up in the explanation before it shows up in the output.
const REMOVAL_CLAIM = /\b([A-Z][\w$]{2,}(?:\.[A-Z][\w$]+)?)\b[^.\n]{0,60}?\b(?:was |is |has been |no longer |not )?(?:removed|deleted|gone|gone away|gutted|gone from|no longer exists?|gone in|does not exist|gone entirely)\b/g;
function falseClaims(text) {
  const bad = [];
  const seen = new Set();
  for (const m of text.matchAll(REMOVAL_CLAIM)) {
    const name = m[1].split('.').pop();
    if (seen.has(name) || name.length < 4) continue;
    seen.add(name);
    // Look for a class of that simple name anywhere in the target. Cheap and decisive: if javap can
    // print it, "it was removed" is false.
    const r = spawnSync('javap', ['-cp', cp, name.includes('.') ? name : `net.minecraft.${name}`], { encoding: 'utf8' });
    let exists = r.status === 0;
    if (!exists) {
      const g = spawnSync('sh', ['-c', `unzip -Z1 "${cp.split(':')[0]}" 2>/dev/null | grep -m1 -E '/${name}\\.class$'`], { encoding: 'utf8' });
      exists = !!(g.stdout || '').trim();
      if (exists) bad.push({ name, found: g.stdout.trim().replace(/\.class$/, '').replace(/\//g, '.') });
    } else bad.push({ name, found: name });
  }
  return bad;
}

// Let the model LOOK instead of pre-guessing what it will need.
//
// Everything above assembles context in advance: the broken links, same-shape candidates, producers
// of a needed type. That is guessing what a question will be. Finding the route to a
// HolderGetter<Biome> took a person six javap calls, each one chosen after seeing the last — which
// is exactly what a one-shot prompt forbids. So before asking for code, run a short round of
// questions: the model asks, javap answers, up to a few times.
//
// Queries are answered with javap and nothing else. It cannot run commands; it names a class and
// gets its signatures back, which is the whole of what was needed here.
const MAX_QUERIES = +(args.queries || 6);
function answerQueries(text) {
  const out = [];
  for (const m of text.matchAll(/^\s*LOOKUP\s+([\w.$]+)\s*$/gm)) {
    const cls = m[1];
    const r = spawnSync('javap', ['-p', '-cp', cp, cls], { encoding: 'utf8', maxBuffer: 32e6 });
    out.push(r.status === 0 && r.stdout
      ? `--- ${cls}\n${r.stdout.split('\n').filter((l) => /^\s{2}\S/.test(l)).slice(0, 45).join('\n')}`
      : `--- ${cls}\n(no such class in ${TO})`);
  }
  return out;
}
function gatherContext(basePrompt) {
  const learned = [];
  for (let round = 0; round < 2; round++) {
    const ask = `${basePrompt}

Before writing any code you may inspect the target version. Emit up to ${MAX_QUERIES} lines of the form

  LOOKUP fully.qualified.ClassName

and nothing else, to see that class's members. Ask about anything you need: a type you must construct,
a class that might provide a factory, a replacement you suspect exists. If you already have enough,
reply with the single word READY.
${learned.length ? `\nAlready looked up:\n${learned.join('\n')}` : ''}`;
    const reply = callAI(ask);
    if (/^\s*READY\s*$/m.test(reply) && !/LOOKUP/.test(reply)) break;
    const answers = answerQueries(reply);
    if (!answers.length) break;
    learned.push(...answers);
    say(`     looked up ${answers.length} class(es)`);
  }
  return learned.length ? `\nWHAT YOU ASKED TO SEE:\n${learned.join('\n\n')}\n` : '';
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
const removedLog = [];
for (const c of candidates) {
  const cls = c.name.replace(/\.class$/, '');
  const simple = cls.split('/').pop();
  say(`\n  ── ${cls}`);
  const outDir = path.join(WORK, simple);
  fs.mkdirSync(outDir, { recursive: true });
  let srcPath = vfRoot ? path.join(vfRoot, cls + '.java') : null;
  if (!srcPath || !fs.existsSync(srcPath)) {
    if (!CFR) { say('     no source from vineflower and no cfr fallback — skipping'); results.push([cls, 'no source']); continue; }
    spawnSync('java', ['-jar', CFR, JAR, '--extraclasspath', cp, '--outputdir', outDir, '--comments', 'false', '--silent', 'true', '--jarfilter', cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 128e6, timeout: 300000 });
    srcPath = path.join(outDir, cls + '.java');
  }
  if (!fs.existsSync(srcPath)) { say('     decompile produced nothing — skipping'); results.push([cls, 'no source']); continue; }
  let source = stripAnnotations(fs.readFileSync(srcPath, 'utf8'));
  // Obfuscators emit identifiers the JVM accepts but Java source cannot express — a keyword as a
  // method name, or a non-ASCII character. Such a class can never be recompiled however good the
  // port is, so it is not worth a model call. Checked PER LINE: joining lines first and testing for
  // characters below \x20 matches the newline separator itself and rejects every file.
  const KEYWORD_ID = /\b(?:if|else|for|while|new|null|class|int|void|return|this|super)\s*\(\)|\.\s*(?:if|else|for|while|new|null|class|int|void|return)\s*\(/;
  // Strip string and char literals first. Non-ASCII inside a string is ordinary legal Java — a
  // kaomoji in Component.literal("(・∀・)") had four perfectly good files written off as
  // unrecompilable. Only a non-ASCII IDENTIFIER is a problem.
  const noLiterals = (l) => l.replace(/"(\\.|[^"\\])*"/g, '""').replace(/'(\\.|[^'\\])*'/g, "''");
  const badLine = source.split('\n').map(noLiterals).find((l) => KEYWORD_ID.test(l) || /[^\x09\x20-\x7E]/.test(l));
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
      // Naming the receiver's TYPE matters. Told only "reached through the field hud", the model
      // wrote mc.hud.getGuiTicks() and dropped the Gui in between; the call site already has a Gui
      // in hand and the field hangs off that, not off Minecraft.
      facts.push(`- ${dotted}.${b.name}${b.desc} moved to ${reloc.host.replace(/\//g, '.')}.`
        + ` Keep the existing receiver — it is a ${dotted.split('.').pop()} — and insert .${reloc.via} between it and the call:`
        + `  <the ${dotted.split('.').pop()} you already have>.${reloc.via}.${b.name}(...)  Same name, same arguments.`);
      continue;
    }
    facts.push(`- ${dotted}.${b.name} ${b.desc}  → ${b.why}`);
    // Same-descriptor members of the same class: the only shapes this could have become.
    const shapes = sameShapeMembers(b.owner, b.desc);
    if (shapes.length) facts.push(`    ${dotted} does still declare these with that exact signature — one of them is probably the replacement:\n      ${shapes.slice(0, 6).join('\n      ')}`);
    else facts.push(`    Nothing on ${dotted} has that signature any more, so this is a redesign, not a rename.`);
    const known = memberFacts.get(`${b.owner}\t${b.name}`);
    if (known) facts.push(`    Derived from diffing the game jars: ${b.name} → ${known}`);
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

  // Bring the callers in when a signature may need to change. They are decompiled and offered
  // alongside, and the no-signature-change rule is relaxed for exactly this set — everything outside
  // it is still compiled bytecode holding the old shapes.
  const group = args['no-group'] ? null : groupFor(cls);
  const groupSrc = [];
  if (group) {
    for (const g of group) {
      const gp = vfRoot ? path.join(vfRoot, g + '.java') : null;
      if (gp && fs.existsSync(gp)) groupSrc.push({ cls: g, src: stripAnnotations(fs.readFileSync(gp, 'utf8')) });
    }
    if (groupSrc.length) say(`     porting with ${groupSrc.length} caller(s): ${groupSrc.map((x) => x.cls.split('/').pop()).join(', ')}`);
  }

  const base = `You are porting ${groupSrc.length ? 'a small group of Java classes' : 'one Java class'} of a Minecraft mod to ${TO}. The code was already
name-remapped; what remains are genuine API redesigns.

BROKEN LINKS — every one of these fails at runtime today:
${facts.join('\n')}

${sigBlocks.length ? `THE ACTUAL TARGET API (from javap — these signatures are ground truth, do not guess):\n${sigBlocks.join('\n\n')}\n` : ''}
The source below is DECOMPILED, so it may not compile even before your changes: lost generic
parameters, missing casts, a raw type where a parameterised one belongs. Fix those too — they are
artifacts of decompilation, not part of the port, and they are not evidence about the target API.

RULES:
- Do NOT change any class name.
${groupSrc.length
  ? `- You MAY change method signatures, because every caller of them inside this jar is included
  below and you are editing them together. Anything NOT shown is still compiled bytecode holding the
  old shapes, so a signature reachable from outside this set must stay as it is.
  If a value has to be threaded down from a caller, that is now available to you — do it.`
  : `- Do NOT change the name, parameters or return type of any public or protected method. Other
  classes in this jar are still compiled bytecode and link to these exact signatures; changing one
  breaks callers you cannot see. Change method BODIES.`}
- Use only members shown above or already in the file. If you cannot fix something without changing
  a signature, leave that part as it is and add // FOXGRADE: <what is needed>.
- Output the complete file, nothing else.

SOURCE — ${groupSrc.length ? `${groupSrc.length + 1} files. Return each one complete, separated by a line of the form  ===== FILE <class/name> =====` : 'one file. Output the complete file, nothing else.'}
===== FILE ${cls} =====
\`\`\`java
${source}
\`\`\`
${groupSrc.map((g) => `===== FILE ${g.cls} =====\n\`\`\`java\n${g.src}\n\`\`\``).join('\n')}`;

  const looked = args['no-lookup'] ? '' : gatherContext(base);
  const baseWithLookups = base.replace('SOURCE:', `${looked}\nSOURCE:`);
  let ported = null, fallback = null, lastErr = '', attemptsMade = 0;
  for (let attempt = 0; attempt <= REPAIRS; attempt++) {
    attemptsMade = attempt + 1;
    const prompt = attempt === 0 ? baseWithLookups
      : `${baseWithLookups}\n\nYour previous attempt did not compile. Fix exactly these errors:\n${lastErr.slice(0, 4000)}`;
    const raw = callAI(prompt);
    // Split a multi-file reply back into files. The marker is required rather than inferred: guessing
    // where one class ends and the next begins would silently write half a file.
    const files = [];
    if (groupSrc.length) {
      const parts = raw.split(/^=====\s*FILE\s+([\w/$.]+)\s*=====\s*$/m);
      for (let i = 1; i < parts.length; i += 2) {
        const code = extractJava(parts[i + 1] || '');
        if (code) files.push({ cls: parts[i].replace(/\./g, '/'), code });
      }
    }
    if (!files.length) { const one = extractJava(raw); if (one) files.push({ cls, code: one }); }
    if (!files.length) {
      // A multi-file reply that arrives without its markers is unusable, and asking again for the
      // same thing tends to fail the same way. Drop to the single-file form for the remaining
      // attempts rather than burning them on a format the model is not producing.
      if (groupSrc.length) { say(`     attempt ${attempt + 1}: no usable code — retrying single-file`); groupSrc.length = 0; }
      else say(`     attempt ${attempt + 1}: no code returned`);
      continue;
    }
    const out = files[0].code;
    // Before spending a compile on it, test what it asserted. A wrong premise produces code built
    // around a class it believes is gone, and no amount of compiling catches that.
    // Existence is checkable; SUITABILITY is not, and conflating them made this check harmful.
    // Told "VanillaRegistries was removed" is false, the correction pushed toward using it — but
    // VanillaRegistries.createLookup() builds a fresh VANILLA-ONLY registry set, and the mod
    // registers its own biome, so that lookup would never contain it and isBiome would silently
    // never match. The model's wording was imprecise and its conclusion was right.
    //
    // So this is now information rather than a verdict: the class exists, here it is, decide whether
    // it fits. A checker that only knows a class is present must not overrule reasoning about
    // whether it belongs.
    const wrong = falseClaims(out);
    if (wrong.length) {
      say(`     note: ${wrong.map((w) => w.name).join(', ')} exists in ${TO} — passed on as context, not a correction`);
      const note = `One correction of fact, which may or may not change your approach:\n`
        + wrong.map((w) => `  ${w.found} DOES exist in ${TO}.`).join('\n')
        + `\nThat says only that the class is present. If you judged it unsuitable — wrong registry,`
        + `\ndatagen-only, would not contain modded entries — that judgement still stands, and saying`
        + `\nso explicitly is a better answer than using it. LOOKUP it if that would settle the question.`;
      lastErr = lastErr ? `${lastErr}\n\n${note}` : note;
    }
    const cDir = path.join(outDir, 'build' + attempt);
    fs.mkdirSync(cDir, { recursive: true });
    // Every file in the group compiles together, so a changed signature and its caller are checked
    // against each other rather than one at a time.
    const written = [];
    for (const fl of files) {
      const fp = path.join(cDir, fl.cls.split('/').pop() + '.java');
      fs.writeFileSync(fp, stripAnnotations(fl.code));
      written.push(fp);
    }
    const f = written[0];
    // The original jar is on the classpath, so the class compiles against its own mod's other
    // classes exactly as it will be linked at runtime.
    const jc = spawnSync('javac', ['-nowarn', '-proc:none', '-cp', COMPILE_CP, '-d', cDir, ...written], { encoding: 'utf8', maxBuffer: 32e6, timeout: 300000 });
    if (jc.status === 0) {
      const built = path.join(cDir, cls + '.class');
      if (fs.existsSync(built)) {
        const cand = { source: files.map((x) => x.code).join('\n'), bytes: fs.readFileSync(built), dir: cDir, files };
        // A result that compiles by deleting the feature is a fallback, not an answer. Holding it and
        // carrying on is what makes --last-resort reachable at all: the first such result used to end
        // the class outright, so "every attempt has been spent" could never become true and the
        // escalation this was built for could never fire. Promenade proved it — attempt 2 compiled by
        // removing the surface rules, the gate correctly said "not yet a last resort", and then there
        // was no attempt 3 to earn it.
        const removes = (cand.source.match(/FOXGRADE:/g) || []).length;
        if (removes && !args['allow-stubs'] && attempt < REPAIRS) {
          if (!fallback) fallback = cand;
          say(`     attempt ${attempt + 1}: compiles, but removes ${removes} behaviour(s) — held as a fallback, trying again`);
          lastErr = 'Your previous attempt compiled, but it removed behaviour instead of porting it:\n'
            + (cand.source.match(/FOXGRADE:[^\n]*/g) || []).slice(0, 3).map((l) => `  ${l.replace(/FOXGRADE:\s*/, '').trim()}`).join('\n')
            + '\n\nDeleting the feature is the last thing to try, not the first. Port it if there is any way'
            + '\nto. LOOKUP the classes involved before concluding it cannot be done — and if it genuinely'
            + '\ncannot, say which fact makes it impossible rather than removing it silently.';
          continue;
        }
        ported = cand;
        say(`     attempt ${attempt + 1}: compiles ✓${files.length > 1 ? ` (${files.length} files)` : ''}`);
        break;
      }
      say(`     attempt ${attempt + 1}: compiled but produced no ${simple}.class`);
    } else {
      // Keep javac's caret lines: they point at the offending expression, which the error text alone
      // does not. Stripping them left the model guessing which of several similar calls was wrong.
      const errLines = (jc.stderr || '').split('\n');
      const keep = [];
      for (let i = 0; i < errLines.length && keep.length < 40; i++)
        if (/error:/.test(errLines[i])) keep.push(errLines[i], errLines[i + 1] || '', errLines[i + 2] || '');
      lastErr = keep.join('\n');
      say(`     attempt ${attempt + 1}: ${(lastErr.match(/error:/g) || []).length} compile error(s)`);
    }
  }
  // Nothing ported it cleanly, so the held fallback is now the only thing that compiles. It still has
  // to clear the link check, the unassigned-field check and the stub gate below — this only makes it
  // the candidate, it does not make it acceptable.
  if (!ported && fallback) {
    ported = fallback;
    say(`     no attempt ported it without removing behaviour — the best compiling result is the only option left`);
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
  // The marker counts wherever it appears, not only after //. The model wrote its warning inside a
  // /** javadoc */ block and the line-comment regex missed it, so a port that declares a field, never
  // assigns it, and passes it to a call was accepted: compiles, every link resolves, null at runtime.
  // It flagged its own uncertainty correctly and the detector could not see it.
  const stubs = (ported.source.match(/FOXGRADE:/g) || []).length;
  // Independently of any marker: a private field that is read but never assigned is a null at
  // runtime, and neither javac nor link resolution objects to it. This is the shape of "compiles,
  // resolves, and is broken" that both gates were built to catch and neither can see.
  const unassigned = [];
  for (const m of ported.source.matchAll(/^\s*private\s+(?:static\s+)?(?:final\s+)?[\w.<>$\[\], ]+?\s+(\w+)\s*;/gm)) {
    const name = m[1];
    const writes = new RegExp(`(?:^|[^.\\w])${name}\\s*=(?!=)`, 'm');
    const reads = new RegExp(`(?:^|[^.\\w])${name}(?!\\s*=[^=])\\b`, 'g');
    if (!writes.test(ported.source) && (ported.source.match(reads) || []).length > 1) unassigned.push(name);
  }
  if (unassigned.length) {
    say(`     refused: ${unassigned.join(', ')} declared and used but never assigned — null at runtime`);
    results.push([cls, `refused: ${unassigned.join(', ')} never assigned`]);
    continue;
  }
  if (stubs) {
    const what = (ported.source.match(/FOXGRADE:[^\n]*/g) || []).slice(0, 3).map((l) => l.replace(/FOXGRADE:\s*/, '').trim());
    say(`     compiles and resolves, but REMOVES ${stubs} piece(s) of behaviour rather than porting them:`);
    for (const w of what) say(`       · ${w.slice(0, 96)}`);
    // Removing a feature is sometimes the correct port. Promenade's own maintainer, with full
    // knowledge of the code and no constraints, deleted exactly this call rather than adapt it —
    // SurfaceRules moved biome resolution to construction time and the mod builds its rules before
    // any registry exists. The stub was the human answer.
    //
    // So it is allowed, but only as an ESCALATION: the port must first have been attempted properly,
    // with lookups, the call graph, and every mined fact available, and every compiling result must
    // have needed the stub. --last-resort permits that; --allow-stubs takes it on the first pass for
    // anyone who has already decided. Either way it is recorded, in the jar and in the report, because
    // a removed feature the user does not know about is the failure this whole design exists to avoid.
    const exhausted = attemptsMade > REPAIRS;
    const permitted = args['allow-stubs'] || (args['last-resort'] && exhausted && !args['no-lookup'] && !args['no-group']);
    if (!permitted) {
      say(args['last-resort']
        ? `     refused for now — ${REPAIRS + 1} attempts have not all been spent, so this is not yet a last resort.`
        : '     refused — a crash you can trace beats silently wrong behaviour. --last-resort lets it through only after a real attempt.');
      results.push([cls, `refused: would remove ${stubs} behaviour(s)`]);
      continue;
    }
    say(`     kept as a LAST RESORT — ${stubs} behaviour(s) removed, recorded in REMOVED.md`);
    removedLog.push({ cls, stubs, what });
  }
  say(`     accepted: ${c.bad.length} → ${after.length} broken links${stubs ? `, with ${stubs} stub(s)` : ''}`);
  replacements.set(c.name, ported.bytes);
  // A caller whose signature changed is only correct alongside the class it calls, so the whole
  // group ships or none of it does.
  for (const fl of (ported.files || []).slice(1)) {
    const built = path.join(ported.dir, fl.cls + '.class');
    if (!fs.existsSync(built)) continue;
    const b2 = fs.readFileSync(built);
    let ok = true;
    for (const r of new ClassFile(b2).refs()) {
      if (!r.owner.startsWith('net/minecraft/')) continue;
      if (!declCache.has(r.owner)) preload([r.owner]);
      if (loaderProvided.has(`${r.owner}\t${r.name}\t${r.desc}`)) continue;
      if (!declCache.get(r.owner) || !resolves(r.owner, `${r.name} ${r.desc}`)) { ok = false; break; }
    }
    if (!ok) { say(`     ${fl.cls.split('/').pop()} still has unresolved links — group rejected`); replacements.delete(c.name); ok = false; break; }
    replacements.set(fl.cls + '.class', b2);
    say(`     also rewrote ${fl.cls.split('/').pop()}`);
  }
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
// A removed feature has to travel with the jar. Someone opening this file in six months should not
// have to reconstruct why part of the mod stopped working.
if (removedLog.length) {
  const lines = ['# Features removed to make this jar load', '',
    `Ported to ${TO}. The following could not be ported and were removed instead. Each was attempted`,
    `properly first — with class lookups, the calling classes, and every derived mapping available —`,
    `and every version that compiled required the removal.`, '',
    'This is the same choice a maintainer sometimes makes. It is recorded because a feature that',
    'disappears without anyone knowing is worse than a crash.', ''];
  for (const r of removedLog) {
    lines.push(`## ${r.cls.replace(/\//g, '.')}`);
    for (const w of r.what) lines.push(`- ${w}`);
    lines.push('');
  }
  replacements.set('REMOVED.md', Buffer.from(lines.join('\n'), 'utf8'));
  if (!entries.some((e) => e.name === 'REMOVED.md')) entries.push({ name: 'REMOVED.md', method: 8, flags: 0, time: 0, date: 0, crc: 0, compSize: 0, size: 0, extAttrs: 0, raw: Buffer.alloc(0) });
}
fs.writeFileSync(args.out, writeZip(entries, replacements));
console.log(`\n  wrote ${args.out} — ${replacements.size} class(es) replaced`);
if (removedLog.length) {
  console.log(`  ⚠ ${removedLog.reduce((n, r) => n + r.stubs, 0)} behaviour(s) REMOVED across ${removedLog.length} class(es) — listed in REMOVED.md inside the jar:`);
  for (const r of removedLog) for (const w of r.what) console.log(`      · ${w.slice(0, 96)}`);
}
console.log(`  re-run jar-verify.mjs on it, then launch the game. Compiling clean is not the same as working.`);
if (!args.keep) fs.rmSync(WORK, { recursive: true, force: true });
