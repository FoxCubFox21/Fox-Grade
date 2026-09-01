// Rewrite CALL SITES, which the constant-pool editor deliberately cannot do.
//
// Everything else in this project edits the pool, because nothing in a class file refers to a byte
// offset inside it — so entries can be rewritten or appended and the code is untouched. That is why
// renaming is safe and why it can never express a relocation. `mc.screen` becoming `mc.gui.screen()`
// is a different instruction, not a different name, and inserting one shifts every later offset in
// the method, invalidating the jump targets, the exception table and the stack map frames.
//
// The way out is that these substitutions have the SAME SIZE and the SAME STACK EFFECT:
//
//   GETSTATIC       3 bytes,          -> value   ==  INVOKESTATIC (no args)      3 bytes
//   GETFIELD        3 bytes,  obj     -> value   ==  INVOKESTATIC (owner)        3 bytes
//   INVOKEVIRTUAL   3 bytes,  obj,as  -> ret     ==  INVOKESTATIC (owner, as)    3 bytes
//   INVOKEINTERFACE 5 bytes,  obj,as  -> ret     ==  INVOKESTATIC + NOP + NOP    5 bytes
//
// So a call site can be redirected to a static method of ours by patching one opcode and one index,
// with every offset in the method left exactly where it was. No frames to recompute, and no chance
// of the subtle breakage that recomputing them wrongly would cause.
//
// The static method lives in a compat class generated into the ported jar — it is our build of the
// mod, so it may carry our code — and it implements the old shape in terms of the new one.

// Instruction lengths. Getting one wrong desynchronises the whole walk and would corrupt whatever it
// patched next, so every operand form is spelled out rather than assumed.
const FIXED = new Map([
  [0x10, 2], [0x11, 3], [0x12, 2], [0x13, 3], [0x14, 3],
  [0x15, 2], [0x16, 2], [0x17, 2], [0x18, 2], [0x19, 2],
  [0x36, 2], [0x37, 2], [0x38, 2], [0x39, 2], [0x3a, 2],
  [0x84, 3], [0xa9, 2],
  [0xb2, 3], [0xb3, 3], [0xb4, 3], [0xb5, 3], [0xb6, 3], [0xb7, 3], [0xb8, 3],
  [0xb9, 5], [0xba, 5], [0xbb, 3], [0xbc, 2], [0xbd, 3],
  [0xc0, 3], [0xc1, 3], [0xc5, 4], [0xc6, 3], [0xc7, 3], [0xc8, 5], [0xc9, 5],
]);
for (let op = 0x99; op <= 0xa8; op++) FIXED.set(op, 3);          // ifeq..jsr

export function insnLength(buf, pc) {
  const op = buf[pc];
  if (op === 0xc4) return buf[pc + 1] === 0x84 ? 6 : 4;          // wide
  if (op === 0xaa) {                                             // tableswitch
    let p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
    const low = buf.readInt32BE(p + 4), high = buf.readInt32BE(p + 8);
    return (p + 12 + (high - low + 1) * 4) - pc;
  }
  if (op === 0xab) {                                             // lookupswitch
    let p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
    const n = buf.readInt32BE(p + 4);
    return (p + 8 + n * 8) - pc;
  }
  return FIXED.get(op) ?? 1;
}

// A walk that loses sync would read operands as opcodes and could compute a length of zero or less,
// which is an infinite loop rather than a wrong answer. Every caller goes through this instead.
export function safeLength(buf, pc, end) {
  const n = insnLength(buf, pc);
  return (!Number.isFinite(n) || n < 1 || pc + n > end) ? -1 : n;
}

// Every Code attribute's byte range. Walks the class structure rather than searching for a pattern,
// because "Code" appears in the pool as an ordinary string and guessing would patch a constant.
export function codeRanges(cf) {
  const b = cf.buf;
  const byIndex = new Map(cf.entries.map((e) => [e.index, e]));
  const utf8 = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? b.toString('latin1', e.start + 3, e.end) : null; };
  let p = cf.poolEnd + 6;                                        // access, this, super
  p += 2 + b.readUInt16BE(p) * 2;                                // interfaces
  const out = [];
  for (const _ of ['fields', 'methods']) {
    const count = b.readUInt16BE(p); p += 2;
    for (let i = 0; i < count; i++) {
      p += 6;                                                    // access, name, desc
      const attrs = b.readUInt16BE(p); p += 2;
      for (let a = 0; a < attrs; a++) {
        const nameIdx = b.readUInt16BE(p); p += 2;
        const len = b.readUInt32BE(p); p += 4;
        if (utf8(nameIdx) === 'Code') {
          const codeLen = b.readUInt32BE(p + 4);
          out.push({ start: p + 8, length: codeLen });
        }
        p += len;
      }
    }
  }
  return out;
}

// decide({kind, owner, name, desc}) -> { owner, name, desc } of a STATIC method to call instead,
// or null to leave the site alone. Returns a new Buffer, or null if nothing matched.
export function retargetCallSites(ClassFile, buf, decide) {
  let cf = new ClassFile(buf);
  const byIndex = new Map(cf.entries.map((e) => [e.index, e]));
  const utf8 = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? cf.buf.toString('latin1', e.start + 3, e.end) : null; };
  const clsOf = (i) => { const e = byIndex.get(i); return e && e.tag === 7 ? utf8(cf.buf.readUInt16BE(e.start + 1)) : null; };

  // Which pool refs are ones we want to redirect, and to what.
  const wanted = new Map();          // old pool index -> target {owner,name,desc}
  for (const e of cf.entries) {
    if (e.tag !== 9 && e.tag !== 10 && e.tag !== 11) continue;
    const owner = clsOf(cf.buf.readUInt16BE(e.start + 1));
    const nat = byIndex.get(cf.buf.readUInt16BE(e.start + 3));
    if (!owner || !nat || nat.tag !== 12) continue;
    const name = utf8(cf.buf.readUInt16BE(nat.start + 1));
    const desc = utf8(cf.buf.readUInt16BE(nat.start + 3));
    if (!name || !desc) continue;
    const to = decide({ kind: e.tag === 9 ? 'field' : 'method', owner, name, desc });
    if (to) wanted.set(e.index, to);
  }
  if (!wanted.size) return null;

  // Append the Methodrefs to call instead. Appending is safe for the same reason renaming is: no
  // offset anywhere in the file points into the pool.
  const added = [];
  let next = cf.entries.length ? Math.max(...cf.entries.map((x) => x.index)) + (cf.entries.some((x) => x.tag === 5 || x.tag === 6) ? 1 : 1) : 1;
  next = cf.buf.readUInt16BE(8);                                  // constant_pool_count == next free index
  const pool = [];
  const intern = new Map();
  const addUtf8 = (s) => {
    if (intern.has('u' + s)) return intern.get('u' + s);
    for (const e of cf.entries) if (e.tag === 1 && utf8(e.index) === s) { intern.set('u' + s, e.index); return e.index; }
    const b2 = Buffer.from(s, 'latin1'); const h = Buffer.alloc(3); h[0] = 1; h.writeUInt16BE(b2.length, 1);
    pool.push(Buffer.concat([h, b2])); const idx = next++; intern.set('u' + s, idx); return idx;
  };
  const addClass = (n) => {
    if (intern.has('c' + n)) return intern.get('c' + n);
    const u = addUtf8(n); const h = Buffer.alloc(3); h[0] = 7; h.writeUInt16BE(u, 1);
    pool.push(h); const idx = next++; intern.set('c' + n, idx); return idx;
  };
  const addRef = (t) => {
    const k = `m${t.owner}\t${t.name}\t${t.desc}`;
    if (intern.has(k)) return intern.get(k);
    const c = addClass(t.owner), nU = addUtf8(t.name), dU = addUtf8(t.desc);
    const nat = Buffer.alloc(5); nat[0] = 12; nat.writeUInt16BE(nU, 1); nat.writeUInt16BE(dU, 3);
    pool.push(nat); const natIdx = next++;
    const ref = Buffer.alloc(5); ref[0] = 10; ref.writeUInt16BE(c, 1); ref.writeUInt16BE(natIdx, 3);
    pool.push(ref); const idx = next++; intern.set(k, idx); return idx;
  };
  const redirect = new Map();        // old index -> new Methodref index
  for (const [oldIdx, to] of wanted) redirect.set(oldIdx, addRef(to));
  for (const p of pool) added.push(p);

  const head = Buffer.from(cf.buf.subarray(0, cf.poolEnd));
  head.writeUInt16BE(next, 8);
  let out = Buffer.concat([head, ...added, cf.buf.subarray(cf.poolEnd)]);

  // Re-parse: the pool grew, so every Code attribute has moved.
  cf = new ClassFile(out);
  let patched = 0;
  for (const { start, length } of codeRanges(cf)) {
    let pc = start;
    const end = start + length;
    while (pc < end) {
      const op = out[pc];
      const len = safeLength(out, pc, end);
      if (len < 0) break;
      if (op === 0xb2 || op === 0xb4 || op === 0xb6 || op === 0xb9) {
        const idx = out.readUInt16BE(pc + 1);
        const to = redirect.get(idx);
        if (to !== undefined) {
          out[pc] = 0xb8;                                         // invokestatic
          out.writeUInt16BE(to, pc + 1);
          // invokeinterface is five bytes; the two it no longer needs become no-ops so that every
          // following offset stays exactly where it was.
          if (op === 0xb9) { out[pc + 3] = 0x00; out[pc + 4] = 0x00; }
          patched++;
        }
      }
      pc += len;
    }
  }
  return patched ? out : null;
}

// Which member references does the CODE actually use?
//
// The JVM resolves a constant pool entry lazily, on first execution of an instruction that names it.
// An entry no instruction reaches is never resolved and can never throw — so a pool that still
// mentions a deleted method is not, on its own, a broken jar. This matters the moment call sites are
// redirected: the old Fieldref stays in the pool, unreferenced and inert, and a checker reading the
// pool alone would keep reporting a link that nothing can ever follow.
export function liveRefs(ClassFile, buf) {
  const cf = new ClassFile(buf);
  const byIndex = new Map(cf.entries.map((e) => [e.index, e]));
  const utf8 = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? buf.toString('latin1', e.start + 3, e.end) : null; };
  const clsOf = (i) => { const e = byIndex.get(i); return e && e.tag === 7 ? utf8(buf.readUInt16BE(e.start + 1)) : null; };
  const used = new Set();
  for (const { start, length } of codeRanges(cf)) {
    let pc = start; const end = start + length;
    while (pc < end) {
      const op = buf[pc];
      // Every opcode that names a member: get/put static and field, the four invokes.
      if ((op >= 0xb2 && op <= 0xb9)) used.add(buf.readUInt16BE(pc + 1));
      const n = safeLength(buf, pc, end);
      if (n < 0) break;                    // desynced: stop rather than spin or misread
      pc += n;
    }
  }
  const out = new Set();
  for (const idx of used) {
    const e = byIndex.get(idx);
    if (!e || (e.tag !== 9 && e.tag !== 10 && e.tag !== 11)) continue;
    const owner = clsOf(buf.readUInt16BE(e.start + 1));
    const nat = byIndex.get(buf.readUInt16BE(e.start + 3));
    if (!owner || !nat || nat.tag !== 12) continue;
    const name = utf8(buf.readUInt16BE(nat.start + 1));
    const desc = utf8(buf.readUInt16BE(nat.start + 3));
    if (name && desc) out.add(`${owner}\t${name}\t${desc}`);
  }
  return out;
}
