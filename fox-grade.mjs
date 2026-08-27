#!/usr/bin/env node
// foxgrade — AI-assisted Minecraft mod porter (prototype)
//
// Ports a single decompiled/source Java file from one Minecraft version to another
// using Claude Opus 5. This is the "synthesis" step: it drafts the port and a report
// of exactly what it changed and what a human must verify. It is a DEV ASSISTANT — the
// output must be compiled and tested; it is not a runtime "install and it works" tool.
//
// Zero dependencies (raw https, like the user's other tools).
//
// Usage:
//   ANTHROPIC_API_KEY=sk-... node foxgrade.mjs <input.java> --from 26.1 --to 26.2 \
//       [--out ported.java] [--notes "extra context or mappings"]
//   ANTHROPIC_API_KEY=sk-... node foxgrade.mjs --selftest   # verify API/model/key

import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawnSync, spawn } from 'node:child_process';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));

// Pull a top-level "## <heading>" section (to end of text) out of the model reply.
function extractSection(text, heading) {
  const mm = text.match(new RegExp(`(?:^|\\n)(##\\s*${heading}\\b[\\s\\S]*)$`, 'i'));
  return mm ? mm[1].trim() : '';
}

function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

// Executable migration ruleset (rules.json), keyed by TARGET version. Only verified renames auto-apply.
// Order the mapping journey properly: normalise the source's NAMING SCHEME first (MCP/Yarn -> mojmap),
// then walk the authoritative version ladder in order, then the jar-verified hop into the target.
// Without this the 40k scheme rules sit unused, because they live in blocks keyed by scheme, not target.
const VER_RE = /(\d+)\.(\d+)(?:\.(\d+))?/;
function verOf(s) { const m = String(s).match(VER_RE); return m ? [+m[1], +m[2], +(m[3] || 0)] : null; }
const verCmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

// Which naming scheme is this source written in? Decide by which scheme table actually hits its imports.
function detectScheme(data, srcSamples) {
  const imports = new Set();
  for (const src of srcSamples)
    for (const m of src.matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) imports.add(m[1]);
  let best = null, bestHits = 0;
  for (const [k, v] of Object.entries(data)) {
    const g = k.match(/^scheme:(\w+)@([\d.]+)->mojmap$/);
    if (!g) continue;
    let hits = 0;
    for (const r of v.renames || []) if (imports.has(r.fromFqcn)) hits++;
    if (hits > bestHits) { bestHits = hits; best = { key: k, scheme: g[1], ver: g[2] }; }
  }
  return bestHits >= 2 ? { ...best, hits: bestHits } : null;
}

// Compose scheme table + ordered ladder + target block into ONE flat map of fromFqcn -> final name.
function buildResolver(data, fromVer, toVer, schemeKey) {
  const flat = new Map();
  const add = (block) => { for (const r of (block?.renames || [])) if (r.verified !== false && !flat.has(r.fromFqcn)) flat.set(r.fromFqcn, r.toFqcn); };
  // 1) scheme normalisation
  const scheme = schemeKey ? data[schemeKey] : null;
  // 2) authoritative version ladder, in ascending version order, restricted to the span we need
  const fv = verOf(fromVer), tv = verOf(toVer);
  const ladder = Object.entries(data)
    .map(([k, v]) => { const m = k.match(/^([\d.]+)->([\d.]+)$/); return m ? { k, v, a: verOf(m[1]), b: verOf(m[2]) } : null; })
    .filter(Boolean)
    .filter((x) => (!fv || verCmp(x.a, fv) >= 0) && (!tv || verCmp(x.b, tv) <= 0))
    .sort((x, y) => verCmp(x.a, y.a));
  // Resolve each scheme entry all the way through the ladder + target block.
  const stepMaps = ladder.map((x) => new Map((x.v.renames || []).map((r) => [r.fromFqcn, r.toFqcn])));
  const target = new Map(((data[toVer] || {}).renames || []).map((r) => [r.fromFqcn, r.toFqcn]));
  // Halt as soon as the name is valid in the target — continuing can overshoot into a later redesign.
  const valid = (x) => TARGET_CLASSES && TARGET_CLASSES.has(x);
  const walk = (start) => {
    let c = start;
    for (const m of stepMaps) { if (valid(c)) return c; if (m.has(c)) c = m.get(c); }
    if (valid(c)) return c;
    if (target.has(c)) c = target.get(c);
    return c;
  };
  if (scheme) for (const r of scheme.renames || []) { const end = walk(r.toFqcn); if (end !== r.fromFqcn) flat.set(r.fromFqcn, end); }
  for (const m of stepMaps) for (const [k] of m) if (!flat.has(k)) { const end = walk(k); if (end !== k) flat.set(k, end); }
  for (const [k, v] of target) if (!flat.has(k)) flat.set(k, v);
  return flat;
}

// Index of classes that really exist in the target, so chains stop at the right place instead of
// walking past a correct answer into a later-version redesign (MatrixStack->PoseStack->GuiGraphics).
let TARGET_CLASSES = null;
function indexTargetClasses(classpath) {
  if (!classpath) return null;
  const set = new Set();
  for (const jar of classpath.split(':')) {
    if (!jar.endsWith('.jar') || !fs.existsSync(jar)) continue;
    const r = spawnSync('unzip', ['-Z1', jar, '*.class'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    if (r.status !== 0 || !r.stdout) continue;
    for (const l of r.stdout.split('\n')) {
      const p2 = l.trim();
      if (p2.endsWith('.class') && !p2.includes('$')) set.add(p2.slice(0, -6).replace(/\//g, '.'));
    }
  }
  return set.size ? set : null;
}

function loadRules(toVer, fromVer, srcSamples) {
  const f = path.join(SCRIPT_DIR, 'rules.json');
  const empty = { renames: [], advisories: [], deleted: [] };
  if (!fs.existsSync(f)) return empty;
  let data;
  try { data = JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return empty; }
  const r = data[toVer] || {};
  // Learning ⑥: compose rules across versions. A 1.8->1.12 move plus a 1.12->26.2 move chains into a
  // direct 1.8->26.2 rule, so mining adjacent version pairs once covers every jump.
  const chain = [];
  for (const [ver, block] of Object.entries(data)) {
    if (ver === toVer || ver.startsWith('_')) continue;
    // only chain through name-preserving moves: the simple name is an invariant, so a chain cannot drift
    for (const rule of (block.renames || []).filter((x) => x.verified && x.chainable && x.fromSimple === x.toSimple)) chain.push(rule);
  }
  const own = (r.renames || []).filter((x) => x.verified);
  const seen = new Set(own.map((x) => x.fromFqcn));
  for (const c of chain) {
    const next = own.find((o) => o.fromFqcn === c.toFqcn); // c: A->B, next: B->C  =>  A->C
    if (next && !seen.has(c.fromFqcn)) {
      own.push({ ...c, toFqcn: next.toFqcn, toSimple: next.toSimple, note: `chained via ${c.toFqcn}` });
      seen.add(c.fromFqcn);
    }
  }
  // Scheme-aware resolution: normalise the source scheme, then walk the ladder in version order.
  let schemeInfo = null;
  if (srcSamples && srcSamples.length) {
    schemeInfo = detectScheme(data, srcSamples);
    if (schemeInfo) {
      const flat = buildResolver(data, fromVer, toVer, schemeInfo.key);
      const seen = new Set(own.map((x) => x.fromFqcn));
      for (const [from, to] of flat) {
        if (seen.has(from) || from === to) continue;
        own.push({ fromFqcn: from, toFqcn: to, fromSimple: from.split('.').pop(), toSimple: to.split('.').pop(), verified: true, kind: 'resolved' });
      }
    }
  }
  // Member mappings: SRG tokens (func_/field_/p_) are GLOBALLY UNIQUE, so a plain token rewrite is
  // sound — no receiver-type analysis needed. Prefer the table for the detected scheme's version.
  const members = new Map();
  const wantVer = schemeInfo?.ver;
  for (const [k, v] of Object.entries(data)) {
    if (!k.startsWith('members:')) continue;
    if (wantVer && !k.includes('@' + wantVer + '-')) continue;
    for (const [a, b] of Object.entries(v.members || {})) if (!members.has(a)) members.set(a, b);
  }
  if (!members.size) for (const [k, v] of Object.entries(data)) {   // fall back to any member table
    if (!k.startsWith('members:')) continue;
    for (const [a, b] of Object.entries(v.members || {})) if (!members.has(a)) members.set(a, b);
  }
  return { renames: own, advisories: r.advisories || [], deleted: r.deleted || [], schemeInfo, members };
}

// Deterministically apply verified rename/move rules to Java source (no AI). Returns {code, applied[]}.
// A rule fires only if the file imports or fully-qualifies the old class, so bare simple-name rewrites are safe.
function applyRules(src, rules) {
  let code = src;
  const applied = [];
  for (const r of rules.renames) {
    const importRe = new RegExp(`(^|\\n)\\s*import\\s+(static\\s+)?${escapeRe(r.fromFqcn)}\\s*;`);
    const hasImport = importRe.test(code);
    // Match the WHOLE fqcn only — trailing lookahead stops "net.minecraft.block.Block" from
    // matching inside "net.minecraft.block.BlockSapling".
    const fqcnRe = new RegExp(`${escapeRe(r.fromFqcn)}(?![A-Za-z0-9_$])`, 'g');
    if (!hasImport && !fqcnRe.test(code)) continue;
    fqcnRe.lastIndex = 0;
    code = code.replace(fqcnRe, r.toFqcn); // rewrite the import line + any fully-qualified use
    if (r.fromSimple !== r.toSimple && hasImport) { // simple name changed -> rewrite bare usages too
      code = code.replace(new RegExp(`\\b${escapeRe(r.fromSimple)}\\b`, 'g'), r.toSimple);
    }
    applied.push(`${r.fromFqcn} -> ${r.toFqcn}`);
  }
  // Member-level pass: rewrite SRG tokens to their real names. Safe because func_/field_/p_ names are
  // globally unique identifiers, unlike class simple names.
  if (rules.members && rules.members.size) {
    let hits = 0;
    code = code.replace(/\b((?:func_|field_|p_)\w+)\b/g, (tok) => {
      const to = rules.members.get(tok);
      if (!to) return tok;
      hits++; return to;
    });
    if (hits) applied.push(`${hits} SRG member token(s) -> real names`);
  }
  return { code, applied };
}

// ---------- Learning ①②: mine rules from a repair DIFF, not from the model's prose ----------
// The model's "## Learned" text is it *claiming* what it did; the diff of a repair that turned a
// FAILING compile into a PASSING one is proof. We pair imports removed/added across that diff.
function importsOf(src) {
  const out = new Map(); // fqcn -> simpleName
  for (const m of src.matchAll(/^\s*import\s+(?:static\s+)?([a-z][\w.]*\.[A-Z]\w*)\s*;/gm)) {
    out.set(m[1], m[1].slice(m[1].lastIndexOf('.') + 1));
  }
  return out;
}
function extractImportRenames(before, after) {
  const b = importsOf(before), a = importsOf(after);
  const removed = [...b.keys()].filter((k) => !a.has(k));
  const added = [...a.keys()].filter((k) => !b.has(k));
  const rules = [];
  const usedAdded = new Set();
  // 1) pair by identical simple name -> a package MOVE (highest confidence)
  for (const r of removed) {
    const s = b.get(r);
    const hit = added.find((x) => a.get(x) === s && !usedAdded.has(x));
    if (hit) { usedAdded.add(hit); rules.push({ fromFqcn: r, toFqcn: hit, fromSimple: s, toSimple: s, kind: 'move' }); }
  }
  // 2) a clean 1:1 leftover -> a genuine RENAME (e.g. ResourceLocation -> Identifier).
  //    Only when unambiguous, and only because the compiler accepted the result.
  const remLeft = removed.filter((r) => !rules.some((x) => x.fromFqcn === r));
  const addLeft = added.filter((x) => !usedAdded.has(x));
  if (remLeft.length === 1 && addLeft.length === 1) {
    const fs_ = b.get(remLeft[0]), ts = a.get(addLeft[0]);
    if (!new RegExp(`\\b${fs_}\\b`).test(after)) { // old name really is gone from the fixed file
      rules.push({ fromFqcn: remLeft[0], toFqcn: addLeft[0], fromSimple: fs_, toSimple: ts, kind: 'rename' });
    }
  }
  return rules;
}
// Learning ④: every rule carries provenance so stale facts can be superseded, never silently trusted.
function recordLearnedRules(toVer, learned) {
  if (!learned.length) return 0;
  const rf = path.join(SCRIPT_DIR, 'rules.json');
  let data = {};
  try { data = JSON.parse(fs.readFileSync(rf, 'utf8')); } catch { return 0; }
  data[toVer] = data[toVer] || { renames: [], advisories: [], deleted: [] };
  const have = new Set(data[toVer].renames.map((r) => r.fromFqcn));
  let n = 0;
  for (const r of learned) {
    if (have.has(r.fromFqcn)) continue;
    have.add(r.fromFqcn);
    data[toVer].renames.push({
      fromFqcn: r.fromFqcn, toFqcn: r.toFqcn, fromSimple: r.fromSimple, toSimple: r.toSimple,
      verified: true,
      source: 'repair-diff', verifiedOn: toVer, provenance: 'javac-proven: this change made a failing compile pass',
      note: `${r.kind} learned from a compile-fixing repair`,
    });
    n++;
  }
  if (n) fs.writeFileSync(rf, JSON.stringify(data, null, 2) + '\n');
  return n;
}

// Learning ⑤: inject only the knowledge that MENTIONS symbols present in this file, instead of dumping
// the whole base into every prompt (cheaper, faster, and less noise for the model to get lost in).
function relevantKnowledge(knowledgeText, src, limit = 60) {
  if (!knowledgeText) return '';
  const syms = new Set();
  for (const m of src.matchAll(/\b([A-Z][A-Za-z0-9_]{2,})\b/g)) syms.add(m[1]);
  const lines = knowledgeText.split('\n');
  const keep = lines.filter((l) => {
    if (!/^\s*[-*]/.test(l)) return false;
    for (const m of l.matchAll(/\b([A-Z][A-Za-z0-9_]{2,})\b/g)) if (syms.has(m[1])) return true;
    return false;
  });
  if (!keep.length) return '';
  return keep.slice(0, limit).join('\n');
}

// Rule #5: deterministically bump a fabric.mod.json's declared MC/loader versions so the ported mod
// actually LOADS (a mod still declaring the old version is refused by the loader). Returns {json, notes} or null.
function bumpFabricModJson(txt, toVer) {
  let data;
  try { data = JSON.parse(txt); } catch { return null; }
  data.depends = data.depends || {};
  const notes = [];
  data.depends.minecraft = '>=' + toVer;
  notes.push(`depends.minecraft = ">=${toVer}"`);
  if (data.depends.java === undefined) { data.depends.java = '>=21'; notes.push('depends.java = ">=21"'); }
  return { json: JSON.stringify(data, null, 2) + '\n', notes };
}

const MODEL = 'claude-opus-5';
let RUN_EFFORT = 'xhigh'; // port/repair/review effort; --effort lowers it for speed (xhigh is slow but best)
// Files port independently, so overlapping them cuts wall-clock without changing ANY output.
// This is the free speedup: same model, same effort, same prompts — just not one-at-a-time.
let JOBS = 4;
let TO_VERSION = ''; // target version — rules/knowledge are scoped to it (learning ④: no unscoped facts)
const API_VERSION = '2023-06-01';
// Opus 5 code should ship the refusal fallback by default (docs guidance).
const BETA = 'server-side-fallback-2026-07-01';

function parseArgs(argv) {
  const a = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i];
    if (['--selftest', '--promote', '--verify', '--deep', '--review', '--rules-dry', '--via-cli', '--foxai'].includes(t)) a[t.slice(2)] = true; // boolean flags
    else if (t.startsWith('--')) a[t.slice(2)] = argv[++i];
    else a._.push(t);
  }
  return a;
}

// POST /v1/messages with stream:true; resolves the full accumulated text + stop reason.
// effort: 'xhigh' for porting; disableThinking (effort must be <= 'high' on Opus 5) for the self-test.
function callClaudeOnce({ apiKey, system, userText, maxTokens = 64000, effort = 'xhigh', disableThinking = false }) {
  const req_body = {
    model: MODEL,
    max_tokens: maxTokens,
    stream: true,
    output_config: { effort },
    fallbacks: 'default', // re-route a safety refusal instead of just stopping
    system,
    messages: [{ role: 'user', content: userText }],
  };
  if (disableThinking) req_body.thinking = { type: 'disabled' }; // else thinking is on by default on Opus 5
  const body = JSON.stringify(req_body);

  const opts = {
    method: 'POST',
    hostname: 'api.anthropic.com',
    path: '/v1/messages',
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': API_VERSION,
      'anthropic-beta': BETA,
      'content-length': Buffer.byteLength(body),
    },
  };

  return new Promise((resolve, reject) => {
    const req = https.request(opts, (res) => {
      let raw = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (raw += c));
      // Non-2xx: body is a JSON error, not SSE.
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.on('end', () => reject(new Error(`HTTP ${res.statusCode}: ${raw.slice(0, 800)}`)));
        return;
      }
      let text = '';
      let stop = null;
      let servedBy = null;
      let buf = '';
      res.on('data', () => {
        // Process complete lines out of the growing buffer.
        buf = raw.slice(processed);
        let idx;
        while ((idx = buf.indexOf('\n')) !== -1) {
          const line = buf.slice(0, idx).trim();
          processed += idx + 1;
          buf = raw.slice(processed);
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          let obj;
          try { obj = JSON.parse(payload); } catch { continue; }
          if (obj.type === 'content_block_delta' && obj.delta?.type === 'text_delta') text += obj.delta.text;
          else if (obj.type === 'message_delta' && obj.delta?.stop_reason) stop = obj.delta.stop_reason;
          else if (obj.type === 'message_start' && obj.message?.model) servedBy = obj.message.model;
          else if (obj.type === 'error') reject(new Error('stream error: ' + JSON.stringify(obj.error)));
        }
      });
      let processed = 0;
      res.on('end', () => resolve({ text, stop, servedBy }));
    });
    req.on('error', reject);
    req.setTimeout(15 * 60 * 1000, () => req.destroy(new Error('request timed out (15m)')));
    req.write(body);
    req.end();
  });
}

// ---- Alternative backend: the Claude Code CLI in headless mode (`claude -p`) ----
// Runs under the user's Claude Code SUBSCRIPTION instead of an API key. This shells out to the
// documented headless interface — it does NOT reuse or extract the login credential, which would not
// be a supported integration. Chosen automatically when there is no ANTHROPIC_API_KEY.
let USE_CLI = false;
function claudeCliAvailable() {
  const r = spawnSync('bash', ['-c', 'command -v claude'], { encoding: 'utf8' });
  return r.status === 0 && !!(r.stdout || '').trim();
}
// ASYNC on purpose: spawnSync would block Node's event loop, which silently serialises every call and
// makes --jobs do nothing. Promise-wrapped spawn lets the CLI route parallelise exactly like the API one.
function callClaudeCLI({ system, userText, model, effort }) {
  // The CLI ignored --system-prompt in testing, so fold the system prompt into the message instead.
  const prompt = `${system}\n\n---\n\n${userText}`;
  const args = ['-p'];
  if (model) args.push('--model', model);
  // The CLI DOES support effort (low|medium|high|xhigh|max) and warns on an unknown value, so unlike
  // --system-prompt it is genuinely parsed. This gives the subscription route the same quality dial.
  const eff = effort || RUN_EFFORT;
  if (eff) args.push('--effort', eff);
  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('error', (e) => reject(new Error(`claude CLI failed: ${e.message}`)));
    child.on('close', (code) => {
      if (code !== 0) return reject(new Error(`claude CLI exit ${code}: ${err.slice(0, 400)}`));
      resolve({ text: out, stop: 'end_turn', servedBy: `claude-code-cli (effort=${eff || 'default'})` });
    });
    child.stdin.end(prompt);
  });
}

// ---- FREE local backend: Ollama (no API key, no usage limits, no cost) ----
// Third backend alongside the API and the Claude Code CLI. Selected with --ollama [model].
// Honest positioning: a 14B local model is weaker than Opus on open-ended porting, but much of our
// AI work is now NARROW — "here is a compile error plus the real javap signature, fix it" — which a
// good local coder model can often handle. Whether it is good enough is a measurement, not a guess.
let USE_OLLAMA = false, OLLAMA_MODEL = 'qwen2.5-coder:1.5b';   // benchmarked equal to 14b on our tasks
let OLLAMA_CTX = 8192;
function callOllama({ system, userText }) {
  // ~3.5 chars/token; give headroom for the reply, but never pay for context we do not need.
  const need = Math.ceil((system.length + userText.length) / 3.5) + 4096;
  OLLAMA_CTX = need > 12288 ? 16384 : need > 6144 ? 8192 : 4096;
  const body = JSON.stringify({
    model: OLLAMA_MODEL,
    prompt: `${system}\n\n---\n\n${userText}`,
    stream: false,
    keep_alive: '30m',            // keep the model resident — a cold reload costs ~30-60s PER CALL
    options: {
      temperature: 0.1,
      num_ctx: OLLAMA_CTX,        // smaller context = faster; raised only when the prompt needs it
      num_predict: 4096,          // cap runaway generation
    },
  });
  const r = spawnSync('curl', ['-s', '--max-time', '900', 'http://localhost:11434/api/generate',
    '-H', 'content-type: application/json', '-d', body], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (r.status !== 0) throw new Error(`ollama request failed: ${(r.stderr || '').slice(0, 200)}`);
  let j;
  try { j = JSON.parse(r.stdout || '{}'); } catch { throw new Error('ollama returned non-JSON'); }
  if (j.error) throw new Error(`ollama: ${j.error}`);
  return { text: j.response || '', stop: 'end_turn', servedBy: `ollama:${OLLAMA_MODEL}` };
}

// Transient failures worth retrying: network resets/timeouts and overloaded/5xx responses.
// Includes API-side transients that arrive INSIDE the SSE stream (overloaded_error / api_error /
// rate_limit_error). Missing "overloaded" here once threw away a completed 29-minute whole-mod port.
const TRANSIENT = /ETIMEDOUT|ECONNRESET|EPIPE|ECONNREFUSED|socket hang up|timed out|EAI_AGAIN|ENOTFOUND|HTTP (?:429|5\d\d)|overloaded|api_error|rate_limit|Internal server error/i;

// FATAL: retrying or continuing is pointless and DANGEROUS — without this the tool once ground through
// 63 files reporting "ported" while every call failed on billing, producing un-ported code that looked
// converted. On a fatal error we abort the whole run loudly instead of emitting a fake success.
const FATAL = /credit balance is too low|invalid x-api-key|authentication_error|permission_error|Your account.*disabled/i;
let FATAL_ERROR = null; // set once; short-circuits all further calls

// Retry wrapper: a single ETIMEDOUT used to kill a whole whole-mod run. Back off and retry transient errors.
// Run async work over items with a concurrency cap, preserving input order in the results.
// Files are ported independently, so overlapping them cuts wall-clock with ZERO change to output.
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return out;
}

async function callClaude(opts, retries = 4) {
  if (USE_OLLAMA) return callOllama(opts);
  if (USE_CLI) return callClaudeCLI({ ...opts, model: opts.model, effort: opts.effort || RUN_EFFORT });
  if (FATAL_ERROR) throw new Error(FATAL_ERROR); // already dead — don't waste time or hide it
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await callClaudeOnce(opts);
    } catch (e) {
      lastErr = e;
      if (FATAL.test(e.message || '')) { // billing/auth: stop the entire run immediately
        FATAL_ERROR = e.message;
        throw e;
      }
      if (attempt < retries && TRANSIENT.test(e.message || '')) {
        const wait = Math.min(60000, 2000 * Math.pow(2, attempt)); // exponential: 2s,4s,8s,16s,32s
        process.stderr.write(`  network/API hiccup (${e.message}); retry ${attempt + 1}/${retries} in ${wait / 1000}s...\n`);
        await new Promise((r) => setTimeout(r, wait));
        continue;
      }
      throw e;
    }
  }
  throw lastErr;
}

const SYSTEM_PORT = `You are an expert Minecraft mod engineer who ports Java mod source across Minecraft versions, including very old ones.

You will be given ONE Java source file from a mod, the Minecraft version it targets, and the version to port it to. Produce a faithful port that preserves the mod's behavior.

Method:
- Identify every place the code touches the Minecraft/loader API and reason about what changed between the two versions (renamed/moved classes, changed method signatures, redesigned systems, removed APIs, mixin target drift).
- Prefer the new version's idiomatic replacement. If a capability was removed with no equivalent, keep the code compiling and flag it loudly rather than inventing an API that does not exist.
- Do NOT invent classes or methods you are not confident exist in the target version. When unsure, port conservatively and flag it for human verification.
- Do not refactor, rename, or "improve" beyond what the port requires.

Obfuscation and mappings — read carefully:
- Minecraft's names are obfuscated, and THE MAPPING IS REGENERATED FOR EVERY VERSION. A given obfuscated name does not carry across versions: obfuscated class names (short tokens like "bao", "aji"), Searge/SRG methods and fields ("func_70071_h_", "field_70170_p", "p_NNNNN_"), and even readable MCP names can all shift between versions. The same "func_NNNNN_x" maps to different members in different versions.
- Recognize which mapping scheme the SOURCE uses: raw Notch obfuscation (short one/two-letter identifiers), SRG/MCP Searge ("func_"/"field_"/"p_" prefixes), Yarn (Fabric), or official Mojang mappings (Mojmap, readable, 1.14.4+).
- You CANNOT reliably recall exact per-version obfuscation tables from memory. So: if the user provided a MAPPINGS block below, treat it as ground truth for resolving SOURCE-version names. If an obfuscated/Searge name is NOT covered by provided mappings and you are not certain what it refers to, DO NOT guess a mapping — port it as faithfully as you can, leave a clearly-marked TODO in the code, and list it under "Needs human verification" with the exact token and which version's mapping file is needed.

Pre-1.13 numeric IDs and "The Flattening":
- Before Minecraft 1.13, blocks and items had NUMERIC IDs plus metadata/damage values: block id 1 = stone; wool with metadata 14 = red wool; new ItemStack(Blocks.wool, 1, 14); Block.getBlockById(int); Item.getItemById(int); subtypes selected by damage value. 1.13 "flattened" these into namespaced string IDs (minecraft:red_wool), distinct block states, and separate item types; numeric IDs and metadata no longer exist.
- When porting FROM a pre-1.13 version, translate every numeric ID and metadata/damage value into its flattened registry name / blockstate / distinct item, and call out each mapping in the report. Those numeric-id-to-name mappings are also historically version-specific — if unsure of a specific id/meta pair, flag it rather than guessing.

Custom items/blocks and their assets:
- Registration APIs drift heavily: old GameRegistry.registerItem/registerBlock + setUnlocalizedName + setTextureName; modern loaders use a registry keyed by a namespaced Identifier/ResourceLocation, and textures/models/lang are SEPARATE asset files, not fields on the item. Port the registration code.
- A .java file cannot contain the item's texture/model/lang, so LIST in the report every asset file the ported item now needs and its exact modern path: the item model JSON (assets/<modid>/models/item/<name>.json), the texture PNG path (assets/<modid>/textures/item/<name>.png — note the pre-1.13 "items" folder became singular "item"), and the lang key (item.<modid>.<name>). Metadata subtypes (damage values) become distinct items/models — enumerate each one.

Code that became DATA:
- Many things registered in CODE in old versions are DATA files now: recipes (code recipe registration -> data/<ns>/recipe(s)/*.json), loot tables (block drops -> data/<ns>/loot_table(s)/blocks/*.json), and world generation (code worldgen -> data/<ns>/worldgen/**.json). If the source registers any of these in code, REMOVE the dead code and, in a "## Data files" section at the very end, output each needed file as its exact target path on one line followed by a fenced \`\`\`json block with the actual content, so the developer can drop it straight in. If you are unsure of the exact directory name for the target version (e.g. recipe vs recipes, loot_table vs loot_tables), say so.

Output format — exactly this, nothing before it:
1. A single fenced code block containing the COMPLETE ported Java file:
\`\`\`java
<full file>
\`\`\`
2. Then a section titled "## Port report" with these markdown bullet lists:
   - "Changes made" — each concrete change and why (old API -> new API; numeric id/meta -> flattened name).
   - "Needs human verification" — anything you are not certain of: invented-looking API calls, unresolved obfuscated/Searge names (give the token + which mapping file is needed), mixin targets, id/meta guesses, removed-capability workarounds. Be honest; this list existing is expected and good.
   - "Assumptions" — versions, mapping scheme, and APIs you assumed.
3. Then a section titled "## Learned" — durable, GENERALIZABLE facts worth remembering for future ports of ANY mod: specific old->new API/name mappings you are confident in, per-version obfuscation resolutions (write "token = meaning" and name the version), numeric-id/meta -> flattened-name facts, and reusable gotchas. One concise bullet each; nothing file-specific. If you learned nothing durable, write "- (none)".
Never claim the port compiles or runs — you cannot verify that.`;

const ASSET_SYSTEM = `You are an expert Minecraft mod engineer who ports mod RESOURCE/ASSET files across Minecraft versions.

You will be given ONE asset file (a lang file, a model or blockstate JSON, a .mcmeta, sounds.json, pack.mcmeta, fabric.mod.json/mods.toml, etc.), the version it targets, and the version to port it to. Produce the ported file and say exactly where it must live.

Key asset changes across versions:
- The 1.13 "Flattening" also hit assets: texture folders became singular — assets/<modid>/textures/items/ -> textures/item/, textures/blocks/ -> textures/block/. All registry names and asset paths are lowercase snake_case; metadata-based subtypes became distinct named assets.
- Lang files: pre-1.13 used assets/<modid>/lang/en_US.lang (KEY=VALUE lines, mixed-case locale). Since 1.13 it is en_us.json (lowercase locale, a JSON object). The translation-key scheme changed: "tile.foo.name"/"item.foo.name" -> "block.<modid>.foo" / "item.<modid>.foo"; creative-tab/itemGroup keys changed too.
- Item models: modern items need assets/<modid>/models/item/<name>.json, usually {"parent":"item/generated","textures":{"layer0":"<modid>:item/<name>"}}. Blocks need a block model plus a blockstates/<name>.json.
- pack.mcmeta "pack_format" is a version-specific integer that changes almost every release — if you are not certain of the exact number for the target version, use a placeholder and FLAG it loudly.
- Textures are PNG (binary) — you cannot transform them here. When a texture is referenced, state the OLD path and the NEW path so the user copies the PNG, and generate any model JSON that now references it.

Output format — exactly this, nothing before it:
1. A single fenced code block containing the COMPLETE ported file content.
2. Then "## Port report" with markdown bullet lists:
   - "Target path" — the exact assets/... path and filename the ported file must be placed at.
   - "Changes made".
   - "Also needs" — other asset files that must be created or copied for this to work (model JSONs, texture PNGs at their new paths, blockstates), with paths.
   - "Needs human verification" — anything version-specific you are unsure of, especially pack_format numbers and the exact target-version translation-key scheme.
   - "Assumptions".
3. Then a section titled "## Learned" — durable, GENERALIZABLE asset facts for future ports of ANY mod: version-specific path/format changes, translation-key scheme facts, pack_format numbers you are confident of, and reusable gotchas. One concise bullet each; nothing file-specific. If nothing durable, write "- (none)".
Never claim it works in-game — you cannot verify that.`;

const PLAN_SYSTEM = `You are an expert Minecraft mod engineer. You will be given ONE source file, its current Minecraft version, and the target version. Do NOT write any ported code. Produce a concrete MIGRATION PLAN that a porter will follow mechanically.

Think hard about every interaction with the Minecraft/loader API and decide the mapping BEFORE any code is written. Output markdown with exactly these sections:
- "## API touchpoints" — a bullet for every place the code touches the Minecraft/loader API. For each: the OLD symbol (class/method/field, including obfuscated/Searge tokens like func_NNNNN_x) and its target-version replacement. Mark each decision as one of: RESOLVED (give the new symbol), REMOVED (give the minimal workaround), or UNKNOWN (say what you'd need — e.g. "needs the ${'${from}'}->target mappings for token X"). Never guess an obfuscated mapping you aren't sure of; mark it UNKNOWN.
- "## IDs & flattening" — every pre-1.13 numeric block/item ID or metadata/damage value and its flattened registry name / blockstate / distinct item. "- (none)" if not applicable.
- "## Assets" — every model/texture/lang/blockstate asset the ported code will need, with exact modern target paths. "- (none)" if not applicable.
- "## Risks" — anything uncertain, invented-looking, behavior-changing, or that the compiler won't catch.
Be specific and terse. This plan is machine-followed, not prose. No preamble.`;

const REVIEW_SYSTEM = `You are a strict senior reviewer of a Minecraft mod port. You are given the ORIGINAL file (source version) and the PORTED file (target version). The port may already compile — your job is to catch what a compiler CANNOT:
- Hallucinated or misused target APIs: methods/classes that do not exist in the target version, or that exist but don't do what the ported code assumes.
- Silent behavior drift: the port compiles but changes behavior vs the original in a way the port did not require (e.g. a Flattening variant check that now matches fewer blocks, an off-by-one, a dropped side-effect).
- Missed touchpoints: something in the original that was not migrated or was left as a dead/no-op.
- Wrong numeric-ID/Flattening or obfuscation resolutions.
Be skeptical and specific; assume subtle bugs exist and go find them. Do NOT rewrite the file. Output markdown with exactly:
- "## Review verdict" — one of: LOOKS FAITHFUL / MINOR ISSUES / LIKELY BROKEN — then one sentence why.
- "## Issues" — a bullet per problem: the symbol or location, what's wrong, and the concrete fix. "- (none)" if genuinely none.
No preamble.`;

const REPAIR_SYSTEM = `You fix a ported Minecraft mod Java file that fails to compile against the target version.
You are given the current file and the javac error output. Return the COMPLETE corrected file as a single fenced \`\`\`java block, changing ONLY what the compiler errors require.
Do not invent APIs: if an error implies a renamed/moved member, use the correct current one; if a capability is genuinely gone, adapt minimally and add a // TODO comment. If a "cannot find symbol" refers to another class from the SAME mod (not net.minecraft/net.fabricmc/etc.), leave that reference as-is — it resolves when the whole mod compiles together — and say so.
After the code block, add a short "## Learned" section of durable, generalizable fix facts (or "- (none)").`;

// Port ONE file. Writes the ported file to outFile; returns {ok, report, learned, plan, servedBy, stop} or {ok:false, reason}.
// deep: run a plan stage first (analyze API surface + decide mappings), then port while following the plan.
async function portFile({ apiKey, input, from, to, notesText, mappingsText, knowledgeText, outFile, deep = false, rules = null }) {
  const ext = path.extname(input).toLowerCase();
  const isJava = ext === '.java';
  const src = fs.readFileSync(input, 'utf8');
  const kind = isJava ? 'Java source file' : `resource/asset file (${ext})`;
  const system = isJava ? SYSTEM_PORT : ASSET_SYSTEM;

  // Deterministic pass (rule #1): apply verified rename/move rules BEFORE the AI sees the code.
  let portSrc = src, appliedRules = [];
  if (isJava && rules && rules.renames.length) { const ar = applyRules(src, rules); portSrc = ar.code; appliedRules = ar.applied; }
  // Learning ⑤: retrieve only the knowledge relevant to THIS file instead of dumping the whole base.
  const rel = relevantKnowledge(knowledgeText, src);
  const scopedKnowledge = rel
    ? `\nVERIFIED KNOWLEDGE relevant to the symbols in this file (from past ports — prefer over guessing):\n${rel}\n`
    : '';

  // #4: structural-matcher candidates as HINTS. The matcher is ~70% accurate — unusable as truth, but
  // "is it this class? here is the evidence" is a far cheaper question for the model than deriving a
  // mapping cold. Explicitly labelled unverified so it is never treated as fact.
  let matcherBlock = '';
  try {
    const cf = path.join(SCRIPT_DIR, 'rules.candidates.json');
    if (isJava && fs.existsSync(cf)) {
      const cand = JSON.parse(fs.readFileSync(cf, 'utf8'));
      const hints = [];
      for (const [k, v] of Object.entries(cand)) {
        if (!k.startsWith('matcher:')) continue;
        for (const r of (v.renames || []).slice(0, 4000)) {
          const simple = String(r.fromObf || '').split('.').pop();
          if (simple && simple.length > 3 && src.includes(simple)) hints.push(`${r.fromObf} =? ${r.toObf} (confidence ${r.score})`);
          if (hints.length >= 12) break;
        }
      }
      if (hints.length) matcherBlock = `\nUNVERIFIED structural-matcher suggestions (~70% accurate — treat as leads to CHECK, never as fact; reject any that do not fit):\n- ${hints.join('\n- ')}\n`;
    }
  } catch {}

  // Learning ③: anti-facts. Telling the model what has NO equivalent stops it re-exploring dead ends
  // and inventing a mapping for a class that was genuinely deleted.
  const deletedHere = (rules?.deleted || []).filter((d) => src.includes(d.fqcn?.split('.').pop() || d));
  const antiBlock = deletedHere.length
    ? `\nCLASSES WITH NO EQUIVALENT in ${to} (verified deleted — do NOT map these to a same-named class, that is a name collision; use the stated replacement approach):\n- ${deletedHere.map((d) => `${d.fqcn || d}: ${d.replacement || 'no direct replacement'}`).join('\n- ')}\n`
    : '';
  const ruleBlock = (rules && isJava && (appliedRules.length || rules.advisories.length))
    ? `\nDETERMINISTIC MIGRATIONS already applied to the source below for ${to} (verified — do NOT revert these):\n- ${appliedRules.length ? appliedRules.join('\n- ') : '(no verified rename matched this file)'}\n` +
      (rules.advisories.length ? `Known ${to} changes to apply where they occur (reliable — prefer over guessing):\n- ${rules.advisories.join('\n- ')}\n` : '')
    : '';
  // Rule #4: mixins need special care — their @Inject targets point at vanilla methods that get renamed.
  const mixinNote = (isJava && /@Mixin\b/.test(src))
    ? `\nThis is a MIXIN. Its @Inject/@Redirect/@ModifyArg/@At target method names and descriptors point at vanilla methods that are renamed or removed across versions. Re-point every target to its correct ${to} equivalent using the real target-class signatures, fix @At locations, and flag any target you cannot confirm rather than guessing a descriptor.\n`
    : '';

  // Deep mode: analyze + plan the migration before writing any code.
  let planText = '';
  if (deep) {
    const planUser =
      `Produce a migration plan to port this ${kind} from Minecraft ${from} to Minecraft ${to}.${notesText}${mappingsText}${scopedKnowledge}${ruleBlock}${antiBlock}${matcherBlock}${mixinNote}\n` +
      `File: ${path.basename(input)}\n\n\`\`\`\n${portSrc}\n\`\`\``;
    const pr = await callClaude({ apiKey, system: PLAN_SYSTEM, userText: planUser, effort: RUN_EFFORT });
    if (pr.stop !== 'refusal') planText = pr.text.trim();
  }
  const planBlock = planText
    ? `\nFOLLOW THIS MIGRATION PLAN (already analyzed for you — execute it; correct it only if a step is clearly wrong):\n${planText}\n`
    : '';

  const userText =
    `Port this ${kind} from Minecraft ${from} to Minecraft ${to}.${notesText}${mappingsText}${scopedKnowledge}${ruleBlock}${antiBlock}${matcherBlock}${mixinNote}${planBlock}\n` +
    `File: ${path.basename(input)}\n\n\`\`\`\n${portSrc}\n\`\`\``;
  const r = await callClaude({ apiKey, system, userText, effort: RUN_EFFORT });
  if (r.stop === 'refusal') return { ok: false, reason: 'refusal' };
  const m = r.text.match(/```[a-z0-9]*\s*\n([\s\S]*?)\n```/);
  if (!m) return { ok: false, reason: 'no-fenced-block', raw: r.text };
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, m[1].trimEnd() + '\n');
  const report = r.text.slice(r.text.indexOf(m[0]) + m[0].length).trim() || '(no report section returned)';
  return { ok: true, report, learned: extractSection(r.text, 'Learned'), plan: planText, appliedRules, servedBy: r.servedBy, stop: r.stop };
}

// Semantic second opinion: catch "compiles but wrong" — hallucinated APIs, behavior drift, missed touchpoints.
// Runs on the FINAL file (after any compile-repair), so it reviews what will actually ship.
async function reviewPort({ apiKey, originalFile, portedFile, from, to }) {
  const orig = fs.readFileSync(originalFile, 'utf8');
  const ported = fs.readFileSync(portedFile, 'utf8');
  const userText =
    `Original file (Minecraft ${from}):\n\`\`\`java\n${orig}\n\`\`\`\n\n` +
    `Ported file (Minecraft ${to}) to review:\n\`\`\`java\n${ported}\n\`\`\``;
  const r = await callClaude({ apiKey, system: REVIEW_SYSTEM, userText, effort: RUN_EFFORT });
  return r.stop === 'refusal' ? '' : r.text.trim();
}

// Recursively collect portable files, skipping build/vcs dirs and already-ported outputs.
function walkDir(dir) {
  const out = [];
  const skip = new Set(['.git', 'node_modules', 'build', 'out', '.gradle', 'bin', '.idea']);
  (function rec(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      if (e.isDirectory()) { if (!skip.has(e.name)) rec(path.join(d, e.name)); }
      else if (!e.name.includes('.ported')) out.push(path.join(d, e.name));
    }
  })(dir);
  return out;
}

// Compile one or many Java files together; returns '' on success or the javac error text.
function compileJava(files, classpath, outDir) {
  const list = Array.isArray(files) ? files : [files];
  const res = spawnSync('javac', ['-J-Xmx512m', '--release', '21', '-cp', classpath, '-d', outDir, ...list], { encoding: 'utf8' });
  if (res.error) return `could not run javac: ${res.error.message}`;
  if (res.status === 0) return '';
  return (res.stderr || res.stdout || 'javac failed with no output').trim();
}

// Real signatures from the target jars via javap — ground truth so repairs fix against reality, not memory.
function javapClass(classpath, fqcn) {
  const res = spawnSync('javap', ['-classpath', classpath, fqcn], { encoding: 'utf8' });
  return (res.status === 0 && res.stdout) ? res.stdout.trim() : null;
}

// javac requires a public top-level type to live in <TypeName>.java. Our ported files are named
// "<stem>.ported-<ver>.java", so compiling them directly triggers a phantom "class X is public,
// should be declared in a file named X.java" error the repair loop can never fix. Derive the real
// type name and compile a correctly-named copy instead.
function publicTypeName(src) {
  const m = src.match(/\bpublic\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+|strictfp\s+)*(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)/);
  if (m) return m[1];
  const any = src.match(/\b(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)/); // fall back to first type
  return any ? any[1] : null;
}

// Pull likely Minecraft/loader class names out of javac error text (drops any trailing .member).
function classesInErrors(errorText) {
  const set = new Set();
  const re = /\b((?:net\.minecraft|net\.fabricmc|com\.mojang|net\.minecraftforge|org\.spongepowered)\.[A-Za-z0-9_.$]+)/g;
  let mm;
  while ((mm = re.exec(errorText))) {
    const parts = mm[1].split('.');
    while (parts.length && !/^[A-Z]/.test(parts[parts.length - 1])) parts.pop(); // drop trailing method/field
    if (parts.length >= 3) set.add(parts.join('.'));
  }
  return [...set].slice(0, 8);
}

// Packages where javac reported a symbol NOT found (bad import / unknown class). javac prints
// "location: package net.minecraft.resources" for these — that's where a class was renamed/moved away from.
function missingPackages(errorText) {
  const set = new Set();
  const re = /location:\s*package\s+([a-zA-Z0-9_.]+)/g;
  let m;
  while ((m = re.exec(errorText))) set.add(m[1]);
  return [...set].slice(0, 6);
}

// The REAL top-level classes present in a package across the target jars. This is how the repair loop
// discovers RENAMES (e.g. ResourceLocation -> Identifier): javap can't find a class by a name that no
// longer exists, but listing what IS in the package lets the model pick the correct replacement.
function classesInPackage(classpath, pkg) {
  const pkgPath = pkg.replace(/\./g, '/');
  const rx = new RegExp(`^${pkgPath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/([A-Za-z0-9_]+)\\.class$`);
  const names = new Set();
  for (const jar of classpath.split(':')) {
    if (!jar.endsWith('.jar')) continue;
    const res = spawnSync('unzip', ['-Z1', jar, `${pkgPath}/*.class`], { encoding: 'utf8' });
    if (res.status !== 0 || !res.stdout) continue;
    for (const line of res.stdout.split('\n')) {
      const m = line.trim().match(rx); // top-level only (single segment, no '$' inner classes)
      if (m) names.add(m[1]);
    }
  }
  return [...names].sort();
}

// Ground truth fed to the repair model: real signatures of named classes (javap) PLUS, for any class
// that wasn't found in its package, the real class list of that package (to catch renames/moves).
// Cross-version member renames (mojmap⋈intermediary, authoritative). These are NOT safe to token-rewrite
// — unlike SRG names, mojmap member names like `pos` are not globally unique. But when javac names an
// exact missing symbol, looking it up is precise and safe, so they are used ONLY as targeted repair hints.
let MEMBERX = null;
function memberRenameHints(errText) {
  if (MEMBERX === null) {
    MEMBERX = new Map();
    try {
      const data = JSON.parse(fs.readFileSync(path.join(SCRIPT_DIR, 'rules.json'), 'utf8'));
      for (const [k, v] of Object.entries(data)) {
        if (!k.startsWith('membersX:')) continue;
        for (const [a, b] of Object.entries(v.members || {})) if (!MEMBERX.has(a)) MEMBERX.set(a, b);
      }
    } catch {}
  }
  if (!MEMBERX.size) return '';
  const hits = [];
  // javac reports the missing member as:  symbol: method foo(...)  |  symbol: variable bar
  for (const m of errText.matchAll(/symbol:\s+(?:method|variable)\s+([A-Za-z_$][\w$]*)/g)) {
    const to = MEMBERX.get(m[1]);
    if (to && !hits.some((h) => h.startsWith(m[1] + ' '))) hits.push(`${m[1]} -> ${to}`);
  }
  return hits.length
    ? `\nAUTHORITATIVE MEMBER RENAMES for the exact symbols javac could not find (from Mojang+intermediary mapping data — these ARE the new names):\n- ${hits.slice(0, 15).join('\n- ')}\n`
    : '';
}

function buildGroundTruth(classpath, errs) {
  let sigs = '';
  for (const c of classesInErrors(errs)) { const s = javapClass(classpath, c); if (s) sigs += `\n// ${c}\n${s}\n`; }
  let pkgIndex = '';
  for (const pkg of missingPackages(errs)) {
    const cls = classesInPackage(classpath, pkg);
    if (cls.length) pkgIndex += `\n// package ${pkg} actually contains:\n${cls.join(', ')}\n`;
  }
  let gt = '';
  if (sigs) gt += `\nREAL API of the target classes named in these errors (javap on the actual target jars — GROUND TRUTH; use these exact names/signatures, do not invent others):\n\`\`\`\n${sigs.slice(0, 12000)}\n\`\`\`\n`;
  if (pkgIndex) gt += `\nA class referenced via import/symbol was NOT FOUND in its package — it was RENAMED or MOVED in the target version. Below is the REAL list of top-level classes that exist in each affected package (from the actual target jars). Pick the correct current replacement from THIS list (e.g. an id/resource-location type may now have a different name); never re-use the missing name:\n\`\`\`\n${pkgIndex.slice(0, 6000)}\n\`\`\`\n`;
  gt += memberRenameHints(errs);   // targeted, symbol-exact member rename hints
  return gt;
}

// Compile -> on errors, feed them PLUS real javap signatures of the classes involved back to Claude -> recompile.
async function verifyAndRepair({ apiKey, javaFile, classpath, rounds }) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'foxgrade-'));
  const learned = []; // durable facts the REPAIR rounds discover — the most valuable ones (e.g. renames)
  for (let i = 0; i <= rounds; i++) {
    const src = fs.readFileSync(javaFile, 'utf8');
    // Compile a correctly-named copy so javac's file/public-type rule doesn't create a phantom error.
    const typeName = publicTypeName(src) || path.basename(javaFile).replace(/\.java$/, '').replace(/[^A-Za-z0-9_$]/g, '_');
    const compileTarget = path.join(tmp, typeName + '.java');
    fs.writeFileSync(compileTarget, src);
    let errs = compileJava(compileTarget, classpath, tmp);
    if (errs) errs = errs.split(compileTarget).join(path.basename(javaFile)); // show the real filename, not the temp path
    if (!errs) return { compiled: true, rounds: i, learned: learned.join('\n') };
    if (i === rounds) return { compiled: false, rounds: i, errors: errs, learned: learned.join('\n') };
    process.stderr.write(`  compile round ${i + 1}: errors — pulling real signatures (javap) + package inventory + asking Claude to fix...\n`);
    const groundTruth = buildGroundTruth(classpath, errs);
    const userText = `javac errors:\n\`\`\`\n${errs}\n\`\`\`\n${groundTruth}\nCurrent file:\n\`\`\`java\n${src}\n\`\`\``;
    const r = await callClaude({ apiKey, system: REPAIR_SYSTEM, userText, effort: RUN_EFFORT });
    const lrn = extractSection(r.text, 'Learned');
    if (lrn && !/\(none\)/i.test(lrn)) learned.push(lrn);
    const m = r.text.match(/```[a-z0-9]*\s*\n([\s\S]*?)\n```/);
    if (!m) return { compiled: false, rounds: i, errors: 'model returned no code block to apply', learned: learned.join('\n') };
    fs.writeFileSync(javaFile, m[1].trimEnd() + '\n');
  }
}

// Compile the WHOLE ported tree TOGETHER so cross-file references resolve (a single-file compile
// can't — it would flag every same-mod class as "cannot find symbol"). Repairs each erroring file
// with its own errors + javap ground truth, then recompiles the whole tree.
async function verifyTreeAndRepair({ apiKey, outRoot, classpath, rounds }) {
  const javaFiles = walkDir(outRoot).filter((f) => f.toLowerCase().endsWith('.java'));
  if (!javaFiles.length) return { compiled: true, rounds: 0, files: 0, calls: 0, note: 'no .java files to compile' };
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'foxgrade-tree-'));
  const learned = []; // durable facts the repair rounds discover (renames/moves) — fed back into the knowledge base
  let calls = 0; // AI calls spent repairing
  const pending = new Map(); // file -> content BEFORE the last repair, for diff-mining proven fixes
  const minedRules = [];
  // A repair is "proven" once the file it touched stops producing errors — then its diff is ground truth.
  const harvest = (errsNow) => {
    if (!pending.size) return;
    const stillBad = attributeErrors(errsNow || '', javaFiles);
    for (const [file, before] of [...pending.entries()]) {
      if (stillBad.has(file)) continue;                       // not proven yet — keep waiting
      try { minedRules.push(...extractImportRenames(before, fs.readFileSync(file, 'utf8'))); } catch {}
      pending.delete(file);
    }
  };
  for (let i = 0; i <= rounds; i++) {
    const errs = compileJava(javaFiles, classpath, tmp);
    harvest(errs);
    if (!errs) {
      const added = recordLearnedRules(TO_VERSION, minedRules);
      if (added) process.stderr.write(`  learned ${added} new verified rule(s) from the repair diff — future ports get these FREE.\n`);
      return { compiled: true, rounds: i, files: javaFiles.length, learned: learned.join('\n'), calls, minedRules: added };
    }
    if (i === rounds) {
      const added = recordLearnedRules(TO_VERSION, minedRules);
      if (added) process.stderr.write(`  learned ${added} new verified rule(s) from partial repairs.\n`);
      return { compiled: false, rounds: i, files: javaFiles.length, errors: errs, learned: learned.join('\n'), calls, minedRules: added };
    }
    process.stderr.write(`  tree compile round ${i + 1}: errors — repairing affected files (javap + package inventory grounded)...\n`);
    // Group error lines by the file they point at (only files we actually ported).
    const byFile = new Map();
    for (const line of errs.split('\n')) {
      const mm = line.match(/^(.*\.java):\d+:/);
      if (mm && javaFiles.includes(mm[1])) {
        if (!byFile.has(mm[1])) byFile.set(mm[1], []);
        byFile.get(mm[1]).push(line);
      }
    }
    if (!byFile.size) return { compiled: false, rounds: i, files: javaFiles.length, errors: errs, learned: learned.join('\n'), calls }; // can't attribute — bail honestly
    const groundTruth = buildGroundTruth(classpath, errs);
    // Repair the erroring files CONCURRENTLY — each gets its own errors + the same ground truth.
    const fixes = await mapLimit([...byFile.entries()], JOBS, async ([file, lines]) => {
      const cur = fs.readFileSync(file, 'utf8');
      const userText =
        `This file is part of a mod compiled together with its sibling files. javac errors for THIS file:\n\`\`\`\n${lines.join('\n')}\n\`\`\`\n${groundTruth}` +
        `\nCurrent file:\n\`\`\`java\n${cur}\n\`\`\``;
      try {
        const r = await callClaude({ apiKey, system: REPAIR_SYSTEM, userText, effort: RUN_EFFORT });
        return { file, text: r.text };
      } catch (e) { return { file, err: e.message }; } // one file's failure must not kill the round
    });
    for (const fx of fixes) {
      calls++;
      if (!fx || fx.err) continue;
      const lrn = extractSection(fx.text, 'Learned');
      if (lrn && !/\(none\)/i.test(lrn)) learned.push(lrn);
      const m = fx.text.match(/```[a-z0-9]*\s*\n([\s\S]*?)\n```/);
      if (m) {
        pending.set(fx.file, fs.readFileSync(fx.file, 'utf8')); // snapshot BEFORE overwriting, to diff later
        fs.writeFileSync(fx.file, m[1].trimEnd() + '\n');
      }
    }
  }
}

// Which of `files` (as javac prints them) appear in the error text — i.e. which files still don't compile.
function attributeErrors(errs, files) {
  const set = new Set();
  for (const line of (errs || '').split('\n')) {
    const m = line.match(/^(.*\.java):\d+:/);
    if (m && files.includes(m[1])) set.add(m[1]);
  }
  return set;
}

// Content-addressed cache: never re-port an identical (source, from, to) again.
const CACHE_DIR = () => path.join(SCRIPT_DIR, '.cache');
const cacheKey = (src, from, to) => crypto.createHash('sha256').update(`${src} ${from} ${to}`).digest('hex');
function cacheGet(key) { const f = path.join(CACHE_DIR(), key + '.java'); return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null; }
function cachePut(key, code) { fs.mkdirSync(CACHE_DIR(), { recursive: true }); fs.writeFileSync(path.join(CACHE_DIR(), key + '.java'), code); }

// "Only call when needed" whole-mod port: deterministic rules first, then the AI ONLY on files that
// still don't compile (with cache), then a shared repair pass. Needs a classpath (to know what compiles).
async function fastDirPort(o) {
  const { apiKey, input, from, to, notesText, mappingsText, knowledgeText, rules, classpath, repairRounds, deep, pendingFile, outRoot } = o;
  const portable = new Set(['.java', '.json', '.lang', '.mcmeta', '.properties']);
  const all = walkDir(input).filter((f) => portable.has(path.extname(f).toLowerCase()));
  const outRel = (f) => { const r = path.relative(input, f); const e = path.extname(r).toLowerCase(); return r.slice(0, r.length - e.length) + (e === '.lang' ? '.json' : e); };
  const outOf = (f) => path.join(outRoot, outRel(f));
  const rel = (f) => path.relative(input, f);
  const javaIn = all.filter((f) => f.toLowerCase().endsWith('.java'));
  const reports = [];
  let aiCalls = 0, cacheHits = 0;

  // 1) Deterministic pass (0 AI): rename rules on every .java, version-bump fabric.mod.json.
  for (const f of javaIn) { const out = outOf(f); fs.mkdirSync(path.dirname(out), { recursive: true }); fs.writeFileSync(out, applyRules(fs.readFileSync(f, 'utf8'), rules).code); }
  for (const f of all) if (path.basename(f) === 'fabric.mod.json') { const out = outOf(f); fs.mkdirSync(path.dirname(out), { recursive: true }); const b = bumpFabricModJson(fs.readFileSync(f, 'utf8'), to); fs.writeFileSync(out, b ? b.json : fs.readFileSync(f, 'utf8')); reports.push(`\n## ${rel(f)} — metadata bumped (deterministic, 0 AI)`); }

  // 2) Compile the deterministic tree; find which .java still error.
  const outJava = javaIn.map(outOf);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'foxgrade-fast-'));
  const errs = outJava.length ? compileJava(outJava, classpath, tmp) : '';
  const erroring = attributeErrors(errs, outJava);
  const clean = outJava.filter((p) => !erroring.has(p));
  process.stderr.write(`Deterministic pass: ${clean.length}/${outJava.length} .java compiled clean from rules alone — 0 AI calls for those.\n`);
  for (const p of clean) reports.push(`\n## ${path.relative(outRoot, p)} — rules only (compiles clean, 0 AI)`);

  // 3+4) AI-port ONLY the .java that still don't compile (cache first), plus assets (format conversion).
  // Run them CONCURRENTLY — files are independent, so this is pure wall-clock win with identical output.
  const needAI = javaIn.filter((f) => erroring.has(outOf(f)));
  const assets = all.filter((f) => !f.toLowerCase().endsWith('.java') && path.basename(f) !== 'fabric.mod.json');
  const work = [...needAI.map((f) => ({ f, asset: false })), ...assets.map((f) => ({ f, asset: true }))];
  if (work.length) process.stderr.write(`AI needed for ${work.length} file(s) — running ${Math.min(JOBS, work.length)} at a time.\n`);
  const results = await mapLimit(work, JOBS, async ({ f, asset }) => {
    const out = outOf(f);
    if (!asset) { // cache only applies to code ports
      const key = cacheKey(fs.readFileSync(f, 'utf8'), from, to);
      const cached = cacheGet(key);
      if (cached) { fs.writeFileSync(out, cached); return { f, cached: true, note: `\n## ${rel(f)} — from cache (0 AI)` }; }
      process.stderr.write(`  AI port: ${rel(f)}\n`);
      let res;
      try { res = await portFile({ apiKey, input: f, from, to, notesText, mappingsText, knowledgeText, outFile: out, deep, rules }); }
      catch (e) { return { f, calls: deep ? 2 : 1, note: `\n## ${rel(f)} — AI port error (${e.message})` }; }
      if (res.ok) cachePut(key, fs.readFileSync(out, 'utf8'));
      return { f, calls: deep ? 2 : 1, learned: res.ok ? res.learned : '', note: res.ok ? `\n## ${rel(f)} — AI ported\n\n${res.report}` : `\n## ${rel(f)} — AI port failed (${res.reason})` };
    }
    process.stderr.write(`  AI port (asset): ${rel(f)}\n`);
    let res;
    try { res = await portFile({ apiKey, input: f, from, to, notesText, mappingsText, knowledgeText, outFile: out, deep: false, rules }); }
    catch (e) { return { f, calls: 1, note: `\n## ${rel(f)} — asset error (${e.message})` }; }
    return { f, calls: 1, note: res.ok ? `\n## ${rel(f)} — asset ported\n\n${res.report}` : `\n## ${rel(f)} — asset failed (${res.reason})` };
  });
  // If the API died fatally (billing/auth), STOP — do not write a tree that looks ported but isn't.
  if (FATAL_ERROR) throw new Error(`aborted — no files were ported: ${FATAL_ERROR}`);
  for (const r of results) { // fold results back in input order (mapLimit preserves it)
    if (!r) continue;
    if (r.cached) cacheHits++; else aiCalls += r.calls || 0;
    if (r.learned && !/\(none\)/i.test(r.learned)) fs.appendFileSync(pendingFile, `\n<!-- ${rel(r.f)} ${from}->${to} -->\n${r.learned}\n`);
    reports.push(r.note);
  }

  // 5) Recompile the whole tree and repair remaining errors (AI only on still-erroring files).
  const tv = await verifyTreeAndRepair({ apiKey, outRoot, classpath, rounds: repairRounds });
  aiCalls += tv.calls || 0;
  if (tv.learned && !/\(none\)/i.test(tv.learned)) fs.appendFileSync(pendingFile, `\n<!-- repair-learned ${from}->${to} -->\n${tv.learned}\n`);
  const verifyNote = tv.compiled
    ? `Whole-mod compile: CLEAN — ${tv.files} file(s)${tv.rounds ? ` after ${tv.rounds} repair round(s)` : ''}.`
    : `Whole-mod compile: FAILED after ${tv.rounds} round(s).\n\n\`\`\`\n${(tv.errors || '').slice(0, 4000)}\n\`\`\``;

  // 6) Report with the "only when needed" tally.
  const wouldHave = javaIn.length + assets.length; // calls if every file went to the AI
  const head = `# Foxgrade — ${path.basename(input)}  ${from} -> ${to}\n\n` +
    `**Only-when-needed:** ${clean.length} .java by deterministic rules (0 AI) · ${cacheHits} from cache (0 AI) · ${needAI.length - cacheHits} AI-ported · ${assets.length} asset(s) AI-ported · repair calls ${tv.calls || 0}. ` +
    `**Total AI calls: ${aiCalls}** (naive would-be ~${wouldHave}${repairRounds ? '+' : ''}).\n\n## Compile verification\n\n${verifyNote}\n`;
  fs.mkdirSync(outRoot, { recursive: true });
  fs.writeFileSync(path.join(outRoot, 'PORT-REPORT.md'), head + reports.join('\n') + '\n');
  return { clean: clean.length, cacheHits, aiCalls, compiled: tv.compiled, wouldHave, outRoot };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  // Apply global knobs FIRST so every path (including --selftest) honours them.
  const foxai = args.foxai ?? args.ollama;          // --ollama kept as a quiet alias
  if (foxai) { USE_OLLAMA = true; if (typeof foxai === 'string' && foxai !== 'true') OLLAMA_MODEL = foxai;
    console.log(`--foxai: using the FREE local model ${OLLAMA_MODEL} (no API key, no usage limits)`); }
  if (args.effort) RUN_EFFORT = args.effort;              // low|medium|high|xhigh|max
  if (args.jobs) JOBS = Math.max(1, parseInt(args.jobs, 10) || 4);
  TO_VERSION = args.to || '';

  // Learning store: a verified knowledge base fed into every port, plus a pending queue of candidates.
  const knowledgeFile = args.knowledge || path.join(SCRIPT_DIR, 'knowledge.md');
  const pendingFile = path.join(SCRIPT_DIR, 'knowledge.pending.md');
  if (args.promote) {
    const pend = fs.existsSync(pendingFile) ? fs.readFileSync(pendingFile, 'utf8').trim() : '';
    if (!pend) { console.log('No pending knowledge to promote.'); return; }
    fs.appendFileSync(knowledgeFile, '\n' + pend + '\n');
    fs.writeFileSync(pendingFile, '');
    console.log(`Promoted pending knowledge -> ${knowledgeFile}`);
    return;
  }

  const apiKey = process.env.ANTHROPIC_API_KEY;
  // Prefer an API key; otherwise fall back to the Claude Code CLI (subscription) if it is installed.
  if (!apiKey && !args['rules-dry']) {
    if (args['via-cli'] !== false && claudeCliAvailable()) {
      USE_CLI = true;
      console.log('No ANTHROPIC_API_KEY — using the Claude Code CLI (your subscription) via headless mode.');
    } else {
      const ollamaUp = spawnSync('curl', ['-s', '--max-time', '2', 'http://localhost:11434/api/tags'], { encoding: 'utf8' }).status === 0;
      if (ollamaUp) { USE_OLLAMA = true; console.log('No API key — using the FREE local model via Ollama (no limits, no cost).'); }
      else {
        console.error('No AI backend available. Pick one:');
        console.error('  FREE, local, no account : ./setup-free-ai.sh      then re-run with --ollama');
        console.error('  Claude subscription     : install the Claude Code CLI');
        console.error('  API key                 : export ANTHROPIC_API_KEY=sk-ant-...');
        console.error('  Or run --rules-dry for the deterministic-only port (no AI needed at all).');
        process.exit(1);
      }
    }
  }
  if (args['via-cli'] && apiKey) { USE_CLI = true; console.log('--via-cli: using the Claude Code CLI (subscription) instead of the API key.'); }

  if (args.selftest) {
    process.stderr.write('Self-test: calling ' + MODEL + ' ... ');
    const r = await callClaude({
      apiKey,
      system: 'Reply with exactly: OK',
      userText: 'ping',
      maxTokens: 64,
      effort: 'high',
      disableThinking: true,
    });
    console.log(`\nmodel=${r.servedBy} stop=${r.stop} reply=${JSON.stringify(r.text.trim())}`);
    console.log(r.text.trim() === 'OK' ? 'SELFTEST PASS' : 'SELFTEST: got a reply (pipeline works)');
    return;
  }

  const input = args._[0];
  if (!input || !args.from || !args.to) {
    console.error('Usage: node foxgrade.mjs <file-or-directory> --from <ver> --to <ver> [--out <path>] [--notes "..."] [--mappings <file>]\n' +
      '                    [--deep] [--review] [--verify --classpath <cp> [--repair <N>]]\n' +
      '  --deep    plan the migration before writing code, then port (higher quality; also enables --review)\n' +
      '  --review  add a semantic second-opinion pass (catches compiles-but-wrong: hallucinated APIs, behavior drift)\n' +
      '  --verify  compile the port against the real target jars and repair errors (whole tree together in directory mode)\n' +
      '            with a directory this also enables ONLY-WHEN-NEEDED mode: files the rules already fix cost 0 AI calls\n' +
      '  --jobs N  port N files concurrently (default 4). Pure speedup — identical output, just not one-at-a-time\n' +
      '  --effort  xhigh (default, best) | high | medium | low\n' +
      '  --rules-dry  apply the deterministic ruleset only — 0 AI calls, no API key needed');
    process.exit(1);
  }

  const deep = !!args.deep;
  const wantReview = !!(args.review || args.deep); // deep mode implies a review pass

  // Shared context blocks — used for every file in single- or directory-mode.
  const notesText = args.notes ? `\nExtra context provided by the user:\n${args.notes}\n` : '';
  let mappingsText = '';
  if (args.mappings) {
    mappingsText = `\nMAPPINGS (authoritative — use to resolve obfuscated/Searge names for the SOURCE version ${args.from}):\n\`\`\`\n${fs.readFileSync(args.mappings, 'utf8')}\n\`\`\`\n`;
  }
  let knowledgeText = '';
  if (fs.existsSync(knowledgeFile)) {
    const k = fs.readFileSync(knowledgeFile, 'utf8').trim();
    if (k) knowledgeText = `\nVERIFIED KNOWLEDGE from past ports (reliable — prefer these over guessing):\n${k}\n`;
  }
  const portable = new Set(['.java', '.json', '.lang', '.mcmeta', '.properties']);

  // Executable migration ruleset for the target version (rule #1).
  const _samples = [];
  try {
    const st = fs.existsSync(input) && fs.statSync(input).isDirectory();
    const files = st ? walkDir(input).filter((f) => f.endsWith('.java')).slice(0, 40) : [input].filter((f) => f.endsWith('.java'));
    for (const f of files) _samples.push(fs.readFileSync(f, 'utf8'));
  } catch {}
  TARGET_CLASSES = indexTargetClasses(args.classpath);
  const rules = loadRules(args.to, args.from, _samples);
  if (rules.schemeInfo) console.log(`scheme detected: ${rules.schemeInfo.scheme}@${rules.schemeInfo.ver} (${rules.schemeInfo.hits} import hits) — ${rules.renames.length} rules active`);

  // ---------- Deterministic-only mode: apply verified renames, NO AI call (free + instant) ----------
  if (args['rules-dry']) {
    const isDir = fs.existsSync(input) && fs.statSync(input).isDirectory();
    const targets = isDir
      ? walkDir(input).filter((f) => f.toLowerCase().endsWith('.java') || path.basename(f) === 'fabric.mod.json')
      : [input];
    let total = 0;
    for (const f of targets) {
      const isMeta = path.basename(f) === 'fabric.mod.json';
      let outContent, appliedList;
      if (isMeta) { const b = bumpFabricModJson(fs.readFileSync(f, 'utf8'), args.to); outContent = b ? b.json : fs.readFileSync(f, 'utf8'); appliedList = b ? b.notes : ['(could not parse)']; }
      else { const ar = applyRules(fs.readFileSync(f, 'utf8'), rules); outContent = ar.code; appliedList = ar.applied; }
      const e = path.extname(f);
      const out = isDir
        ? path.join(args.out || `${input.replace(/[/\\]+$/, '')}.rules`, path.relative(input, f))
        : (args.out || `${f.slice(0, f.length - e.length)}.rules${e}`);
      fs.mkdirSync(path.dirname(out), { recursive: true });
      fs.writeFileSync(out, outContent);
      console.log(`${path.relative(process.cwd(), f)}: ${appliedList.length} change(s)${isMeta ? ' (metadata)' : ''} -> ${path.relative(process.cwd(), out)}`);
      for (const a of appliedList) console.log('   - ' + a);
      total += appliedList.length;
    }
    console.log(`\nrules-dry: applied ${total} deterministic change(s) with 0 AI calls (rules.json target ${args.to}).`);
    return;
  }

  // ---------- Directory (whole-mod) mode ----------
  if (fs.existsSync(input) && fs.statSync(input).isDirectory()) {
    const files = walkDir(input).filter((f) => portable.has(path.extname(f).toLowerCase()));
    if (!files.length) { console.error('No portable files (.java/.json/.lang/.mcmeta/.properties) under ' + input); process.exit(1); }
    const outRoot = args.out || `${input.replace(/[/\\]+$/, '')}.ported`;

    // "Only call when needed": with a classpath we can tell which files already compile after the
    // deterministic rules and skip the AI for them entirely. This is the low-AI-cost path.
    if (args.verify && args.classpath) {
      const repairRounds = parseInt(args.repair || '2', 10);
      console.log(`Whole-mod port (only-when-needed): ${files.length} files  ${args.from} -> ${args.to}  ->  ${outRoot}`);
      const r = await fastDirPort({ apiKey, input, from: args.from, to: args.to, notesText, mappingsText, knowledgeText, rules, classpath: args.classpath, repairRounds, deep, pendingFile, outRoot });
      console.log(`\nAI calls: ${r.aiCalls} total  (${r.clean} files by rules + ${r.cacheHits} cached = 0 AI; naive would be ~${r.wouldHave}).`);
      console.log(`${r.compiled ? 'compiles CLEAN against the real jars' : 'still has compile errors (see report)'}\ntree   -> ${outRoot}\nreport -> ${path.join(outRoot, 'PORT-REPORT.md')}`);
      if (fs.existsSync(pendingFile) && fs.readFileSync(pendingFile, 'utf8').trim()) console.log('learned candidates queued -> node foxgrade.mjs --promote');
      return;
    }

    console.log(`Whole-mod port: ${files.length} files  ${args.from} -> ${args.to}${deep ? '  (deep: plan+port)' : ''}  ->  ${outRoot}`);
    const reports = [];
    const portedPairs = []; // {original, ported, rel} for the review pass
    const planSections = []; // deep-mode migration plans
    let done = 0, failed = 0;
    // Port files CONCURRENTLY (JOBS at a time) — independent work, identical output, far less wall-clock.
    let doneCount = 0;
    const fileResults = await mapLimit(files, JOBS, async (f) => {
      const relPath = path.relative(input, f);
      const rext = path.extname(relPath).toLowerCase();
      const relOut = relPath.slice(0, relPath.length - rext.length) + (rext === '.lang' ? '.json' : rext);
      // Rule #5: bump loader metadata deterministically so the ported mod actually loads (no AI needed).
      if (path.basename(f) === 'fabric.mod.json') {
        const b = bumpFabricModJson(fs.readFileSync(f, 'utf8'), args.to);
        const outMeta = path.join(outRoot, relOut);
        fs.mkdirSync(path.dirname(outMeta), { recursive: true });
        if (b) fs.writeFileSync(outMeta, b.json);
        process.stderr.write(`[${++doneCount}/${files.length}] ${relPath} (metadata)\n`);
        return b
          ? { ok: true, rel: relPath, note: `\n## ${relPath} (metadata bumped deterministically)\n\n- ${b.notes.join('\n- ')}\n- verify the exact version predicate for your loader.` }
          : { ok: false, rel: relPath, note: `\n## ${relPath}\n\n(could not parse fabric.mod.json)` };
      }
      let res;
      try {
        res = await portFile({ apiKey, input: f, from: args.from, to: args.to, notesText, mappingsText, knowledgeText, outFile: path.join(outRoot, relOut), deep, rules });
      } catch (e) {
        res = { ok: false, reason: `error: ${e.message}` }; // isolate: one bad file must not abort the whole mod
      }
      process.stderr.write(`[${++doneCount}/${files.length}] ${relPath}\n`);
      return res.ok
        ? { ok: true, rel: relPath, relOut, orig: f, plan: res.plan, learned: res.learned, note: `\n## ${relPath}  ->  ${relOut}\n\n${res.report}` }
        : { ok: false, rel: relPath, note: `\n## ${relPath}\n\n(port failed: ${res.reason})` };
    });
    for (const r of fileResults) { // fold back in original order
      if (!r) { failed++; continue; }
      if (r.ok) {
        done++;
        reports.push(r.note);
        if (r.relOut && path.extname(r.relOut).toLowerCase() === '.java') portedPairs.push({ original: r.orig, ported: path.join(outRoot, r.relOut), rel: r.rel });
        if (r.plan) planSections.push(`\n### ${r.rel}\n\n${r.plan}`);
        if (r.learned && !/\(none\)/i.test(r.learned)) fs.appendFileSync(pendingFile, `\n<!-- ${r.rel}  ${args.from} -> ${args.to} -->\n${r.learned}\n`);
      } else { failed++; reports.push(r.note); }
    }
    // Whole-mod compile verification: compile the ENTIRE ported tree together so cross-file refs resolve.
    let verifySection = '';
    if (args.verify) {
      if (!args.classpath) console.log('\n--verify needs --classpath <target jars>. Skipped tree compile.');
      else if (done > 0) {
        const rounds = parseInt(args.repair || '2', 10);
        process.stderr.write(`\nVerifying the whole ported tree with javac (up to ${rounds} repair round(s))...\n`);
        const tv = await verifyTreeAndRepair({ apiKey, outRoot, classpath: args.classpath, rounds });
        if (tv.compiled) {
          const msg = `Whole-mod compile: CLEAN — ${tv.files} .java file(s) compile together${tv.rounds ? ` after ${tv.rounds} repair round(s)` : ''}.`;
          console.log(msg); verifySection = `\n## Compile verification\n\n${msg}\n`;
        } else {
          const msg = `Whole-mod compile: FAILED after ${tv.rounds} round(s) (${tv.files} file(s)).`;
          console.log(msg); verifySection = `\n## Compile verification\n\n${msg}\n\n\`\`\`\n${(tv.errors || '').slice(0, 6000)}\n\`\`\`\n`;
        }
        if (tv.learned && !/\(none\)/i.test(tv.learned)) {
          fs.appendFileSync(pendingFile, `\n<!-- repair-learned (verified against real ${args.to} jars): ${path.basename(input)}  ${args.from} -> ${args.to} -->\n${tv.learned}\n`);
          console.log(`repair learnings -> queued to ${path.basename(pendingFile)} (javac-verified)`);
        }
      }
    }
    // Semantic review pass: each ported Java file reviewed against its original for compiles-but-wrong issues.
    let reviewSection = '';
    if (wantReview && portedPairs.length) {
      process.stderr.write(`\nReviewing ${portedPairs.length} ported Java file(s) for compiles-but-wrong issues...\n`);
      const reviews = await mapLimit(portedPairs, JOBS, async (p) => { // reviews are independent — run concurrently
        try { return { rel: p.rel, rv: await reviewPort({ apiKey, originalFile: p.original, portedFile: p.ported, from: args.from, to: args.to }) }; }
        catch (e) { return { rel: p.rel, rv: `(review failed: ${e.message})` }; }
      });
      for (const r of reviews) if (r && r.rv) reviewSection += `\n### ${r.rel}\n\n${r.rv}\n`;
      if (reviewSection) reviewSection = `\n## Semantic review\n${reviewSection}`;
    }
    const planAppendix = planSections.length ? `\n## Migration plans (deep mode)\n${planSections.join('\n')}\n` : '';

    fs.mkdirSync(outRoot, { recursive: true });
    fs.writeFileSync(path.join(outRoot, 'PORT-REPORT.md'),
      `# foxgrade — ${path.basename(input)}  ${args.from} -> ${args.to}\n\nPorted ${done}/${files.length} files${failed ? `, ${failed} failed` : ''}.\n${verifySection}${reviewSection}${reports.join('\n')}${planAppendix}\n`);
    console.log(`\nDone: ${done}/${files.length} ported${failed ? `, ${failed} failed` : ''}.\ntree   -> ${outRoot}\nreport -> ${path.join(outRoot, 'PORT-REPORT.md')}`);
    if (fs.existsSync(pendingFile) && fs.readFileSync(pendingFile, 'utf8').trim())
      console.log('learned candidates queued -> node foxgrade.mjs --promote to keep them');
    return;
  }

  // ---------- Single-file mode ----------
  const ext = path.extname(input).toLowerCase();
  const isJava = ext === '.java';
  if (!portable.has(ext)) {
    console.error(`Unsupported file type "${ext}". Handles .java (code) and .json/.lang/.mcmeta/.properties (assets). PNG textures are binary — copy them to the new path the report gives.`);
    process.exit(1);
  }
  const stem = input.slice(0, input.length - ext.length);
  const outFile = args.out || (isJava ? `${stem}.ported-${args.to}.java` : `${stem}.ported${ext === '.lang' ? '.json' : ext}`);
  const reportFile = `${stem}.ported.report.md`;

  process.stderr.write(`Porting ${path.basename(input)} (${isJava ? 'code' : 'asset'})  ${args.from} -> ${args.to}  (${MODEL}, effort xhigh)\n`);
  const res = await portFile({ apiKey, input, from: args.from, to: args.to, notesText, mappingsText, knowledgeText, outFile, deep, rules });
  if (!res.ok) {
    if (res.reason === 'refusal') { console.error('Model declined this request (safety). Nothing written.'); process.exit(2); }
    if (res.raw) fs.writeFileSync(`${stem}.ported.raw.txt`, res.raw);
    console.error(`Port failed (${res.reason}).${res.raw ? ` Raw saved -> ${stem}.ported.raw.txt` : ''}`);
    process.exit(1);
  }
  console.log(`\nported -> ${outFile}\nserved by ${res.servedBy} (stop=${res.stop})`);
  if (res.learned && !/\(none\)/i.test(res.learned)) {
    fs.appendFileSync(pendingFile, `\n<!-- ${path.basename(input)}  ${args.from} -> ${args.to} -->\n${res.learned}\n`);
    console.log(`learned -> candidates appended to ${path.basename(pendingFile)}  (review, then: node foxgrade.mjs --promote)`);
  }

  // ---------- Auto-compile -> feed-errors-back repair loop (Java only) — runs BEFORE review ----------
  let verifyNote = '';
  if (args.verify && isJava) {
    if (!args.classpath) {
      console.log('\n--verify needs --classpath <target-version jars> to compile against. Skipped.');
    } else {
      const rounds = parseInt(args.repair || '2', 10);
      process.stderr.write(`\nVerifying with javac (up to ${rounds} repair round(s))...\n`);
      const vr = await verifyAndRepair({ apiKey, javaFile: outFile, classpath: args.classpath, rounds });
      if (vr.compiled) { const m = `Compile: CLEAN${vr.rounds ? ` after ${vr.rounds} repair round(s)` : ''}.`; console.log(`${m} — ${outFile} updated in place.`); verifyNote = `\n## Compile verification\n\n${m}\n`; }
      else { const m = `Compile: FAILED after ${vr.rounds} round(s).`; console.log(`${m} Remaining errors:\n${vr.errors}`); verifyNote = `\n## Compile verification\n\n${m}\n\n\`\`\`\n${(vr.errors || '').slice(0, 6000)}\n\`\`\`\n`; }
      if (vr.learned && !/\(none\)/i.test(vr.learned)) {
        fs.appendFileSync(pendingFile, `\n<!-- repair-learned (verified against real ${args.to} jars): ${path.basename(input)}  ${args.from} -> ${args.to} -->\n${vr.learned}\n`);
        console.log(`repair learnings -> queued to ${path.basename(pendingFile)} (these are javac-verified — the most reliable kind)`);
      }
    }
  }

  // ---------- Semantic review pass (Java only): catch compiles-but-wrong ----------
  let reviewNote = '';
  if (wantReview && isJava) {
    process.stderr.write('\nReviewing the port for compiles-but-wrong issues (hallucinated APIs, behavior drift)...\n');
    try {
      const rv = await reviewPort({ apiKey, originalFile: input, portedFile: outFile, from: args.from, to: args.to });
      if (rv) { reviewNote = `\n## Semantic review\n\n${rv}\n`; const verdict = (rv.match(/##\s*Review verdict\s*\n+\s*([^\n]+)/i) || [])[1]; console.log(`review -> ${verdict ? verdict.trim() : 'done (see report)'}`); }
    } catch (e) { console.log(`review failed: ${e.message}`); }
  }

  const planNote = res.plan ? `\n## Migration plan (deep mode)\n\n${res.plan}\n` : '';
  fs.writeFileSync(reportFile, `${res.report}\n${verifyNote}${reviewNote}${planNote}`);
  console.log(`report -> ${reportFile}`);
  console.log('\nAI draft — read "Needs human verification"' + (reviewNote ? ' + the "Semantic review" section' : '') + ' (and for assets, the exact target paths) before trusting it.');
}

main().catch((e) => {
  if (FATAL_ERROR || FATAL.test(e.message || '')) {
    console.error('\n*** FOXGRADE ABORTED — nothing was ported ***');
    console.error(e.message.replace(/^HTTP \d+: /, ''));
    console.error('Any files already written are from the deterministic rules only, NOT an AI port.\n');
    process.exit(3);
  }
  console.error('Error:', e.message);
  process.exit(1);
});
