#!/usr/bin/env node
// Gatekeeper for community-contributed rules. Trust NOTHING in the submission except the class-name
// pair itself; re-establish every claim locally against the real target jars and our own anti-facts.
// This is what stops one broken install from poisoning everyone.
//
//   node verify-contrib.mjs their-rules.json --classpath "<target jars>"
import fs from 'node:fs'; import { spawnSync } from 'node:child_process';
const args={}; const pos=[];
for(let i=2;i<process.argv.length;i++){const t=process.argv[i];t.startsWith('--')?args[t.slice(2)]=process.argv[++i]:pos.push(t);}
const file=pos[0]; if(!file){console.error('usage: node verify-contrib.mjs <contribution.json> [--classpath ...]');process.exit(2);}
const cp=args.classpath||(fs.existsSync('/tmp/foxgrade_cp.txt')?fs.readFileSync('/tmp/foxgrade_cp.txt','utf8').trim():'');
if(!cp){console.error('need --classpath to verify against the real jars');process.exit(2);}

const real=new Set();
for(const jar of cp.split(':')){
  if(!jar.endsWith('.jar')||!fs.existsSync(jar))continue;
  const r=spawnSync('unzip',['-Z1',jar,'*.class'],{encoding:'utf8',maxBuffer:64e6});
  if(r.status!==0||!r.stdout)continue;
  for(const l of r.stdout.split('\n')){const p=l.trim();
    if(p.endsWith('.class')&&!p.includes('$'))real.add(p.slice(0,-6).replace(/\//g,'.'));}
}
const mine=JSON.parse(fs.readFileSync('rules.json','utf8'));
const banned=new Set((mine['26.2'].deleted||[]).map(x=>x.fqcn));
const known=new Map();
for(const [k,v] of Object.entries(mine)){ if(k.startsWith('_'))continue;
  for(const r of (v.renames||[])) if(r.verified!==false&&!known.has(r.fromFqcn)) known.set(r.fromFqcn,r.toFqcn); }
const auth=new Map();
for(const [k,v] of Object.entries(mine)){ if(k.startsWith('_'))continue;
  for(const r of (v.renames||[])) if(r.source==='official-mappings'&&!auth.has(r.fromFqcn)) auth.set(r.fromFqcn,r.toFqcn); }

const sub=JSON.parse(fs.readFileSync(file,'utf8'));
const ok=[],bad=[],dup=[],collide=[];
const ALLOWED=['repair-diff','direct-jar-resolution','bridge','official-mappings','human-groundtruth','rendering-audit'];
for(const e of (sub.entries||[])){
  const {from,to,proof}=e;
  if(!from||!to||!/^[a-z][\w.]*\.[A-Z]\w*$/.test(from)||!/^[a-z][\w.]*\.[A-Z]\w*$/.test(to))
    { bad.push([from,to,'malformed class name']); continue; }
  if(!ALLOWED.includes(proof)) { bad.push([from,to,`unaccepted proof "${proof}"`]); continue; }
  if(banned.has(from))        { bad.push([from,to,'source is a known anti-fact']); continue; }
  if(!real.has(to))           { bad.push([from,to,'target does not exist in the target jars']); continue; }
  if(auth.has(from)&&auth.get(from)!==to)
                              { collide.push([from,to,`contradicts authoritative ${auth.get(from)}`]); continue; }
  if(known.has(from))         { dup.push([from,to]); continue; }
  ok.push(e);
}
console.log(`  submission: ${(sub.entries||[]).length} entries (target ${sub.target||'?'})`);
console.log(`    ACCEPT  : ${ok.length}`);
console.log(`    already : ${dup.length}`);
console.log(`    REJECT  : ${bad.length+collide.length}`);
for(const [f,t,why] of [...bad,...collide].slice(0,8)) console.log(`      ✗ ${f} → ${t}   (${why})`);
if(ok.length) fs.writeFileSync('accepted-contrib.json',JSON.stringify({entries:ok},null,1));
console.log(ok.length?'\n  wrote accepted-contrib.json — merge only these':'\n  nothing accepted');
process.exit((bad.length+collide.length)?1:0);   // non-zero fails CI
