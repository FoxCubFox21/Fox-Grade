// Any import that survives the full deterministic pipeline is either deleted or renamed with no
// derivable mapping. Recording them as anti-facts stops the AI re-deriving the same dead ends.
import fs from 'node:fs'; import path from 'node:path'; import { spawnSync } from 'node:child_process';
const cp = fs.readFileSync('/tmp/foxgrade_cp.txt','utf8').trim();
const real = new Set();
for (const jar of cp.split(':')) { if (!jar.endsWith('.jar')||!fs.existsSync(jar)) continue;
  const r = spawnSync('unzip',['-Z1',jar,'*.class'],{encoding:'utf8',maxBuffer:64e6}); if (r.status!==0||!r.stdout) continue;
  for (const l of r.stdout.split('\n')) { const p=l.trim(); if (p.endsWith('.class')&&!p.includes('$')) real.add(p.slice(0,-6).replace(/\//g,'.')); } }
function walk(d,o=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);
  e.isDirectory()?walk(p,o):(p.endsWith('.java')&&o.push(p));}return o;}
// Build the same resolver the porter uses, so we only flag what genuinely survives it.
const rj = JSON.parse(fs.readFileSync('rules.json','utf8'));
const flat = new Map();
for (const [k,v] of Object.entries(rj)) { if (k.startsWith('_')) continue;
  for (const r of (v.renames||[])) if (r.verified !== false && !flat.has(r.fromFqcn)) flat.set(r.fromFqcn, r.toFqcn); }
const resolve = (fq) => { let c=fq, seen=new Set();
  while (flat.has(c) && !seen.has(c)) { if (real.has(c) && c !== fq) return c; seen.add(c); c=flat.get(c); }
  return c; };
const unresolved = new Map();
for (const f of walk(process.argv[2])) for (const m of fs.readFileSync(f,'utf8').matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) {
  const i = m[1];
  if (!/^(net\.minecraft|com\.mojang|net\.minecraftforge)\./.test(i)) continue;
  if (real.has(i)) continue;
  if (resolve(i) !== i && real.has(resolve(i))) continue;   // BUG FIX: apply the rules FIRST — an import
                                                            // the pipeline can already fix is not an anti-fact
  unresolved.set(i, (unresolved.get(i)||0)+1);
}
const top = [...unresolved.entries()].sort((a,b)=>b[1]-a[1]);
const d = JSON.parse(fs.readFileSync('rules.json','utf8'));
d['26.2'].deleted = d['26.2'].deleted || [];
const have = new Set(d['26.2'].deleted.map(x=>x.fqcn));
let added=0;
for (const [fq,count] of top.slice(0,120)) {
  if (have.has(fq)) continue;
  const forge = /^net\.minecraftforge\./.test(fq);
  d['26.2'].deleted.push({ fqcn: fq, uses: count,
    replacement: forge ? 'Forge/FML API — no Fabric equivalent; port the concept, not the class.'
                       : 'No derivable mapping (deleted or renamed with no published table). Needs a real replacement design, not a rename.',
    verifiedOn: '26.2', source: 'pipeline-unresolved' });
  added++;
}
fs.writeFileSync('rules.json', JSON.stringify(d,null,2)+'\n');
console.log(`  recorded ${added} new anti-fact(s); 26.2 now has ${d['26.2'].deleted.length}`);
console.log('  most-used unresolved:'); for (const [f,c] of top.slice(0,6)) console.log(`    ${c}x  ${f}`);
