// Minimal zip reader/writer, stdlib only — a jar is just a zip.
//
// Entries we do not touch are copied as their ORIGINAL COMPRESSED BYTES: never inflated, never
// re-deflated. That keeps assets, mixin configs and signatures bit-identical, makes the pass fast,
// and means a bug in this file cannot corrupt a resource it was not asked to change.
import zlib from 'node:zlib';

const LOCAL = 0x04034b50, CENTRAL = 0x02014b50, EOCD = 0x06054b50, EOCD64 = 0x06064b50;

const CRC = (() => {
  const t = new Int32Array(256);
  for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[i] = c; }
  return t;
})();
export function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

export function readZip(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i >= buf.length - 65557; i--) if (buf.readUInt32LE(i) === EOCD) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a zip (no end-of-central-directory)');
  let count = buf.readUInt16LE(eocd + 10), cdOff = buf.readUInt32LE(eocd + 16);
  // ZIP64 marks the real values as 0xffff/0xffffffff and puts them in its own record.
  if (count === 0xffff || cdOff === 0xffffffff) {
    let z = -1;
    for (let i = eocd - 20; i >= 0 && i >= eocd - 4096; i--) if (buf.readUInt32LE(i) === EOCD64) { z = i; break; }
    if (z < 0) throw new Error('zip64 archive without a locatable zip64 record');
    count = Number(buf.readBigUInt64LE(z + 32)); cdOff = Number(buf.readBigUInt64LE(z + 48));
  }
  const entries = [];
  let p = cdOff;
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== CENTRAL) throw new Error(`bad central directory entry at ${p}`);
    const flags = buf.readUInt16LE(p + 8), method = buf.readUInt16LE(p + 10);
    const nameLen = buf.readUInt16LE(p + 28), extraLen = buf.readUInt16LE(p + 30), cmtLen = buf.readUInt16LE(p + 32);
    const e = {
      name: buf.toString('utf8', p + 46, p + 46 + nameLen),
      flags, method,
      time: buf.readUInt16LE(p + 12), date: buf.readUInt16LE(p + 14),
      crc: buf.readUInt32LE(p + 16),
      compSize: buf.readUInt32LE(p + 20), size: buf.readUInt32LE(p + 24),
      extAttrs: buf.readUInt32LE(p + 38),
      localOffset: buf.readUInt32LE(p + 42),
    };
    // Read the local header to find where the data actually starts — its extra field can differ
    // in length from the central one, and trusting the central copy puts you off by a few bytes.
    if (buf.readUInt32LE(e.localOffset) !== LOCAL) throw new Error(`bad local header for ${e.name}`);
    const lnLen = buf.readUInt16LE(e.localOffset + 26), leLen = buf.readUInt16LE(e.localOffset + 28);
    const dataStart = e.localOffset + 30 + lnLen + leLen;
    e.raw = buf.subarray(dataStart, dataStart + e.compSize);
    entries.push(e);
    p += 46 + nameLen + extraLen + cmtLen;
  }
  return entries;
}

export function inflateEntry(e) {
  if (e.method === 0) return Buffer.from(e.raw);
  if (e.method === 8) return zlib.inflateRawSync(e.raw);
  throw new Error(`unsupported compression method ${e.method} for ${e.name}`);
}

// `replacements`: Map(name -> Buffer) for entries whose content changed. Everything else is copied
// with its original compressed bytes intact.
export function writeZip(entries, replacements = new Map()) {
  const chunks = [], central = [];
  let offset = 0;
  for (const e of entries) {
    const next = replacements.get(e.name);
    let method = e.method, raw = e.raw, crc = e.crc, size = e.size, compSize = e.compSize;
    let flags = e.flags;
    if (next) {
      method = 8;
      raw = zlib.deflateRawSync(next, { level: 9 });
      crc = crc32(next); size = next.length; compSize = raw.length;
    }
    // Bit 3 says "sizes are zero here; a data descriptor follows the compressed data". We always
    // write real sizes into the local header and never copy that trailing descriptor, so the bit
    // must be cleared on EVERY entry — including ones passed through untouched.
    //
    // Leaving it set produced an archive that unzip -t and any central-directory reader accept
    // happily, because they never look at the local header. Fabric uses ZipInputStream, which reads
    // sequentially, trusts the flag, and walks straight off the end of the data:
    //   ZipException: invalid entry size (expected 8 but got 25 bytes)
    flags &= ~0x08;
    const name = Buffer.from(e.name, 'utf8');
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(LOCAL, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(flags, 6);
    lh.writeUInt16LE(method, 8); lh.writeUInt16LE(e.time, 10); lh.writeUInt16LE(e.date, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(compSize, 18); lh.writeUInt32LE(size, 22);
    lh.writeUInt16LE(name.length, 26); lh.writeUInt16LE(0, 28);
    chunks.push(lh, name, raw);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(CENTRAL, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(flags, 8); ch.writeUInt16LE(method, 10);
    ch.writeUInt16LE(e.time, 12); ch.writeUInt16LE(e.date, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(compSize, 20); ch.writeUInt32LE(size, 24);
    ch.writeUInt16LE(name.length, 28); ch.writeUInt16LE(0, 30); ch.writeUInt16LE(0, 32);
    ch.writeUInt16LE(0, 34); ch.writeUInt16LE(0, 36); ch.writeUInt32LE(e.extAttrs, 38);
    ch.writeUInt32LE(offset, 42);
    central.push(ch, name);
    offset += lh.length + name.length + raw.length;
  }
  const cd = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(EOCD, 0);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12); eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, cd, eocd]);
}
