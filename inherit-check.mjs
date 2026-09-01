#!/usr/bin/env node
// Find overrides that DIED — the failure with no crash.
//
// jar-verify resolves every call a mod makes outward; nothing checked the classes a mod extends
// INTO Minecraft. When the super stops declaring a method the mod overrides, nothing breaks loudly:
// the mod's method simply never runs again, and the feature it implemented evaporates. bclib's
// DestructionStructureProcessor.processBlock is exactly this — its structure damage would quietly
// stop happening. A crash you can trace beats silently wrong behaviour, and this is the checker for
// the silent case.
//
// The evidence needs BOTH versions: a method only counts as a dead override if it matched a declared
// super method in the SOURCE version (it really was an override) and matches nothing in the target
// chain (it really died). Checking the target alone would flag every helper method a mod happens to
// declare on a subclass.
//
//   node inherit-check.mjs mod.jar --classpath "<target>" --source-classpath mc-old.jar
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { readZip, inflateEntry } from './zipfile.mjs';
import { ClassFile } from './classfile.mjs';

const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
const cp = args.classpath || (fs.existsSync('/tmp/foxgrade_cp.txt') ? fs.readFileSync('/tmp/foxgrade_cp.txt', 'utf8').trim() : '');
if (!JAR || !cp || !args['source-classpath']) { console.error('usage: node inherit-check.mjs <jar> --classpath "..." --source-classpath mc-old.jar'); process.exit(2); }

const oldIdx = new Map();
for (const j of args['source-classpath'].split(':')) {
  if (!fs.existsSync(j)) continue;
  for (const e of readZip(fs.readFileSync(j))) {
    if (!e.name.endsWith('.class')) continue;
    try { const d = new ClassFile(inflateEntry(e)).declared(); if (d.name) oldIdx.set(d.name, { supers: [d.super, ...d.interfaces], methods: new Set(d.members.filter((m) => m.kind === 'method').map((m) => `${m.name} ${m.desc}`)) }); } catch { /* skip */ }
  }
}
// The override may be declared levels above the direct super, so membership is checked up the
// SOURCE hierarchy, not only on the immediate parent.
function declaredInOldChain(cls, key, seen = new Set()) {
  if (!cls || seen.has(cls) || !cls.startsWith('net/minecraft/')) return false;
  seen.add(cls);
  const d = oldIdx.get(cls);
  if (!d) return false;
  if (d.methods.has(key)) return true;
  return d.supers.some((s) => declaredInOldChain(s, key, seen));
}
const newCache = new Map();
function inNewChain(cls, key) {
  if (!newCache.has(cls)) {
    const r = spawnSync('javap', ['-p', '-s', '-cp', cp, cls.replace(/\//g, '.')], { encoding: 'utf8', maxBuffer: 64e6 });
    const methods = new Set(), supers = [];
    const head = (r.stdout || '').match(/\b(?:class|interface)\s+[\w.$]+([^{]*)\{/);
    if (head) for (const part of head[1].split(/\b(?:extends|implements)\b/).slice(1))
      for (const t of part.split(',')) { const n = t.trim().replace(/<.*/, '').replace(/\./g, '/'); if (/^[\w/$]+$/.test(n)) supers.push(n); }
    const lines = (r.stdout || '').split('\n');
    for (let i = 0; i < lines.length; i++) { const d = lines[i].match(/^\s*descriptor:\s*(\S+)/); if (!d) continue; const nm = (lines[i - 1] || '').match(/([\w$<>]+)\s*\(/); if (nm) methods.add(`${nm[1]} ${d[1]}`); }
    newCache.set(cls, r.status === 0 ? { methods, supers } : null);
  }
  const d = newCache.get(cls);
  if (!d) return false;
  if (d.methods.has(key)) return true;
  return d.supers.some((s) => s.startsWith('net/minecraft/') && inNewChain(s, key));
}

let dead = 0, checked = 0;
const scan = (buf) => {
  for (const e of readZip(buf)) {
    if (/^META-INF\/jars\/.+\.jar$/.test(e.name)) { try { scan(inflateEntry(e)); } catch { /* skip */ } continue; }
    if (!e.name.endsWith('.class')) continue;
    let d; try { d = new ClassFile(inflateEntry(e)).declared(); } catch { continue; }
    const supers = [d.super, ...d.interfaces].filter((s) => s && s.startsWith('net/minecraft/'));
    if (!supers.length) continue;
    for (const m of d.members) {
      if (m.kind !== 'method' || m.name.startsWith('<') || m.name.startsWith('lambda$')) continue;
      const key = `${m.name} ${m.desc}`;
      for (const s of supers) {
        if (!declaredInOldChain(s, key)) continue;
        checked++;
        if (!inNewChain(s, key)) {
          dead++;
          console.log(`  ✗ ${d.name.replace(/\//g, '.')}.${m.name} — overrode ${s.split('/').pop()}, which no longer declares it`);
          console.log('      this method will never be called again: the feature it implements dies SILENTLY');
        }
        break;
      }
    }
  }
};
scan(fs.readFileSync(JAR));
console.log(`\n  overrides checked : ${checked}`);
console.log(`  DEAD              : ${dead}`);
process.exit(dead ? 1 : 0);
