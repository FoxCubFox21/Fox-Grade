#!/usr/bin/env node
// Fox-Grade — point it at your mods and it sorts them out.
//
//     node foxgrade.mjs
//
// No flags, no classpath, no version numbers. It finds your Minecraft install, reads which mods are
// built for an older version, gets the author's own update where one exists, ports the rest, and
// tells you in plain words what happened.
//
// The rules it holds itself to, because a person's mods folder is not a scratch directory:
//   · nothing is ever deleted — originals move to mods-backup/ before anything is installed
//   · nothing that failed a check is installed, ever. "I could not port this" is a fine answer;
//     a mod that loads and quietly misbehaves is not
//   · an official build from the mod's own author always beats a port of ours
//   · every failure says what the PERSON should do about it, not what went wrong inside a jar
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readZip, inflateEntry } from './zipfile.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = (process.argv[i + 1] || '').startsWith('--') || !process.argv[i + 1] ? true : process.argv[++i]; }
const DRY = args.dry || args['dry-run'];

const c = { b: (s) => `\x1b[1m${s}\x1b[0m`, dim: (s) => `\x1b[2m${s}\x1b[0m`, g: (s) => `\x1b[32m${s}\x1b[0m`, y: (s) => `\x1b[33m${s}\x1b[0m`, r: (s) => `\x1b[31m${s}\x1b[0m` };
const say = (s = '') => console.log(s);

// ── 1. find Minecraft, without asking ────────────────────────────────────────────────────────
function minecraftRoot() {
  if (args.dir) return args.dir;
  const guesses = process.platform === 'darwin'
    ? [path.join(os.homedir(), 'Library/Application Support/minecraft')]
    : process.platform === 'win32'
      ? [path.join(process.env.APPDATA || '', '.minecraft')]
      : [path.join(os.homedir(), '.minecraft')];
  return guesses.find((g) => fs.existsSync(g)) || null;
}
const ROOT = minecraftRoot();
if (!ROOT) {
  say(c.r("I couldn't find your Minecraft folder."));
  say('  Tell me where it is:  node foxgrade.mjs --dir "/path/to/.minecraft"');
  process.exit(1);
}

// Which profile? The one whose mods we should port is the Fabric profile most recently played;
// asking a person to name a profile is asking them to know something the file already knows.
function pickProfile() {
  if (args.mods) return { mods: args.mods, version: args.version || null, name: 'the folder you named' };
  const pf = path.join(ROOT, 'launcher_profiles.json');
  if (!fs.existsSync(pf)) return { mods: path.join(ROOT, 'mods'), version: args.version || null, name: 'your default install' };
  let profiles = [];
  try { profiles = Object.values(JSON.parse(fs.readFileSync(pf, 'utf8')).profiles || {}); } catch { /* fall through */ }
  const fabric = profiles
    .filter((p) => /^fabric-loader-/.test(p.lastVersionId || ''))
    .map((p) => ({ ...p, mc: (p.lastVersionId.match(/^fabric-loader-[\d.]+-(.+)$/) || [])[1], used: p.lastUsed || '' }))
    .filter((p) => p.mc)
    .sort((a, b) => (b.used > a.used ? 1 : -1));
  if (!fabric.length) return { mods: path.join(ROOT, 'mods'), version: args.version || null, name: 'your default install' };
  const p = fabric[0];
  const dir = p.gameDir || ROOT;
  return { mods: path.join(dir, 'mods'), version: args.version || p.mc, name: p.name || p.mc };
}
const PROFILE = pickProfile();
if (!fs.existsSync(PROFILE.mods)) {
  say(c.r(`No mods folder at ${PROFILE.mods}`));
  say('  If your mods live elsewhere:  node foxgrade.mjs --mods "/path/to/mods"');
  process.exit(1);
}
const TARGET = PROFILE.version;
say(`${c.b('Fox-Grade')} ${c.dim('— checking your mods')}`);
say(`  install : ${PROFILE.name}   ${c.dim(PROFILE.mods.replace(os.homedir(), '~'))}`);
say(`  version : Minecraft ${TARGET}`);

// ── 2. read every mod, decide what it needs ──────────────────────────────────────────────────
// A range like ">=26.1" already covers 26.2; only a mod that genuinely excludes this version needs
// anything. Getting this wrong in the eager direction would "port" mods that were already fine.
function coversTarget(range, target) {
  if (!range) return true;
  const r = String(range).trim();
  if (r === '*' || r === target) return true;
  if (r.includes(target)) return true;
  const m = r.match(/^>=\s*([\d.]+)/);
  if (m) return cmpVer(target, m[1]) >= 0;
  const x = r.match(/^([\d.]+)\.x$/);
  if (x) return target.startsWith(x[1] + '.');
  return false;
}
const cmpVer = (a, b) => {
  const pa = String(a).split(/[.-]/).map(Number), pb = String(b).split(/[.-]/).map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) { const d = (pa[i] || 0) - (pb[i] || 0); if (d) return d; }
  return 0;
};

const mods = [];
for (const f of fs.readdirSync(PROFILE.mods)) {
  if (!f.endsWith('.jar')) continue;
  const full = path.join(PROFILE.mods, f);
  let meta = null;
  try { for (const e of readZip(fs.readFileSync(full))) if (e.name === 'fabric.mod.json') { meta = JSON.parse(inflateEntry(e).toString('utf8')); break; } } catch { /* unreadable */ }
  if (!meta) { mods.push({ file: f, full, kind: 'not-a-fabric-mod' }); continue; }
  const range = (meta.depends || {}).minecraft;
  // A manifest that names no Minecraft version is common — AppleSkin ships without one — and a
  // range is only a claim anyway. What decides whether a mod works is whether the code it calls
  // still exists, so ask the jar, not the manifest: if any link fails to resolve, it needs porting
  // whatever the range says. Manifest-covered mods are still checked; that is how a mod whose
  // range lies gets caught instead of silently half-working in someone's game.
  mods.push({ file: f, full, id: meta.id, name: meta.name || meta.id, range, ok: null });
}
// Resolve every mod's links against the target once. This is the honest test and it is cheap.
const cpFileEarly = path.join(os.tmpdir(), `foxgrade_cp_${TARGET}.txt`);
spawnSync('node', [path.join(HERE, 'build-classpath.mjs'), '--version', TARGET, '--out', cpFileEarly], { encoding: 'utf8' });
if (!fs.existsSync(cpFileEarly)) {
  say(c.r(`  I need Minecraft ${TARGET} downloaded before I can check anything.`));
  say('  Open the launcher, play that version once, then run me again.');
  process.exit(1);
}
const CP = fs.readFileSync(cpFileEarly, 'utf8').trim();
process.stdout.write(c.dim('  checking each mod against this version…'));
for (const m of mods) {
  if (!m.id) continue;
  const v = spawnSync('node', [path.join(HERE, 'jar-verify.mjs'), m.full, '--classpath', CP], { encoding: 'utf8', maxBuffer: 64e6 }).stdout || '';
  const links = +((v.match(/^\s*(\d+) link\(s\) will fail/m) || [])[1] ?? (/every link resolves/.test(v) ? 0 : NaN));
  m.links = Number.isNaN(links) ? 0 : links;                 // unmeasurable: trust the manifest
  m.ok = m.links === 0 && coversTarget(m.range, TARGET);
}
process.stdout.write('\r' + ' '.repeat(46) + '\r');
const needy = mods.filter((m) => m.id && !m.ok);
const fine = mods.filter((m) => m.id && m.ok);
say(`  mods    : ${mods.length} found — ${c.g(`${fine.length} already fine`)}${needy.length ? `, ${c.y(`${needy.length} built for an older version`)}` : ''}`);
if (!needy.length) { say(`\n${c.g('Nothing to do — every mod already supports this version.')}`); process.exit(0); }
say('');

// ── 3. the author's own build always wins ────────────────────────────────────────────────────
const UA = { 'User-Agent': 'FoxGrade (personal mod updating)' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function officialUpdate(m) {
  try {
    const crypto = await import('node:crypto');
    const sha1 = crypto.createHash('sha1').update(fs.readFileSync(m.full)).digest('hex');
    const ver = await (await fetch(`https://api.modrinth.com/v2/version_file/${sha1}?algorithm=sha1`, { headers: UA })).json().catch(() => null);
    if (!ver || !ver.project_id) return null;
    await sleep(200);
    const builds = await (await fetch(`https://api.modrinth.com/v2/project/${ver.project_id}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TARGET}%22%5D`, { headers: UA })).json().catch(() => null);
    if (!Array.isArray(builds) || !builds.length) return null;
    const file = builds[0].files.find((x) => x.primary) || builds[0].files[0];
    return { name: file.filename, url: file.url };
  } catch { return null; }
}

const BACKUP = path.join(path.dirname(PROFILE.mods), 'mods-backup');
const plan = [];
say(c.dim('  Checking whether the authors have already updated these…'));
for (const m of needy) {
  const off = await officialUpdate(m);
  plan.push({ ...m, official: off });
  say(`   ${off ? c.g('✓') : '·'} ${m.name}${off ? `  →  the author has a ${TARGET} build` : '  →  no official update; I will try to port it'}`);
}
say('');

// ── 4. port what has no official build ───────────────────────────────────────────────────────
const toPort = plan.filter((p) => !p.official);
const results = [];
if (toPort.length) {
  const cpFile = path.join(os.tmpdir(), `foxgrade_cp_${TARGET}.txt`);
  const bc = spawnSync('node', [path.join(HERE, 'build-classpath.mjs'), '--version', TARGET, '--out', cpFile], { encoding: 'utf8' });
  if (!fs.existsSync(cpFile)) {
    say(c.r(`  I need Minecraft ${TARGET} downloaded before I can port anything.`));
    say('  Open the launcher, play that version once, then run me again.');
    process.exit(1);
  }
  const cp = fs.readFileSync(cpFile, 'utf8').trim();
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'foxgrade-'));
  for (const m of toPort) {
    process.stdout.write(`   … porting ${m.name}`);
    const out = path.join(work, m.file);
    const from = (String(m.range).match(/[\d.]+/) || [])[0] || null;
    spawnSync('node', [path.join(HERE, 'port-pipeline.mjs'), m.full, ...(from ? ['--from', from] : []), '--to', TARGET, '--classpath', cp, '--out', out, '--work', work], { encoding: 'utf8', maxBuffer: 128e6, timeout: 900000 });
    if (!fs.existsSync(out)) { say(`\r   ${c.y('·')} ${m.name}: ${c.y("I couldn't port this one")}                    `); results.push({ ...m, state: 'failed', why: 'the porter could not produce a build' }); continue; }
    // The gates decide, not the porter. Anything that fails them is not installed.
    const v = spawnSync('node', [path.join(HERE, 'jar-verify.mjs'), out, '--classpath', cp], { encoding: 'utf8', maxBuffer: 64e6 }).stdout || '';
    const mx = spawnSync('node', [path.join(HERE, 'mixin-check.mjs'), out, '--classpath', cp], { encoding: 'utf8', maxBuffer: 64e6 }).stdout || '';
    const links = +((v.match(/^\s*(\d+) link\(s\) will fail/m) || [])[1] ?? (/every link resolves/.test(v) ? 0 : NaN));
    const mixins = /no mixins in this jar/.test(mx) ? 0 : +((mx.match(/PROBLEMS\s*:\s*(\d+)/) || [])[1] ?? NaN);
    if (links === 0 && mixins === 0) { say(`\r   ${c.g('✓')} ${m.name}: ported                                   `); results.push({ ...m, state: 'ported', jar: out }); }
    else {
      const renderer = /MultiBufferSource|SubmitNode|OutlineBuffer|RenderPipeline/.test(v + mx);
      say(`\r   ${c.y('·')} ${m.name}: ${c.y('needs its author')}                          `);
      results.push({ ...m, state: 'failed', why: renderer ? 'it hooks into rendering code Minecraft rewrote' : 'parts of it use game code that no longer exists' });
    }
  }
}

// ── 5. install, keeping every original ───────────────────────────────────────────────────────
const installs = [...plan.filter((p) => p.official), ...results.filter((r) => r.state === 'ported')];

// A mod whose library is missing stops Minecraft at a wall of red text before anything loads, and
// that is the tool's failure to finish the job, not the person's to debug. Anything an installed
// mod declares as a dependency is fetched for the target version if it is not already present.
const present = new Set();
for (const f of fs.readdirSync(PROFILE.mods)) {
  if (!f.endsWith('.jar')) continue;
  try { for (const e of readZip(fs.readFileSync(path.join(PROFILE.mods, f)))) if (e.name === 'fabric.mod.json') present.add(JSON.parse(inflateEntry(e).toString('utf8')).id); } catch { /* skip */ }
}
const SKIP_DEPS = new Set(['minecraft', 'java', 'fabricloader', 'fabric', 'fabric-api', 'fabric-language-kotlin']);
const missingDeps = new Set();
for (const m of installs) {
  // Read from the jar we are INSTALLING (a ported build, or the current file for an official
  // update whose replacement is not downloaded yet) and do it before the install step moves
  // anything: reading m.full after the rename silently found nothing.
  const src = m.official ? m.full : m.jar;
  try {
    for (const e of readZip(fs.readFileSync(src))) {
      if (e.name !== 'fabric.mod.json') continue;
      for (const d of Object.keys(JSON.parse(inflateEntry(e).toString('utf8')).depends || {}))
        if (!SKIP_DEPS.has(d) && !present.has(d)) missingDeps.add(d);
    }
  } catch { /* skip */ }
}
const fetchedDeps = [];
if (missingDeps.size && !DRY) {
  for (const id of missingDeps) {
    const r = spawnSync('node', [path.join(HERE, 'fetch-deps-by-id.mjs'), id, '--to', TARGET, '--out', PROFILE.mods], { encoding: 'utf8', timeout: 120000 });
    if ((r.stdout || '').includes('OK ')) fetchedDeps.push(id);
  }
}
say('');
if (DRY) { say(c.dim('  (dry run — nothing was changed)')); }
else if (installs.length) {
  fs.mkdirSync(BACKUP, { recursive: true });
  for (const m of installs) {
    fs.renameSync(m.full, path.join(BACKUP, m.file));
    if (m.official) {
      const buf = Buffer.from(await (await fetch(m.official.url, { headers: UA })).arrayBuffer());
      fs.writeFileSync(path.join(PROFILE.mods, m.official.name), buf);
    } else fs.copyFileSync(m.jar, path.join(PROFILE.mods, m.file.replace(/\.jar$/, `-foxgrade-${TARGET}.jar`)));
  }
}

// ── 6. tell a person what happened ───────────────────────────────────────────────────────────
const off = installs.filter((i) => i.official).length, ported = installs.filter((i) => !i.official).length;
const failed = results.filter((r) => r.state === 'failed');
say(c.b('  Done.'));
if (off) say(`   ${c.g(`${off} updated`)} to the author's own ${TARGET} build`);
if (ported) say(`   ${c.g(`${ported} ported`)} by Fox-Grade — these are new builds, so play-test them`);
if (failed.length) {
  say(`   ${c.y(`${failed.length} left alone`)}, still in your mods folder as they were:`);
  for (const f of failed) say(`      ${f.name} — ${f.why}. ${/rendering/.test(f.why) ? 'Wait for its author to update it.' : 'Check its page for a newer build.'}`);
}
if (fetchedDeps.length) say(`   ${c.g(`${fetchedDeps.length} library mod(s)`)} downloaded because the updated mods need them`);
if (installs.length && !DRY) {
  say('');
  say(`  Your original files are safe in ${c.dim(BACKUP.replace(os.homedir(), '~'))} — delete that folder when you are happy.`);
  say(`  ${c.b('Restart Minecraft to load the updated mods.')}`);
  if (ported) say(c.dim('  A ported mod compiles and links correctly, which is not the same as being tested. Play it and see.'));
}
