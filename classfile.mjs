// Minimal Java class-file reader/rewriter — enough to rename types and members, nothing more.
//
// The whole trick: class names, method names, field names and descriptors do not live in the
// bytecode. They live in the constant pool as UTF-8 entries, and the instructions only hold
// *indices* into that pool. So renaming is a string edit. Nothing else in the file refers to a byte
// offset — jumps are relative to positions inside a Code attribute we never touch — which means we
// can rewrite pool entries and copy every other byte verbatim.
//
// That is why this needs no bytecode library, no stack map recomputation, and no decompiler.

// How many bytes each constant pool tag occupies after its 1-byte tag.
const SIZES = { 3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2 };

export class ClassFile {
  constructor(buf) {
    if (buf.length < 10 || buf.readUInt32BE(0) !== 0xcafebabe) throw new Error('not a class file');
    this.buf = buf;
    this.major = buf.readUInt16BE(6);
    this.poolStart = 10;
    const count = buf.readUInt16BE(8);
    this.entries = [];        // {tag, start, end} — start is the tag byte
    let p = this.poolStart;
    // The pool is 1-indexed, and long/double each swallow the following slot. Getting that wrong
    // silently shifts every later index, so the loop counts slots rather than entries.
    for (let i = 1; i < count; i++) {
      const tag = buf[p];
      let len;
      if (tag === 1) len = 2 + buf.readUInt16BE(p + 1);
      else if (SIZES[tag] !== undefined) len = SIZES[tag];
      else throw new Error(`unknown constant pool tag ${tag} at index ${i}`);
      this.entries.push({ tag, start: p, end: p + 1 + len, index: i });
      p += 1 + len;
      if (tag === 5 || tag === 6) i++;   // takes two slots
    }
    this.poolEnd = p;
  }

  // Every UTF-8 entry, as a latin1 string so bytes round-trip exactly.
  *utf8() {
    for (const e of this.entries) {
      if (e.tag !== 1) continue;
      yield { entry: e, value: this.buf.toString('latin1', e.start + 3, e.end) };
    }
  }

  // Every member this class reaches for: (owner, name, descriptor). These are exactly the links the
  // JVM resolves at runtime, so checking them against the target jars predicts NoClassDefFoundError
  // and NoSuchMethodError before the game is ever launched.
  *refs() {
    const byIndex = new Map(this.entries.map((e) => [e.index, e]));
    const utf8 = (i) => { const e = byIndex.get(i); return e && e.tag === 1 ? this.buf.toString('latin1', e.start + 3, e.end) : null; };
    const className = (i) => { const e = byIndex.get(i); return e && e.tag === 7 ? utf8(this.buf.readUInt16BE(e.start + 1)) : null; };
    for (const e of this.entries) {
      if (e.tag !== 9 && e.tag !== 10 && e.tag !== 11) continue;
      const owner = className(this.buf.readUInt16BE(e.start + 1));
      const nat = byIndex.get(this.buf.readUInt16BE(e.start + 3));
      if (!owner || !nat || nat.tag !== 12) continue;
      const name = utf8(this.buf.readUInt16BE(nat.start + 1));
      const desc = utf8(this.buf.readUInt16BE(nat.start + 3));
      if (name && desc) yield { kind: e.tag === 9 ? 'field' : 'method', owner, name, desc };
    }
  }

  // The class's own declared fields and methods. Reading these straight from the class file rather
  // than shelling out to javap makes it cheap enough to diff two entire Minecraft jars.
  declared() {
    const b = this.buf;
    const utf8 = (i) => { const e = this.entries.find((x) => x.index === i); return e && e.tag === 1 ? b.toString('latin1', e.start + 3, e.end) : null; };
    const clsName = (i) => { const e = this.entries.find((x) => x.index === i); return e && e.tag === 7 ? utf8(b.readUInt16BE(e.start + 1)) : null; };
    let p = this.poolEnd;
    p += 2;                                        // access_flags
    const thisClass = clsName(b.readUInt16BE(p)); p += 2;
    const superClass = clsName(b.readUInt16BE(p)); p += 2;
    p += 2 + b.readUInt16BE(p) * 2;                // interfaces
    const members = [];
    for (const kind of ['field', 'method']) {
      const count = b.readUInt16BE(p); p += 2;
      for (let i = 0; i < count; i++) {
        p += 2;                                    // access_flags
        const name = utf8(b.readUInt16BE(p)); p += 2;
        const desc = utf8(b.readUInt16BE(p)); p += 2;
        const attrs = b.readUInt16BE(p); p += 2;
        for (let a = 0; a < attrs; a++) { p += 2; p += 4 + b.readUInt32BE(p); }
        if (name && desc) members.push({ kind, name, desc });
      }
    }
    return { name: thisClass, super: superClass, members };
  }

  // Only entries that are plain printable ASCII are safe to rewrite. Anything else is a string
  // literal with real text in it — possibly modified-UTF8 — and is none of our business.
  static isPlain(s) { return /^[\x20-\x7E]*$/.test(s); }

  // fn(value) -> replacement string, or null/undefined to leave it alone.
  // Returns a new Buffer, or null if nothing changed.
  rewrite(fn) {
    const edits = new Map();
    for (const { entry, value } of this.utf8()) {
      if (!ClassFile.isPlain(value)) continue;
      const next = fn(value);
      if (next != null && next !== value) edits.set(entry, next);
    }
    if (!edits.size) return null;
    const parts = [this.buf.subarray(0, this.poolStart)];
    let cursor = this.poolStart;
    for (const e of this.entries) {
      const next = edits.get(e);
      if (next === undefined) continue;
      parts.push(this.buf.subarray(cursor, e.start));
      const bytes = Buffer.from(next, 'latin1');
      if (bytes.length > 0xffff) throw new Error('rewritten constant exceeds 64 KB');
      const head = Buffer.alloc(3);
      head[0] = 1; head.writeUInt16BE(bytes.length, 1);
      parts.push(head, bytes);
      cursor = e.end;
    }
    parts.push(this.buf.subarray(cursor));   // rest of the pool + the entire rest of the file
    return Buffer.concat(parts);
  }
}

// Pull out every type name this class mentions. Class entries hold `a/b/C` directly; descriptors and
// generic signatures hold them as `La/b/C;` or `La/b/C<...>`, so both forms have to be swept.
const TYPE_IN_DESC = /L([\w/$]+)([;<])/g;
export function referencedTypes(cf) {
  const out = new Set();
  for (const { value } of cf.utf8()) {
    if (!ClassFile.isPlain(value)) continue;
    if (/^[\w/$]+$/.test(value) && value.includes('/')) out.add(value);
    for (const m of value.matchAll(TYPE_IN_DESC)) out.add(m[1]);
  }
  return out;
}

// Build the replacer for a set of renames. `types` and `members` are keyed in INTERNAL form
// (slashes). Member renaming is sound for SRG/intermediary tokens because those are globally
// unique — the same reason the source porter can rewrite them without receiver-type analysis.
export function makeReplacer(types, members, stats = {}) {
  stats.types = stats.types || 0; stats.members = stats.members || 0;
  return (s) => {
    const direct = types.get(s);
    if (direct) { stats.types++; return direct; }
    if (members.has(s)) { stats.members++; return members.get(s); }
    if (s.includes('L') && (s.includes(';') || s.includes('<'))) {
      let hit = false;
      const next = s.replace(TYPE_IN_DESC, (whole, name, term) => {
        const to = types.get(name);
        if (!to) return whole;
        hit = true; return 'L' + to + term;
      });
      if (hit) { stats.types++; return next; }
    }
    return null;
  };
}
