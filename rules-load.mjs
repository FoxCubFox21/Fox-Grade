// One place that knows how to read rules.json, so shipping it compressed is invisible to callers.
//
// The table is 22.8 MB of JSON and every revision of it sits in git history forever — seven
// revisions was 160 MB of clone weight for a repo whose code is under a megabyte. Gzipped it is
// 2.1 MB, so the compressed form is what ships and the plain file becomes a local convenience.
// Readers try plain first (a working copy someone is editing wins), then the compressed one.
import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';

export function loadRules(dir) {
  const plain = path.join(dir, 'rules.json');
  if (fs.existsSync(plain)) return JSON.parse(fs.readFileSync(plain, 'utf8'));
  const gz = path.join(dir, 'rules.json.gz');
  if (fs.existsSync(gz)) return JSON.parse(zlib.gunzipSync(fs.readFileSync(gz)).toString('utf8'));
  throw new Error(`no rules.json or rules.json.gz in ${dir}`);
}
