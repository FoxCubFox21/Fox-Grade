#!/usr/bin/env node
// Foxgrade detector — finds installed mods that target an OLDER Minecraft version than you're running.
// Zero AI, zero API key, zero network: it just reads each jar's own declared metadata.
//
// Usage: node detect-outdated.mjs [modsDir] [--target 26.2]
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const argv = process.argv.slice(2);
let target = null;
const positional = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--target') target = argv[++i];
  else positional.push(argv[i]);
}
const modsDir = positional[0] || path.join(process.env.HOME, 'Library/Application Support/minecraft/mods');

// Read one file out of a jar without extracting it.
function fromJar(jar, entry) {
  const r = spawnSync('unzip', ['-p', jar, entry], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  return r.status === 0 && r.stdout ? r.stdout : null;
}

// Turn a version predicate (">=1.20", "~1.21.4", "1.19.x", "[1.20,1.21)") into a comparable number list.
function versionsIn(pred) {
  if (!pred) return [];
  const out = [];
  for (const m of String(pred).matchAll(/(\d+)\.(\d+)(?:\.(\d+))?/g)) out.push([+m[1], +m[2], +(m[3] || 0)]);
  return out;
}
const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
const fmt = (v) => v.join('.');
const parseV = (s) => { const m = String(s).match(/(\d+)\.(\d+)(?:\.(\d+))?/); return m ? [+m[1], +m[2], +(m[3] || 0)] : null; };

// Does `t` satisfy a loader version predicate? Handles the forms mods actually use:
//   ">=26.2-", ">26.1 <26.3", "~26.2", "26.2.x", "26.2", "[1.20,1.21)".
// Crucially an upper bound is EXCLUSIVE: ">26.1 <26.3" means 26.2, NOT 26.3 — reading the 26.3 as a
// supported version made the detector flag 17 perfectly good mods as outdated.
function satisfies(t, pred) {
  if (!pred) return null;
  const s = String(pred).trim();
  if (s === '*' || s === '') return true;
  let ok = null;
  // maven-style ranges [a,b) / (a,b]
  for (const m of s.matchAll(/([\[\(])\s*([\d.]+)?\s*,\s*([\d.]+)?\s*([\]\)])/g)) {
    const lo = m[2] && parseV(m[2]), hi = m[3] && parseV(m[3]);
    let r = true;
    if (lo) r = r && (m[1] === '[' ? cmp(t, lo) >= 0 : cmp(t, lo) > 0);
    if (hi) r = r && (m[4] === ']' ? cmp(t, hi) <= 0 : cmp(t, hi) < 0);
    ok = ok === null ? r : ok && r;
  }
  if (ok !== null) return ok;
  const toks = s.split(/\s+|\s*(?:&&|,)\s*/).filter(Boolean);
  for (const tok of toks) {
    const v = parseV(tok);
    if (!v) continue;
    let r;
    if (/^>=/.test(tok)) r = cmp(t, v) >= 0;
    else if (/^>/.test(tok)) r = cmp(t, v) > 0;
    else if (/^<=/.test(tok)) r = cmp(t, v) <= 0;
    else if (/^</.test(tok)) r = cmp(t, v) < 0;                       // exclusive upper bound
    else if (/^[~^]/.test(tok)) r = t[0] === v[0] && t[1] === v[1];   // ~26.2 -> any 26.2.z
    else if (/\.x$/i.test(tok)) r = t[0] === v[0] && t[1] === v[1];   // 26.2.x
    else r = t[0] === v[0] && t[1] === v[1];                          // bare 26.2 -> that minor line
    ok = ok === null ? r : ok && r;
  }
  return ok;
}

// The version you actually RUN, from the installed Minecraft versions folder (not from mod metadata).
function installedTarget() {
  const vd = path.join(process.env.HOME, 'Library/Application Support/minecraft/versions');
  if (!fs.existsSync(vd)) return null;
  const vs = fs.readdirSync(vd).map(parseV).filter(Boolean).sort(cmp);
  return vs.pop() || null;
}

function readMod(jar) {
  const fmj = fromJar(jar, 'fabric.mod.json');
  if (fmj) {
    try {
      const j = JSON.parse(fmj.replace(/,\s*([}\]])/g, '$1'));
      const dep = j.depends?.minecraft ?? j.recommends?.minecraft;
      const pred = Array.isArray(dep) ? dep.join(' ') : dep;
      return { loader: 'Fabric', id: j.id, name: j.name || j.id, version: j.version, mcPred: pred };
    } catch { return { loader: 'Fabric', id: path.basename(jar), name: path.basename(jar), mcPred: null, note: 'unparsable fabric.mod.json' }; }
  }
  const toml = fromJar(jar, 'META-INF/mods.toml') || fromJar(jar, 'META-INF/neoforge.mods.toml');
  if (toml) {
    const id = (toml.match(/modId\s*=\s*"([^"]+)"/) || [])[1];
    const mc = [...toml.matchAll(/\[\[dependencies[^\]]*\]\][\s\S]*?modId\s*=\s*"minecraft"[\s\S]*?versionRange\s*=\s*"([^"]+)"/g)].map((m) => m[1])[0];
    return { loader: 'Forge/NeoForge', id, name: id || path.basename(jar), mcPred: mc };
  }
  return { loader: '?', id: path.basename(jar), name: path.basename(jar), mcPred: null, note: 'no mod metadata found' };
}

if (!fs.existsSync(modsDir)) { console.error(`No mods folder at ${modsDir}`); process.exit(1); }
const jars = fs.readdirSync(modsDir).filter((f) => f.toLowerCase().endsWith('.jar')).sort();
if (!jars.length) { console.log(`No .jar files in ${modsDir}`); process.exit(0); }

// Infer the target version from the mods themselves if not supplied: the highest version any mod declares.
const mods = jars.map((f) => ({ file: f, ...readMod(path.join(modsDir, f)) }));
// Which version is this mod SET actually built for? Not "highest installed" — you can have a 26.3
// folder sitting there while every mod you run is 26.2. Pick the candidate version that satisfies the
// most mods; that's the profile you're really playing. --target overrides.
function consensusTarget(mods) {
  const cands = [...new Set(mods.flatMap((m) => versionsIn(m.mcPred)).map(fmt))].map(parseV).filter(Boolean);
  const inst = installedTarget();
  if (inst) cands.push(inst);
  let best = null, bestN = -1;
  for (const c of cands.sort(cmp)) {
    const n = mods.filter((m) => satisfies(c, m.mcPred) === true).length;
    if (n > bestN || (n === bestN && best && cmp(c, best) > 0)) { best = c; bestN = n; }
  }
  return best;
}

let targetV = target ? parseV(target) : null;
let how = target ? '' : '  (version this mod set is built for)';
if (!targetV) targetV = consensusTarget(mods);
if (!targetV) { targetV = installedTarget(); how = '  (highest installed)'; }

console.log(`Mods folder : ${modsDir}`);
console.log(`Target MC   : ${fmt(targetV || [0, 0, 0])}${how}`);
console.log(`Scanned     : ${jars.length} jar(s)\n`);

const rows = mods.map((m) => {
  let status = 'unknown', delta = '';
  if (!m.mcPred) status = 'no MC declared';
  else {
    const sat = satisfies(targetV, m.mcPred);
    if (sat === null) status = 'unparsed';
    else if (sat) status = 'current';
    else {
      status = 'OUTDATED';
      const newest = versionsIn(m.mcPred).sort(cmp).pop();
      delta = newest ? `targets ${fmt(newest)}` : '';
    }
  }
  return { ...m, status, delta };
});

const outdated = rows.filter((r) => r.status === 'OUTDATED');
const pad = Math.min(42, Math.max(...rows.map((r) => (r.name || '').length)) + 1);
for (const r of rows.sort((a, b) => (a.status === 'OUTDATED' ? -1 : 1) - (b.status === 'OUTDATED' ? -1 : 1))) {
  const tag = r.status === 'OUTDATED' ? '⚠ OUTDATED' : r.status === 'current' ? '  ok      ' : '  ?       ';
  console.log(`${tag} ${String(r.name).slice(0, pad).padEnd(pad)} ${String(r.mcPred ?? r.note ?? '').slice(0, 26).padEnd(26)} ${r.delta}`);
}

console.log(`\n${outdated.length} of ${rows.length} mod(s) target an older Minecraft than ${fmt(targetV)}.`);
if (outdated.length) {
  console.log('These will usually refuse to load (or crash) on the newer version.');
  console.log('Foxgrade can port one you have the SOURCE for:');
  console.log('  node foxgrade.mjs <mod-source-dir> --from <old> --to ' + fmt(targetV) + ' --verify --classpath "<target jars>"');
  console.log('(porting needs source + the right to modify it — it cannot rewrite a compiled jar.)');
}
