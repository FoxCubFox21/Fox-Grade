// Read Mojang's published mappings into the same index shape the jar readers produce.
//
// This is what makes an obfuscated version minable at all. 26.x ships unobfuscated, so the miners
// can diff its jar directly — but 1.21.1 and everything before it ships as `dqh`, `eza`, `a`, `b`,
// and diffing THAT against 26.2 finds nothing but noise.
//
// The way through is that the mappings file already contains the answer. It is not merely a
// translation table: it lists every class, every field and every method WITH its signature, in
// readable names. So a mojmap view of 1.21.1 can be built from the mappings alone, with no
// deobfuscation step, no remapped jar on disk, and no dependency on a mapping toolchain.
//
// Mojang's licence permits using these mappings but not redistributing them complete and
// unmodified, which is why this reads a file the user downloaded rather than shipping one, and why
// only the DERIVED old->new pairs are ever written out.
//
// Format:
//   net.minecraft.world.level.block.entity.BlockEntity -> dqh:
//       net.minecraft.core.BlockPos worldPosition -> o
//       12:13:void setChanged() -> b
//
// Members are indented; a line ending in ':' opens a class. Method lines may carry a `start:end:`
// line-number prefix, which is source information rather than signature and is dropped.
import fs from 'node:fs';

const PRIM = { void: 'V', int: 'I', long: 'J', float: 'F', double: 'D', boolean: 'Z', byte: 'B', char: 'C', short: 'S' };

// A Java source type as written in the mappings -> a JVM descriptor. Inner classes already arrive
// with '$' so only the package separator changes; array suffixes become leading '[' in order.
export function descOf(type) {
  let dims = 0;
  while (type.endsWith('[]')) { dims++; type = type.slice(0, -2); }
  const base = PRIM[type] || `L${type.replace(/\./g, '/')};`;
  return '['.repeat(dims) + base;
}

// Split a parameter list on top-level commas. Mojang's mappings carry no generics, so a plain split
// is correct here — but doing it by depth costs nothing and will not silently mangle a future file
// that does include them.
function splitParams(s) {
  if (!s.trim()) return [];
  const out = []; let depth = 0, cur = '';
  for (const ch of s) {
    if (ch === '<') depth++;
    else if (ch === '>') depth--;
    if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}

// -> Map(internalClassName -> [{kind,name,desc}]), matching what ClassFile.declared() yields so the
// existing miners can consume it without knowing where it came from.
export function indexFromProguard(file) {
  const out = new Map();
  let members = null;
  for (const raw of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!raw || raw[0] === '#') continue;
    if (raw[0] !== ' ' && raw[0] !== '\t') {
      const m = raw.match(/^([\w.$]+)\s*->\s*([\w.$]+):\s*$/);
      if (!m) { members = null; continue; }
      members = [];
      out.set(m[1].replace(/\./g, '/'), members);
      continue;
    }
    if (!members) continue;
    const line = raw.trim().replace(/^\d+:\d+:/, '');
    const m = line.match(/^([\w.$\[\]<>]+)\s+([\w$<>]+)(\((.*)\))?\s*->\s*([\w$<>]+)$/);
    if (!m) continue;
    const [, type, name, isMethod, params] = m;
    members.push(isMethod
      ? { kind: 'method', name, desc: `(${splitParams(params).map(descOf).join('')})${descOf(type)}` }
      : { kind: 'field', name, desc: descOf(type) });
  }
  return out;
}
