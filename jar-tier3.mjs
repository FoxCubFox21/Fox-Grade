#!/usr/bin/env node
// Tier 3: the investigation rung — an AGENT with tools, for what single calls could not crack.
//
// Tier 2 is a consultation: one prompt, a narrow LOOKUP menu, four attempts. The classes that
// survive it fail for a reason no prompt can pre-package — the answer lives somewhere in the jars
// and has to be FOUND. ambient-environment's diagnosis took javap across two game versions, a
// live-reader scan and a caller-graph diff; no single call was ever going to do that. This rung
// hands the class to an agent that can run those tools itself, inside a workspace, on a budget,
// and then judges the result with exactly the gates a human port would face.
//
// The agent is not trusted — the gates are. It can investigate however it likes; what comes back
// must compile, must not lose injectors, must not stub silently, and must fix more than it breaks.
//
//   node jar-tier3.mjs mod.jar --classes a/b/C,d/e/F --classpath "..." --source-classpath old.jar
//                      --out fixed.jar [--budget 1200] [--corpus dir] [--deps-dir dir]
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry, writeZip } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!JAR || !cp || !args.classes) { console.error('usage: node jar-tier3.mjs <jar> --classes a/b/C[,..] --classpath "..." [--source-classpath old.jar] --out out.jar'); process.exit(2); }
const BUDGET = +(args.budget || 1200);
const OUT = args.out || JAR.replace(/\.jar$/, '.t3.jar');
const VINEFLOWER = [path.join(HERE, 'vineflower.jar')].find((x) => fs.existsSync(x));
const say = (s) => process.stderr.write(s + '\n');

// deps for the compile classpath, same shape as tier2
const LIBS = fs.mkdtempSync(path.join(process.env.HOME, '.foxgrade-t3-libs-'));
const extra = [];
for (const j of [JAR, ...cp.split(':')]) {
  if (!j.endsWith('.jar') || !fs.existsSync(j)) continue;
  try {
    for (const e of readZip(fs.readFileSync(j)).filter((x) => /^META-INF\/jars\/.+\.jar$/.test(x.name))) {
      const dest = path.join(LIBS, path.basename(e.name));
      if (!fs.existsSync(dest)) fs.writeFileSync(dest, inflateEntry(e));
      extra.push(dest);
    }
  } catch { /* skip */ }
}
const deps = [];
if (args['deps-dir'] && fs.existsSync(args['deps-dir'])) for (const f of fs.readdirSync(args['deps-dir'])) if (f.endsWith('.jar')) deps.push(path.join(args['deps-dir'], f));
const COMPILE_CP = [JAR, cp, ...extra, ...deps].join(':');

const jarBuf = fs.readFileSync(JAR);
const entries = [...readZip(jarBuf)];
const replacements = new Map();
const results = [];

const countInjectors = (buf) => {
  let n = 0;
  try { for (const { value } of new ClassFile(buf).utf8()) if (/^Lorg\/spongepowered\/asm\/mixin\/injection\/(Inject|ModifyVariable|Redirect|ModifyArg|ModifyArgs|ModifyConstant);$|^Lcom\/llamalad7\/mixinextras\/injector\//.test(value)) n++; } catch { /* zero */ }
  return n;
};

for (const cls of args.classes.split(',').map((s) => s.trim()).filter(Boolean)) {
  const simple = cls.split('/').pop();
  const e = entries.find((x) => x.name === cls + '.class');
  if (!e) { say(`  ── ${simple}: not in the jar`); results.push([cls, 'absent']); continue; }
  say(`  ── ${simple}`);

  // The workspace: decompiled source, both classpaths named, and the mission.
  const W = fs.mkdtempSync(path.join(process.env.HOME, `.foxgrade-t3-${simple}-`));
  spawnSync('java', ['-jar', VINEFLOWER, JAR, W, `--only=${cls}`], { encoding: 'utf8', maxBuffer: 128e6, timeout: 300000 });
  const srcPath = path.join(W, cls + '.java');
  if (!fs.existsSync(srcPath)) { say('     decompile failed'); results.push([cls, 'no source']); continue; }
  fs.copyFileSync(srcPath, path.join(W, 'ORIGINAL.java'));
  fs.writeFileSync(path.join(W, 'classpath.txt'), COMPILE_CP);
  if (args['source-classpath']) fs.writeFileSync(path.join(W, 'old-version-jar.txt'), args['source-classpath']);
  const diagnosis = args.diagnosis && fs.existsSync(args.diagnosis) ? fs.readFileSync(args.diagnosis, 'utf8') : '';

  fs.writeFileSync(path.join(W, 'MISSION.md'), `# Port ${cls.replace(/\//g, '.')} to Minecraft ${args.to || '26.2'}

ORIGINAL.java is the decompiled class from a build for the PREVIOUS version. It is broken on the
target version. Your job: write PORT.java in this directory — the same class, same name, same
package, working against the target.

## Investigate before you write. That is why you have tools.
- The TARGET version's classes: javap -p -cp "$(cat classpath.txt)" <dotted.name>
- The OLD version (what the code was written against): javap -p -cp "$(cat old-version-jar.txt)" <dotted.name>
- Diff the two for any class you touch. Never assume a member exists — look.
- When something seems deleted, search for where it WENT: same descriptor elsewhere, a delegate
  field away, a renamed sibling. The previous rungs' diagnosis (below) may already name it.
- Distrust single signals. A method that exists but nothing calls is as dead as a missing one.

## Diagnosis from the previous rungs
${diagnosis || '(none recorded — the class failed compilation attempts without a structured diagnosis)'}

## Hard rules — the gates that will judge PORT.java enforce all of these mechanically
- Do NOT change the class name or package. Do NOT touch any other file's contract.
- NEVER delete a feature to make it compile. If something truly has no successor, keep the code
  path and mark the line with a comment starting FOXGRADE: explaining what is missing. A crash you
  can trace beats silently wrong behaviour.
- If this is a Mixin class: keep EVERY handler method and EVERY injector annotation. @At strings
  use descriptor form ("Lpkg/Cls;name:Ldesc;" fields, "Lpkg/Cls;name(args)ret" methods) and must
  name members that exist in the TARGET — verify each with javap. A @Shadow may only name members
  the target class really declares.
- Write NOTES.md with the evidence for each decision: what you looked up, what it showed.

When PORT.java is complete and you believe it compiles against classpath.txt, you are done.`);

  // The agent. Tools are the point; the allowlist is the leash.
  const t0 = Date.now();
  const r = spawnSync('claude', ['-p',
    'Read MISSION.md and carry it out. Investigate with the tools before writing PORT.java.',
    '--allowedTools', 'Bash(javap:*)', 'Bash(cat:*)', 'Bash(ls:*)', 'Bash(grep:*)', 'Read', 'Write', 'Edit', 'Grep', 'Glob'],
    { cwd: W, encoding: 'utf8', maxBuffer: 64e6, timeout: BUDGET * 1000 });
  const spent = Math.round((Date.now() - t0) / 1000);
  const portPath = path.join(W, 'PORT.java');
  if (!fs.existsSync(portPath)) {
    say(`     no PORT.java after ${spent}s${r.error ? ` (${r.error.code})` : ''} — nothing to judge`);
    results.push([cls, 'no port produced']); continue;
  }
  say(`     agent finished in ${spent}s — judging PORT.java`);

  // ── the gates, same teeth as tier2 ──
  const buildDir = path.join(W, 'build');
  fs.mkdirSync(buildDir, { recursive: true });
  const jc = spawnSync('javac', ['-nowarn', '-proc:none', '-cp', COMPILE_CP, '-d', buildDir, portPath], { encoding: 'utf8', maxBuffer: 32e6, timeout: 300000 });
  if (jc.status !== 0) {
    say(`     rejected: does not compile (${((jc.stderr || '').match(/error:/g) || []).length} error(s))`);
    results.push([cls, 'did not compile']); continue;
  }
  const built = path.join(buildDir, cls + '.class');
  if (!fs.existsSync(built)) { say('     rejected: compiled but produced no class file'); results.push([cls, 'no class emitted']); continue; }
  const newBytes = fs.readFileSync(built);
  const src = fs.readFileSync(portPath, 'utf8');

  const before = countInjectors(inflateEntry(e)), after = countInjectors(newBytes);
  if (after < before) { say(`     rejected: ${before} injector(s) became ${after} — a dropped injector is a feature dying silently`); results.push([cls, 'dropped injectors']); continue; }

  const shadowed = new Set();
  for (const m of src.matchAll(/@Shadow[\s\S]{0,160}?(?:private|protected|public)[^;=(]*?\b(\w+)\s*;/g)) shadowed.add(m[1]);
  const unassigned = [];
  for (const m of src.matchAll(/^\s*private\s+(?:static\s+)?(?:final\s+)?[\w.<>$\[\], ]+?\s+(\w+)\s*;/gm)) {
    if (shadowed.has(m[1])) continue;
    if (!new RegExp(`(?:^|[^.\\w])${m[1]}\\s*=(?!=)`, 'm').test(src) && (src.match(new RegExp(`(?:^|[^.\\w])${m[1]}(?!\\s*=[^=])\\b`, 'g')) || []).length > 1) unassigned.push(m[1]);
  }
  if (unassigned.length) { say(`     rejected: ${unassigned.join(', ')} declared and used but never assigned`); results.push([cls, 'null fields']); continue; }

  const stubs = (src.match(/FOXGRADE:/g) || []).length;
  if (stubs && !args['allow-stubs']) { say(`     refused: marks ${stubs} thing(s) as unportable — needs --allow-stubs or a decision`); results.push([cls, `refused: ${stubs} stub(s)`]); continue; }

  replacements.set(e.name, newBytes);
  for (const f of fs.readdirSync(path.dirname(built))) {
    if (f.startsWith(simple + '$') && f.endsWith('.class')) replacements.set(path.dirname(cls + '.class') === '.' ? f : `${cls.slice(0, cls.lastIndexOf('/'))}/${f}`, fs.readFileSync(path.join(path.dirname(built), f)));
  }
  say(`     ACCEPTED${stubs ? ` (with ${stubs} marked stub(s))` : ''} — notes: ${fs.existsSync(path.join(W, 'NOTES.md')) ? path.join(W, 'NOTES.md') : '(none written)'}`);
  results.push([cls, 'accepted']);
}

say('\n  ── results ──');
for (const [c, v] of results) say(`    ${v === 'accepted' ? '✓' : '·'} ${c.split('/').pop().padEnd(28)} ${v}`);
if (!replacements.size) { say('\n  nothing was accepted; the jar is unchanged'); process.exit(0); }
fs.writeFileSync(OUT, writeZip(entries, replacements));
say(`\n  wrote ${OUT} — ${replacements.size} class(es) replaced`);
say('  re-run the full gate stack on it, then play it. An agent that investigated is still not a player.');
