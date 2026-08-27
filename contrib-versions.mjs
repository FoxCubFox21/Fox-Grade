#!/usr/bin/env node
// List the Minecraft versions a submission makes claims about, so CI can fetch the right jar for each.
// Without this, every rule aimed at a version other than the one jar CI happened to download gets
// held as unverifiable — which is most of them.
//
//   node contrib-versions.mjs contrib/*.json        ->  1.16.5\n1.18.2\n26.2
import fs from 'node:fs';
import { blockTarget } from './contrib-gate.mjs';

const vers = new Set();
for (const f of process.argv.slice(2)) {
  let sub; try { sub = JSON.parse(fs.readFileSync(f, 'utf8')); } catch { continue; }
  for (const e of (sub.entries || [])) {
    const v = blockTarget(e.block || sub.target || '26.2');
    if (v) vers.add(v);
  }
}
// oldest first, so a capped run still covers the version ladder rather than clustering at one end
const cmp = (a, b) => { const A = a.split('.').map(Number), B = b.split('.').map(Number);
  return (A[0] - B[0]) || ((A[1] || 0) - (B[1] || 0)) || ((A[2] || 0) - (B[2] || 0)); };
console.log([...vers].sort(cmp).join('\n'));
