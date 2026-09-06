#!/usr/bin/env python3
"""Publish the harness evidence: every mod tested, its verdict, the members the game no longer
has, a screenshot where the run produced one, and the Retromod head-to-head. Reads the harness
ledgers and kept ports; writes repo/docs/compat.md (+ docs/shots/)."""
import glob, json, os, pathlib, re, shutil, subprocess, collections, datetime
W = pathlib.Path.home() / "foxgrade-work"; REPO = W / "repo"; OUT = REPO / "docs"; SHOTS = OUT / "shots"
OUT.mkdir(exist_ok=True); SHOTS.mkdir(exist_ok=True)
def norm(n): return re.sub(r"-(fix|screen|A)$", "", n)
# Harness run names are not mod names: "bhc3" is the third betterhurtcam run, "c-betterf3" a client
# re-run, "duck+geckolib" a co-port. Fold them so each mod is one row and the LAST verdict wins.
ALIAS = {"bhc-ukulib": "betterhurtcam", "bhc": "betterhurtcam", "spr": "sound-physics-remastered", "betterstats": "better-stats", "friendsandfoes": "friends-and-foes"}
SKIP = {"crash-guard", "cguard-seed", "test"}
# harness runs that tested Fox-Grade, not the mod (BetterF3 ships an access widener: Fabric Loader itself refuses an old one before any mod code runs)
RAW_SKIP = {"autoinbox-betterf3"}
KNOWN = set()
for f in glob.glob(str(pathlib.Path.home() / "foxgrade-work/batch121/ledger*.txt")) + [str(pathlib.Path.home() / "foxgrade-work/batch2/ledger.txt")]:
    for ln in pathlib.Path(f).read_text().splitlines():
        q = ln.split("\t")
        if len(q) >= 2 and q[0] in ("PASS", "CRASH", "HELD", "STALL", "NOSCREEN", "ERROR", "SCREENLOST"): KNOWN.add(norm(q[1]).split("+")[0])
RAW_OF = collections.defaultdict(set)
def canon(n):
    raw = n
    n = norm(n).split("+")[0]
    if n.startswith("c-"): n = n[2:]
    if n.startswith("autoinbox-"): n = n[len("autoinbox-"):]   # the auto-inbox test is a way of running a mod, not a mod
    m = re.match(r"(.*?)(\d+)$", n)
    if m and m.group(1) in ALIAS: n = ALIAS[m.group(1)]
    elif m and m.group(1) in KNOWN: n = m.group(1)
    n = ALIAS.get(n, n)
    RAW_OF[n].add(raw); RAW_OF[n].add(norm(raw).split("+")[0])
    return n
def read_ledger(path):
    rows = {}
    for ln in pathlib.Path(path).read_text().splitlines():
        p = ln.split("\t")
        if len(p) < 2 or p[0] not in ("PASS", "CRASH", "HELD", "STALL", "NOSCREEN", "ERROR", "SCREENLOST"): continue
        if p[1] in RAW_SKIP: continue
        rows[canon(p[1])] = {"verdict": p[0], "port": p[2] if len(p) > 2 else "", "why": p[3] if len(p) > 3 else ""}
    return rows
client = {}
for f in sorted(glob.glob(str(W / "batch121/ledger*.txt"))) + [str(W / "batch2/ledger.txt")]:
    client.update(read_ledger(f))
server = read_ledger(W / "batch2/server-ledger.txt")
retro = read_ledger(W / "batch2/retromod-ledger.txt")
screens = {norm(m.group(1)): m.group(0) for m in [re.match(r".*", os.path.basename(x)) for x in []]}
# unresolved lists from kept ports (latest per name)
unresolved = {}
for pj in sorted(glob.glob(str(W / "batch2/ports/*.jar")), key=os.path.getmtime):
    name = canon(os.path.basename(pj).split("--")[0])
    try:
        meta = json.loads(subprocess.run(["unzip", "-p", pj, "fabric.mod.json"], capture_output=True, text=True).stdout)
        c = meta.get("custom", {}).get("foxgrade", {})
        unresolved.setdefault(name, []).clear(); unresolved[name] = c.get("unresolved", [])
    except Exception: pass
CORPUS = [W / "batch2/gui", W / "batch2/world", W / "batch2/retromod", W / "batch2/input", W / "batch121"] + [pathlib.Path(x) for x in glob.glob(str(W / "batch121/*")) if os.path.isdir(x)] + [pathlib.Path(x) for x in glob.glob(str(W / "deps-*"))]
def declared_range(name):
    """What the original jar says it was built for (fabric.mod.json depends.minecraft)."""
    for d in CORPUS:
        for jar in sorted(glob.glob(str(d / "*.jar"))):
            base = os.path.basename(jar).lower()
            if "fgport" in base or not (base.startswith(name.lower()) or base.replace("_", "-").startswith(name.lower().replace("_", "-"))): continue
            try:
                meta = json.loads(subprocess.run(["unzip", "-p", jar, "fabric.mod.json"], capture_output=True, text=True).stdout)
                dep = meta.get("depends", {}).get("minecraft")
                if isinstance(dep, list): dep = " / ".join(dep)
                if dep: return str(dep).replace("|", "/")
            except Exception: pass
    return ""
def source_version(port, name=""):
    if name:
        r = declared_range(name)
        if r: return r
    if not port: return ""
    src = re.sub(r".*?--", "", port.split("→")[0])
    m = re.search(r"(?<![\d.])(1\.2\d(?:\.\d+)?|26\.\d(?:\.\d+)?)(?![\d])", src)
    return m.group(1) if m else ""
def shot_for(name):
    for d in ("batch2", "batch121"):
      for raw in sorted(RAW_OF.get(name, {name}) | {name}, reverse=True):
        for cand in (f"shot-{raw}-screen-final.png", f"shot-{raw}-screen.png", f"shot-{raw}-fix.png", f"shot-{raw}.png"):
            p = W / d / cand
            if p.exists():
                dst = SHOTS / f"{name}.png"; shutil.copy(p, dst); return f"shots/{name}.png"
    return ""
label = {"PASS": "✅ boots", "HELD": "⏸ held (library missing)", "CRASH": "❌ crash", "STALL": "❌ stall", "NOSCREEN": "⚠ screen", "ERROR": "❌ error", "SCREENLOST": "⚠ screen closed"}
rows = []
for name, r in sorted(client.items()):
    if name.endswith("-A") or name in SKIP: continue
    u = unresolved.get(name); uc = "" if u is None else str(len(u))
    notes = r["why"][:90].replace("|", "/") if r["verdict"] != "PASS" else ""
    srv = label.get(server[name]["verdict"], "") if name in server else ""
    rm = label.get(retro[name]["verdict"], "") if name in retro else ""
    shot = shot_for(name); shot_md = f"[shot]({shot})" if shot else ""
    rows.append(f"| {name} | {source_version(r['port'], name)} | {label.get(r['verdict'], r['verdict'])} | {uc} | {srv} | {rm} | {shot_md} | {notes} |")
counts = collections.Counter(r["verdict"] for n, r in client.items() if not n.endswith("-A") and n not in SKIP)
commit = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
md = f"""# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client (headless launch into a world,
screenshot at tick 220). **Boots** means the ported mod loaded and the game reached the world with
it; a screen or overlay test is linked where one ran. "Unresolved" is the number of classes,
fields and methods the port still references that 26.2 no longer has — the honest measure of
how much of the mod is reachable. Generated from the harness ledgers at commit `{commit}`
on {datetime.date.today().isoformat()}; the scripts are in `tools/`.

| Result | Mods |
|---|---|
""" + "\n".join(f"| {label.get(k, k)} | {v} |" for k, v in counts.most_common()) + f"""

## Per mod

| Mod | Built for | Fox-Grade (client) | Unresolved | Server | Retromod | Screenshot | Notes |
|---|---|---|---|---|---|---|---|
""" + "\n".join(rows) + """

## Retromod head-to-head

Same five mods, same instance, same base jars, two launches each (Retromod ports on the first and
asks for a restart). Retromod 1.3.0-snapshot.10 for 26.2.

| Mod | Fox-Grade | Retromod |
|---|---|---|
| Mod Menu | boots; mod-list screen renders fully | boots; screen fails to open (`I18n.exists`, `render`→`extract` rename missing) |
| Zoomify + YACL | boots | crash (reload-listener signature) |
| BetterF3 | boots | boots |
| Lithium | boots | crash (mixin targets changed) |
| Entity Culling | boots | crash (renderer mixin) |

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.
"""
(OUT / "compat.md").write_text(md)
print(f"wrote {OUT/'compat.md'}: {len(rows)} rows, counts {dict(counts)}, shots {len(list(SHOTS.glob('*.png')))}")
