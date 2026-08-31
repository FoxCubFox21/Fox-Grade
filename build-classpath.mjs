#!/usr/bin/env node
// Build a classpath for the target version, with exactly ONE source of Minecraft classes.
//
// This exists because of a silent contamination that invalidated two rounds of measurement.
// Installing NeoForge put a complete 1.21.1 client jar into the launcher's SHARED libraries folder:
//
//   libraries/net/minecraft/client/1.21.1-.../client-1.21.1-...-srg.jar
//   libraries/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-client.jar
//
// A classpath built by sweeping libraries/ then resolves 1.21.1 classes as though they were 26.2.
// ResourceLocation "still exists", every link to it passes, and a port that should have been marked
// broken reports clean. The failure is invisible: nothing errors, the numbers just get better.
//
// So: the version jar is the only permitted source of net/minecraft, and any library carrying game
// classes is refused by name and reported, rather than quietly skipped.
//
//   node build-classpath.mjs --version 26.2 --out /tmp/foxgrade_cp.txt
import fs from 'node:fs';
import path from 'node:path';
import { readZip } from './zipfile.mjs';

const args = {};
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; if (t.startsWith('--')) args[t.slice(2)] = process.argv[++i]; }
const VERSION = args.version || '26.2';
const MC = args.mc || `${process.env.HOME}/Library/Application Support/minecraft`;
const OUT = args.out || '/tmp/foxgrade_cp.txt';

const gameJar = path.join(MC, 'versions', VERSION, `${VERSION}.jar`);
if (!fs.existsSync(gameJar)) { console.error(`no game jar at ${gameJar}`); process.exit(2); }

const carriesGameClasses = (jar) => {
  try { for (const e of readZip(fs.readFileSync(jar))) if (e.name.startsWith('net/minecraft/') && e.name.endsWith('.class')) return true; }
  catch { /* unreadable jars cannot contaminate anything */ }
  return false;
};

const cp = [gameJar], rejected = [];
const walk = (d) => {
  if (!fs.existsSync(d)) return;
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    if (!e.name.endsWith('.jar')) continue;
    if (carriesGameClasses(p)) { rejected.push(p); continue; }
    cp.push(p);
  }
};
walk(path.join(MC, 'libraries'));
// Mods belong on the classpath: Fabric API adds methods to vanilla classes, and leaving it off turns
// every one of those into a phantom broken link.
if (!args['no-mods']) walk(path.join(MC, 'mods'));

fs.writeFileSync(OUT, cp.join(':'));
console.log(`  ${VERSION}: ${cp.length} classpath entries -> ${OUT}`);
if (rejected.length) {
  console.log(`  REFUSED ${rejected.length} jar(s) carrying their own net/minecraft classes:`);
  for (const r of rejected.slice(0, 6)) console.log(`    ✗ ${path.relative(MC, r)}`);
  console.log('  Those would have answered version questions for a version you are not testing.');
}
