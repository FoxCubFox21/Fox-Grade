#!/usr/bin/env node
// The whole mechanical pipeline as ONE command producing ONE artifact.
//
// The census chained stages by filename suffix — .mx.jar, .br.jar, .dp.jar, .sx.jar — and every
// stage had to guess which suffix was current. That produced wrong measurements twice in one
// afternoon, both times by reading a stale stage: `ls` sorts suffixes alphabetically, and a stage
// that declines to write leaves the previous file as the newest. A pipeline is a chain, so it is
// written as one here: each stage hands the next an explicit path, a stage that does nothing passes
// its input through, and the only artifact anyone else ever needs to know about is <name>.final.jar.
//
//   node port-pipeline.mjs mod.jar --from 26.1 --to 26.2 --classpath "..." --source mc-26.1.jar \
//        --out final.jar [--deps-dir dir] [--work dir]
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = {}, pos = [];
for (let i = 2; i < process.argv.length; i++) { const t = process.argv[i]; t.startsWith('--') ? args[t.slice(2)] = process.argv[++i] : pos.push(t); }
const JAR = pos[0];
if (!JAR || !args.classpath) { console.error('usage: node port-pipeline.mjs <mod.jar> --from A --to B --classpath "..." [--source src.jar] [--out final.jar] [--deps-dir d]'); process.exit(2); }
const FROM = args.from || '26.1', TO = args.to || '26.2';
const name = path.basename(JAR).replace(/\.jar$/, '');
const WORK = args.work || fs.mkdtempSync(path.join(process.env.HOME, '.foxgrade-pipe-'));
fs.mkdirSync(WORK, { recursive: true });
const OUT = args.out || path.join(WORK, `${name}.final.jar`);

const run = (script, a) => spawnSync('node', [path.join(HERE, script), ...a], { encoding: 'utf8', maxBuffer: 128e6 });

let current = JAR;
const step = (label, script, a, outFile) => {
  const r = run(script, a);
  // A stage either wrote its output or it did not; both are fine, and which happened is stated
  // rather than inferred from directory listings later.
  if (fs.existsSync(outFile) && fs.statSync(outFile).size > 0) { current = outFile; console.log(`  ${label.padEnd(10)} applied`); }
  else console.log(`  ${label.padEnd(10)} nothing to do`);
  if (r.status !== 0 && !fs.existsSync(outFile)) console.log(`    (exit ${r.status}: ${(r.stderr || '').split('\n')[0].slice(0, 100)})`);
};

console.log(`${name}  ${FROM} -> ${TO}`);
const p = (suffix) => path.join(WORK, `${name}.${suffix}.jar`);
step('remap',    'jar-remap.mjs',   [current, '--from', FROM, '--to', TO, '--classpath', args.classpath, '--out', p('1')], p('1'));
step('retarget', 'mixin-check.mjs', [current, '--classpath', args.classpath, ...(args.source ? ['--source-classpath', args.source] : []), '--out', p('2')], p('2'));
step('bridge',   'jar-bridge.mjs',  [current, '--from', FROM, '--to', TO, '--classpath', args.classpath, '--out', p('3')], p('3'));
step('deps',     'jar-deps.mjs',    [current, '--original', JAR, '--to', TO, '--out', p('4'), ...(args['deps-dir'] ? ['--deps-dir', args['deps-dir']] : [])], p('4'));
// The bridge and the dep swap can both unlock a retarget that was refused before — a selector that
// was also a live reference stops being one once the call site moved into Compat — so the mixin pass
// runs again at the end rather than trusting its first answer.
step('retarget2', 'mixin-check.mjs', [current, '--classpath', args.classpath, ...(args.source ? ['--source-classpath', args.source] : []), '--out', p('5')], p('5'));
fs.copyFileSync(current, OUT);
console.log(`  final      ${OUT}`);
