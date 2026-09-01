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

// codeStart matters: tableswitch and lookupswitch pad to a 4-byte boundary measured from the START
// OF THE CODE ARRAY, not from the file offset. Using the absolute position gets the padding right
// only when the code happens to begin on a multiple of four, and silently wrong otherwise — the walk
// then desynchronises and patches a byte in the middle of an operand. That produced exactly one
// corrupted class in the corpus, caught by the JVM verifier as "Inconsistent stackmap frames at
// branch target 139" rather than by anything in this project.
export function insnLength(buf, pc, codeStart = 0) {
  const op = buf[pc];
  if (op === 0xc4) return buf[pc + 1] === 0x84 ? 6 : 4;          // wide
  if (op === 0xaa) {                                             // tableswitch
    const p = pc + 1 + ((4 - ((pc + 1 - codeStart) % 4)) % 4);
    const low = buf.readInt32BE(p + 4), high = buf.readInt32BE(p + 8);
    return (p + 12 + (high - low + 1) * 4) - pc;
  }
  if (op === 0xab) {                                             // lookupswitch
    const p = pc + 1 + ((4 - ((pc + 1 - codeStart) % 4)) % 4);
    const n = buf.readInt32BE(p + 4);
    return (p + 8 + n * 8) - pc;
  }
  return FIXED.get(op) ?? 1;
}

// A walk that loses sync would read operands as opcodes and could compute a length of zero or less,
// which is an infinite loop rather than a wrong answer. Every caller goes through this instead.
export function safeLength(buf, pc, end, codeStart = 0) {
  const n = insnLength(buf, pc, codeStart);
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
  const wanted = new Map();          // old pool index -> {get?, put?, call?} of static methods
  for (const e of cf.entries) {
    if (e.tag !== 9 && e.tag !== 10 && e.tag !== 11) continue;
    const owner = clsOf(cf.buf.readUInt16BE(e.start + 1));
    const nat = byIndex.get(cf.buf.readUInt16BE(e.start + 3));
    if (!owner || !nat || nat.tag !== 12) continue;
    const name = utf8(cf.buf.readUInt16BE(nat.start + 1));
    const desc = utf8(cf.buf.readUInt16BE(nat.start + 3));
    if (!name || !desc) continue;
    const to = decide({ kind: e.tag === 9 ? 'field' : 'method', owner, name, desc });
    if (!to) continue;
    // Plain {owner,name,desc} answers READS and CALLS only — never writes. The compatibility shim
    // used to fill all three slots, and advancement-plaques found out why that is wrong in the one
    // place a static check never ran: the mod WRITES the toast manager back (mc.toastManager =
    // wrapper, its whole injection technique), the write site got redirected into the read bridge,
    // and the JVM refused the class at runtime — Type 'ToastManagerWrapper' not assignable to
    // 'Minecraft'. A write is only redirected when something explicitly supplies a write body; a
    // relocation to an accessor has none, so the write stays broken and VISIBLE.
    wanted.set(e.index, to.owner ? { get: to, call: to } : to);
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
  const redirect = new Map();        // old index -> {get?, put?, call?} of new Methodref indexes
  for (const [oldIdx, to] of wanted) redirect.set(oldIdx, {
    get: to.get ? addRef(to.get) : undefined,
    put: to.put ? addRef(to.put) : undefined,
    call: to.call ? addRef(to.call) : undefined,
  });
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
      const len = safeLength(out, pc, end, start);
      if (len < 0) break;
      if (op === 0xb2 || op === 0xb3 || op === 0xb4 || op === 0xb5 || op === 0xb6 || op === 0xb8 || op === 0xb9) {
        const idx = out.readUInt16BE(pc + 1);
        const slot = redirect.get(idx);
        // PUTFIELD pops value-then-objectref exactly as a static (owner, value) call pops its two
        // arguments, and both instructions are three bytes — the same size-and-stack identity the
        // reads rely on. A kind with no answer leaves its instruction untouched.
        const to = slot === undefined ? undefined
          : (op === 0xb3 || op === 0xb5) ? slot.put
          : (op === 0xb2 || op === 0xb4) ? slot.get
          : slot.call;
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
      const n = safeLength(buf, pc, end, start);
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

// ── method synthesis ─────────────────────────────────────────────────────────────────────────
//
// Appending a whole method to a class is offset-safe for the same reason pool appends are: no byte
// in any other method's Code attribute refers to a position outside itself, and the methods table
// carries its own count. Splice a method_info before the class attributes, bump methods_count, done.
//
// The methods synthesized here are STRAIGHT-LINE — loads, one call or one throw, a return. A method
// with no branches needs no StackMapTable, which keeps this machinery inside what has already been
// proven against the JVM verifier instead of reimplementing frame computation.

// A tiny pool builder over an existing ClassFile, shared shape with retargetCallSites.
export function poolBuilder(cf) {
  const byIndex = new Map(cf.entries.map((e) => [e.index, e]));
  const utf8At = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? cf.buf.toString('latin1', e.start + 3, e.end) : null; };
  let next = cf.buf.readUInt16BE(8);
  const pool = [], intern = new Map();
  const utf8 = (s) => {
    if (intern.has('u' + s)) return intern.get('u' + s);
    for (const e of cf.entries) if (e.tag === 1 && utf8At(e.index) === s) { intern.set('u' + s, e.index); return e.index; }
    const b = Buffer.from(s, 'latin1'), h = Buffer.alloc(3); h[0] = 1; h.writeUInt16BE(b.length, 1);
    pool.push(Buffer.concat([h, b])); const i = next++; intern.set('u' + s, i); return i;
  };
  const cls = (n) => { const k = 'c' + n; if (intern.has(k)) return intern.get(k); const u = utf8(n), h = Buffer.alloc(3); h[0] = 7; h.writeUInt16BE(u, 1); pool.push(h); const i = next++; intern.set(k, i); return i; };
  const nat = (n, d) => { const k = `n${n}\t${d}`; if (intern.has(k)) return intern.get(k); const b = Buffer.alloc(5); b[0] = 12; b.writeUInt16BE(utf8(n), 1); b.writeUInt16BE(utf8(d), 3); pool.push(b); const i = next++; intern.set(k, i); return i; };
  const ref = (tag, o, n, d) => { const k = `r${tag}${o}\t${n}\t${d}`; if (intern.has(k)) return intern.get(k); const c = cls(o), na = nat(n, d); const b = Buffer.alloc(5); b[0] = tag; b.writeUInt16BE(c, 1); b.writeUInt16BE(na, 3); pool.push(b); const i = next++; intern.set(k, i); return i; };
  const str = (s) => { const k = 's' + s; if (intern.has(k)) return intern.get(k); const u = utf8(s), h = Buffer.alloc(3); h[0] = 8; h.writeUInt16BE(u, 1); pool.push(h); const i = next++; intern.set(k, i); return i; };
  return { utf8, cls, nat, methodref: (o, n, d) => ref(10, o, n, d), interfaceref: (o, n, d) => ref(11, o, n, d), str, flush: () => ({ pool, next }) };
}

const SLOTS = { J: 2, D: 2 };
export function paramSlots(desc) {
  const out = []; let i = 1;
  while (desc[i] !== ')') { let s = i; while (desc[i] === '[') i++; if (desc[i] === 'L') i = desc.indexOf(';', i); const t = desc.slice(s, i + 1); out.push({ t, wide: SLOTS[t] === 2 }); i++; }
  return out;
}
const loadOp = (t) => (t === 'J' ? 0x16 : t === 'F' ? 0x17 : t === 'D' ? 0x18 : 'IZBCS'.includes(t) ? 0x15 : 0x19);
const returnOp = (t) => (t === 'V' ? 0xb1 : t === 'J' ? 0xad : t === 'F' ? 0xae : t === 'D' ? 0xaf : 'IZBCS'.includes(t) ? 0xac : 0xb0);

// One synthesized method: either `throws` (message string) or `call` {owner,name,desc,iface} which
// receives this method's own arguments in order (an instance method passes `this` first).
export function assembleMethod(pb, { name, desc, static: isStatic, throws, call }) {
  const code = [];
  if (throws) {
    const ex = pb.cls('java/lang/UnsupportedOperationException');
    const ctor = pb.methodref('java/lang/UnsupportedOperationException', '<init>', '(Ljava/lang/String;)V');
    const msg = pb.str(throws);
    code.push(0xbb, ex >> 8, ex & 255, 0x59, 0x13, msg >> 8, msg & 255, 0xb7, ctor >> 8, ctor & 255, 0xbf);
  } else {
    let slot = 0;
    if (!isStatic) { code.push(0x2a); slot = 1; }                     // aload_0: this
    for (const p of paramSlots(desc)) { code.push(loadOp(p.t), slot); slot += p.wide ? 2 : 1; }
    const idx = call.iface ? pb.interfaceref(call.owner, call.name, call.desc) : pb.methodref(call.owner, call.name, call.desc);
    if (call.iface) code.push(0xb9, idx >> 8, idx & 255, slot + (isStatic ? 0 : 0), 0);
    else code.push(call.special ? 0xb7 : call.static ? 0xb8 : 0xb6, idx >> 8, idx & 255);
    code.push(returnOp(desc.slice(desc.indexOf(')') + 1)));
  }
  const maxLocals = 1 + paramSlots(desc).reduce((a, p) => a + (p.wide ? 2 : 1), 0) + (isStatic ? 0 : 1);
  const codeAttr = Buffer.alloc(14 + code.length);
  codeAttr.writeUInt16BE(pb.utf8('Code'), 0);
  codeAttr.writeUInt32BE(8 + code.length + 4, 2);
  codeAttr.writeUInt16BE(Math.max(6, maxLocals + 2), 6);              // max_stack: generous, straight-line
  codeAttr.writeUInt16BE(maxLocals, 8);
  codeAttr.writeUInt32BE(code.length, 10);
  Buffer.from(code).copy(codeAttr, 14);
  const tail = Buffer.alloc(4);                                       // exception_table_length, attrs
  const head = Buffer.alloc(8);
  head.writeUInt16BE(isStatic ? 0x0009 : 0x0001, 0);                  // public [static]
  head.writeUInt16BE(pb.utf8(name), 2);
  head.writeUInt16BE(pb.utf8(desc), 4);
  head.writeUInt16BE(1, 6);                                           // one attribute: Code
  return Buffer.concat([head, codeAttr, tail]);
}

// Append methods, and optionally re-parent the class: `newSuper` replaces the super_class (used when
// the old super became an interface — it moves to the interface list and Object takes its place,
// with the <init> super-call repointed, which resolves because Object.<init> shares the descriptor).
export function transformClass(ClassFile, buf, { methods = [], newSuper, addInterface, repointInit } = {}) {
  const cf = new ClassFile(buf);
  const pb = poolBuilder(cf);
  const assembled = methods.map((m) => assembleMethod(pb, m));
  const objIdx = newSuper ? pb.cls(newSuper) : 0;
  const ifaceIdx = addInterface ? pb.cls(addInterface) : 0;
  const initIdx = repointInit ? pb.methodref(newSuper, '<init>', '()V') : 0;
  const { pool, next } = pb.flush();

  const head = Buffer.from(buf.subarray(0, cf.poolEnd));
  head.writeUInt16BE(next, 8);
  let out = Buffer.concat([head, ...pool, buf.subarray(cf.poolEnd)]);
  const cf2 = new ClassFile(out);

  let p = cf2.poolEnd + 2;                                            // access_flags
  p += 2;                                                             // this_class
  if (newSuper) out.writeUInt16BE(objIdx, p);
  const superIdxPos = p; p += 2;
  const ifCountPos = p; const ifCount = out.readUInt16BE(p); p += 2 + ifCount * 2;
  if (addInterface) {
    out = Buffer.concat([out.subarray(0, p), Buffer.from([ifaceIdx >> 8, ifaceIdx & 255]), out.subarray(p)]);
    out.writeUInt16BE(ifCount + 1, ifCountPos);
    p += 2;
  }
  // walk fields, then methods, to find the splice point
  const cf3 = { buf: out };
  const skipMembers = (pos) => {
    const count = out.readUInt16BE(pos); pos += 2;
    for (let i = 0; i < count; i++) { pos += 6; const attrs = out.readUInt16BE(pos); pos += 2; for (let a = 0; a < attrs; a++) { pos += 2; pos += 4 + out.readUInt32BE(pos); } }
    return pos;
  };
  p = skipMembers(p);                                                 // fields
  const methodCountPos = p;
  const endOfMethods = skipMembers(p);
  if (assembled.length) {
    out.writeUInt16BE(out.readUInt16BE(methodCountPos) + assembled.length, methodCountPos);
    out = Buffer.concat([out.subarray(0, endOfMethods), ...assembled, out.subarray(endOfMethods)]);
  }
  if (repointInit && initIdx) {
    // Re-point every invokespecial <init> aimed at the old super. Same 3-byte instruction, new index.
    const cf4 = new ClassFile(out);
    const byIndex = new Map(cf4.entries.map((e) => [e.index, e]));
    const u = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? out.toString('latin1', e.start + 3, e.end) : null; };
    const oldSuperInits = new Set();
    for (const e of cf4.entries) {
      if (e.tag !== 10) continue;
      const c = byIndex.get(out.readUInt16BE(e.start + 1));
      const owner = c && c.tag === 7 ? u(out.readUInt16BE(c.start + 1)) : null;
      const nt = byIndex.get(out.readUInt16BE(e.start + 3));
      const nm = nt && nt.tag === 12 ? u(out.readUInt16BE(nt.start + 1)) : null;
      if (owner === repointInit && nm === '<init>') oldSuperInits.add(e.index);
    }
    for (const { start, length } of codeRanges(cf4)) {
      let pc = start; const end = start + length;
      while (pc < end) {
        if (out[pc] === 0xb7 && oldSuperInits.has(out.readUInt16BE(pc + 1))) out.writeUInt16BE(initIdx, pc + 1);
        const n = safeLength(out, pc, end, start); if (n < 0) break; pc += n;
      }
    }
  }
  return out;
}
