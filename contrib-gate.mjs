// The single decision about whether a contributed rule is trustworthy.
//
// This lives in one file because two copies of it drifted apart twice: verify-contrib was rejecting
// correct cross-version rules that merge-contrib accepted, and accepting self-maps and prototype
// -pollution block names that merge-contrib rejected. A gate that disagrees with itself is not a gate.
// CI and the maintainer's merge now ask exactly the same question.

export const ALLOWED_PROOFS = ['repair-diff', 'direct-jar-resolution', 'bridge', 'official-mappings', 'human-groundtruth', 'rendering-audit'];
const FQCN = /^[a-z][\w.]*\.[A-Z]\w*$/;
const RESERVED = new Set(['__proto__', 'constructor', 'prototype']);

// Which version does a block produce names FOR? "Does this class exist" can only be asked of that
// version's jar — checking a 1.15.2 rule against a 26.2 jar rejects perfectly correct rules.
export function blockTarget(name) {
  if (/^[\d.]+$/.test(name)) return name;
  let m = name.match(/^([\d.]+)->([\d.]+)$/); if (m) return m[2];
  m = name.match(/^scheme:\w+@([\d.]+)->mojmap$/); if (m) return m[1];
  return null;
}

// A rule in a block the porter cannot route is dead weight, and inventing arbitrary top-level keys is
// how junk accumulated before. A NEW block must look like one of the kinds loadRules() actually reads.
export function blockStatus(name, data) {
  if (!name || RESERVED.has(name) || name.startsWith('_') || !/^[A-Za-z0-9][\w.:@>-]*$/.test(name)) return null;
  if (Object.prototype.hasOwnProperty.call(data, name)) return 'existing';
  if (/^[\d.]+$/.test(name)) return 'new target';
  if (/^[\d.]+->[\d.]+$/.test(name)) return 'new ladder';
  if (/^scheme:\w+@[\d.]+->mojmap$/.test(name)) return 'new scheme';
  if (/^members:/.test(name)) return 'new members';
  return null;
}

export function indexRules(data) {
  const banned = new Map();        // anti-facts: old class with no equivalent
  const authoritative = new Map(); // first official-mappings answer seen anywhere, with its block
  const authInBlock = new Map();   // block -> authoritative answers for THAT span
  const perBlock = new Map();      // block -> every verified answer for that span
  for (const [k, v] of Object.entries(data)) {
    if (k.startsWith('_')) continue;
    for (const d of (v.deleted || [])) if (d.fqcn && !banned.has(d.fqcn)) banned.set(d.fqcn, k);
    const m = new Map(), a = new Map();
    for (const r of (v.renames || [])) {
      if (r.verified !== false) m.set(r.fromFqcn, r.toFqcn);
      if (r.source === 'official-mappings') {
        if (!a.has(r.fromFqcn)) a.set(r.fromFqcn, r.toFqcn);
        if (!authoritative.has(r.fromFqcn)) authoritative.set(r.fromFqcn, { to: r.toFqcn, block: k });
      }
    }
    perBlock.set(k, m); authInBlock.set(k, a);
  }
  return { banned, authoritative, authInBlock, perBlock };
}

// Two authoritative answers for one old class are usually two different VERSIONS' answers, not a
// contradiction: LocationPredicate really does live in `critereon` in one release and `predicates` in
// another. What separates that from a genuine contradiction is whether the simple name survives — a
// package move keeps it, a rename does not. So tolerate a cross-block disagreement only when both
// sides are pure moves of the same class.
//   allowed  : criterion.LocationPredicate -> {critereon,predicates}.LocationPredicate
//   rejected : world.World -> DataComponents          (authoritative says Level; neither preserves)
//   rejected : templatesystem.StructureManager -> level.StructureManager
//              (authoritative renamed it to StructureTemplateManager; the same-named class is the trap)
function reconcilable(from, mine, theirs) {
  const s = from.split('.').pop();
  return mine.split('.').pop() === s && theirs.split('.').pop() === s;
}

// entries: [{from, to, proof, block, why?, file?}]
// real: Set of class names in the jars for `jarVersion`, or null if no jars were supplied.
// Returns { accept, already, reject, held } — `held` means "could not be checked", never "fine".
export function judge(entries, { data, index, real = null, jarVersion = null }) {
  const { banned, authoritative, authInBlock, perBlock } = index;

  // Two entries in one batch that disagree about the same class are BOTH suspect: at most one can be
  // right and there is no way to tell which, so neither lands.
  const votes = new Map();
  for (const e of entries) {
    const k = `${e.block}|${e.from}`;
    if (!votes.has(k)) votes.set(k, new Set());
    votes.get(k).add(e.to);
  }

  const accept = [], already = [], reject = [], held = [];
  const taken = new Set();
  for (const e of entries) {
    const { from, to, proof, block } = e;
    const R = (why) => reject.push([from, to, why, e.file]);
    if (!from || !to || !FQCN.test(from) || !FQCN.test(to)) { R('malformed class name'); continue; }
    if (from === to) { R('maps a class to itself'); continue; }
    if (!ALLOWED_PROOFS.includes(proof)) { R(`unaccepted proof "${proof}"`); continue; }
    const bs = blockStatus(block, data);
    if (!bs) { R(`block "${block}" is not one the porter reads`); continue; }
    if (banned.has(from)) { R(`source is a known anti-fact (deleted in ${banned.get(from)})`); continue; }
    if (banned.has(to)) { R(`target is a known anti-fact (deleted in ${banned.get(to)})`); continue; }

    if (real && blockTarget(block) === jarVersion) {
      if (!real.has(to)) { R(`class not in the ${jarVersion} jars`); continue; }
    } else { held.push([from, to, block, e.file]); continue; }

    // within one span there can be only one right answer, so any disagreement there is fatal
    const sameSpan = authInBlock.get(block)?.get(from);
    if (sameSpan && sameSpan !== to) { R(`this block authoritatively maps it to ${sameSpan}`); continue; }
    const auth = authoritative.get(from);
    if (!sameSpan && auth && auth.to !== to && !reconcilable(from, auth.to, to)) {
      R(`contradicts authoritative ${auth.to} (${auth.block})`); continue;
    }
    if (votes.get(`${block}|${from}`).size > 1) { R('batch disagrees with itself about this class'); continue; }
    const here = perBlock.get(block);
    if (here && here.has(from)) {
      if (here.get(from) === to) { already.push([from, to]); continue; }
      R(`block already maps it to ${here.get(from)}`); continue;
    }
    const k = `${block}|${from}`;
    if (taken.has(k)) { already.push([from, to]); continue; }   // same pair arriving from several files
    taken.add(k);
    accept.push({ ...e, blockStatus: bs });
  }
  return { accept, already, reject, held };
}

// Index every class that really exists in the given jars. Ground truth beats any table.
export function indexJars(classpath, spawnSync, fs) {
  if (!classpath) return null;
  const set = new Set();
  for (const jar of classpath.split(':')) {
    if (!jar.endsWith('.jar') || !fs.existsSync(jar)) continue;
    const r = spawnSync('unzip', ['-Z1', jar, '*.class'], { encoding: 'utf8', maxBuffer: 64e6 });
    if (r.status !== 0 || !r.stdout) continue;
    for (const l of r.stdout.split('\n')) {
      const p = l.trim();
      if (p.endsWith('.class') && !p.includes('$')) set.add(p.slice(0, -6).replace(/\//g, '.'));
    }
  }
  return set.size ? set : null;
}
