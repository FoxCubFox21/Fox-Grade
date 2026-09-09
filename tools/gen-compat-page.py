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
# harness limitations worth stating instead of an empty reason
NOTES = {"rei": "needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the dependency before either tool runs",
  # out of scope for a bytecode port (the 26.2 subsystem the mod is built on no longer exists), stated as such rather than "crashes"
  "cobblemon": "251 unresolved references across 33 removed classes; a rewrite, not a port",
  "physicsmod": "renderer-tier: its own render pipeline reads shader resources 26.2 no longer ships",
  "entity-model-features": "hooks every vanilla entity model's mesh builder (WolfModel.createMeshDefinition and friends), rebuilt in 26.2",
  "entitytexturefeatures": "a @Shadow method whose static modifier no longer matches the 26.2 target; boots intermittently",
  "particle-rain": "declares its own particle render type, which 26.2 sealed",
  "supplementaries": "moonlight's registry supplier implements Holder, which 26.2 sealed",
  "amendments": "moonlight's registry supplier implements Holder, which 26.2 sealed",
  "biomes-o-plenty": "passes registry keys where 26.2's villager types expect the type record; worldgen data changed shape",
  "enhancedvisuals": "builds a post-processing chain whose constructor 26.2 replaced",
  "toms-storage": "draws through Gui.layers (LayeredDraw), removed in 26.2's HUD rewrite",
  "drippy-loading-screen": "FancyMenu family: blaze3d RenderCall and the GUI framework are gone",
  "fancymenu": "gets past the removed render-call queue and the pack-metadata change, then stops in the post-processing chain",
  "ebe": "needs Fabric's FabricBakedModelManager, removed with the model-loading rewrite",
  "sodium-shadowy-path-blocks": "needs Sodium, and Sodium itself cannot be ported: its particle mixin targets a class hierarchy 26.2 rewrote",
  "sodium-options-api": "no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal",
  "sodium-dynamic-lights": "no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal",
  "dungeons-and-taverns": "its loot conditions use the 1.21 time_check shape without the clock key 26.2 requires",
  "comforts": "its SleepStatus mixin captures the server from a hook that no longer fires, so the field stays null",
  "travelersbackpack": "Cardinal Components attaches its containers from constructor injections whose parameter lists changed in 26.2; the handlers are stripped and the container is never created (run with Cardinal Components and Cloth Config present)",
  "trinkets": "Cardinal Components attaches its containers from constructor injections whose parameter lists changed in 26.2; the handlers are stripped and the container is never created",
  "handcrafted": "model baking fails on its block models (translucency out of bounds) and the world never loads",
  "dynamiccrosshair": "reads private Inventory.selected; widened in 1.1.0",
  "lootr": "needs Fabric's BuiltinItemRendererRegistry (custom item renderers), removed with the 26.2 rendering rewrite",
  "chipped": "the game never starts loading the world with it (silent stall after registration)",
  "visual-workbench": "puzzleslib's sound mixin captures a local that 26.2 no longer has at that point (same as cut-through); boots intermittently",
  "sound": "resolves an item tag during construction, which 26.2 forbids",
  "cut-through": "puzzleslib's sound mixin captures a local that 26.2 no longer has at that point",
  "lambdynamiclights": "its LevelRenderer duck interface mixin cannot apply; renderer-tier",
  "xaeros-minimap": "draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier",
  "xaeros-world-map": "draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier",
  "towns-and-towers": "touches Fabric's internal ModNioResourcePack, which the 26.2 resource loader renamed",
  "enchantment-descriptions": "bookshelf's client registry helper hits a null registry entry at init",
  "modernfix": "texture-stitcher internals (Stitcher.SpriteLoader) changed shape in 26.2; renderer-tier",
  "yungs-better-nether-fortresses": "YUNG's structure processor types still reach 26.2's codec dispatch as 1.21 lambdas (cast to MapCodec fails), so registry loading errors and the world never opens (run with YUNG's API present)",
  "yungs-better-ocean-monuments": "YUNG's structure processor types still reach 26.2's codec dispatch as 1.21 lambdas (cast to MapCodec fails), so registry loading errors and the world never opens (run with YUNG's API present)",
  "yungs-better-witch-huts": "YUNG's structure processor types still reach 26.2's codec dispatch as 1.21 lambdas (cast to MapCodec fails), so registry loading errors and the world never opens (run with YUNG's API present)",
  "presence-footsteps": "references FlyingMob, removed from 26.2 (run with Kirin present)",

  "terralith": "its worldgen datapack uses the 1.21 JSON shapes (feature keys, carver lists) that 26.2's registry loader rejects",
  "controlify": "its resource-reload listener implements the 1.21 reload signature through a path the reload adapter does not cover",
}
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
        if p[1] in RAW_SKIP or p[1].startswith("dev-"): continue   # dev-instance engine reruns are not corpus rows
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
    notes = (NOTES.get(name) or r["why"][:90]).replace("|", "/") if r["verdict"] != "PASS" else ""
    srv = label.get(server[name]["verdict"], "") if name in server else ""
    rm = label.get(retro[name]["verdict"], "") if name in retro else ""
    shot = shot_for(name); shot_md = f"[shot]({shot})" if shot else ""
    rows.append(f"| {name} | {source_version(r['port'], name)} | {label.get(r['verdict'], r['verdict'])} | {uc} | {srv} | {rm} | {shot_md} | {notes} |")
counts = collections.Counter(r["verdict"] for n, r in client.items() if not n.endswith("-A") and n not in SKIP)
commit = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
# head-to-head summary over every mod both tools ran (same instance, same base jars)
h2h = [n for n in client if n in retro and not n.endswith("-A") and n not in SKIP]
fox_ok = {n for n in h2h if client[n]["verdict"] == "PASS"}; rm_ok = {n for n in h2h if retro[n]["verdict"] == "PASS"}
summary = {"h2h_total": len(h2h), "fox_boots": len(fox_ok), "retro_boots": len(rm_ok), "fox_only": len(fox_ok - rm_ok), "retro_only": len(rm_ok - fox_ok),
           "both": len(fox_ok & rm_ok), "neither": len(set(h2h) - fox_ok - rm_ok), "all_mods": sum(counts.values()), "all_boots": counts.get("PASS", 0),
           "fox_only_names": sorted(fox_ok - rm_ok), "retro_only_names": sorted(rm_ok - fox_ok)}
(OUT / "compat-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print("head-to-head:", {k: v for k, v in summary.items() if not k.endswith("_names")})
print("retro rows without a client row:", sorted(n for n in retro if n not in client))
md = f"""# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client launched straight into a world.
**Boots** means the mod under test was loaded, the world started loading, and the game was still
running 8 seconds later, within a 150-second cap. Both tools in the head-to-head are graded by
exactly that rule, from the same launch command, base jars and instance layout; a boot in which
the tested mod was held back or failed to port is graded "held", not "boots". A screenshot is
linked where the game got far enough to take one. "Unresolved" is the number of classes,
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

Same mods, same instance, same base jars. Retromod ports on its first launch and asks for a
restart; the second launch is the verdict. Retromod 1.3.0-snapshot.10 for 26.2. Fox-Grade's
verdict requires the world to render and a screenshot to be taken; Retromod's requires the world
to load and the game to still be running.

| Mod | Fox-Grade | Retromod |
|---|---|---|
""" + "\n".join(f"| {n} | {label.get(client[n]['verdict'], client[n]['verdict'])}{(' — ' + (NOTES.get(n) or client[n]['why'])[:90].replace('|', '/')) if client[n]['verdict'] != 'PASS' and (NOTES.get(n) or client[n]['why']) else ''} | {label.get(retro[n]['verdict'], retro[n]['verdict'])}{(' — ' + retro[n]['why'][:70].replace('|', '/')) if retro[n]['verdict'] != 'PASS' and retro[n]['why'] else ''} |" for n in sorted(retro) if n in client and n not in SKIP) + """

""" + f"""Score: Fox-Grade {sum(1 for n in retro if n in client and client[n]['verdict'] == 'PASS')} / Retromod {sum(1 for n in retro if n in client and retro[n]['verdict'] == 'PASS')} of {sum(1 for n in retro if n in client and n not in SKIP)} mods booting.

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.
"""

# --- NeoForge -----------------------------------------------------------------------------------------------------
# A separate section rather than another column, because it is a separate question. The Fabric table asks whether a
# 1.21.x Fabric mod runs on 26.2; this asks whether a 1.21.1 *NeoForge* mod does, on a NeoForge 26.2 client, with
# NeoForge's own libraries alongside it. The mods are different mods and the numbers do not add up with the ones
# above — presenting them in one table would invite exactly that mistake.
# Causes the runner's own grep does not catch, read back out of the log. The first entry is not a port failure at
# all: NeoForge refuses to load a mod whose declared dependency is absent, which is the same "held" case the Fabric
# lane already reports separately, and counting it as a Fox-Grade failure would be counting someone else's.
NEO_CAUSES = [
    (r"Missing or unsupported mandatory dependencies", "HELD", "declares a dependency that has no 26.2 build"),
    (r"requires (\S+) any", "HELD", None),
    (r"Failed to create mod instance\. ModID: \S+", None, None),
    (r"(?:NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|IncompatibleClassChangeError):.*", None, None),
    (r"Mixin apply for mod \S+ failed \S+", None, None),
    # The client shut down seconds into startup with nothing logged: the mod list is printed, mixin setup finishes,
    # and then "Closing FML Loader" with no exception anywhere. Saying that plainly is better than a blank cell,
    # which reads as "we did not look".
    (r"Closing FML Loader", None, "client shut down during startup with no error logged"),
]


def read_neoforge_ledger(path):
    """The NeoForge lane writes verdict/name/why; the Fabric lane writes verdict/name/portline/why."""
    rows = {}
    logdir = pathlib.Path(path).parent
    stem = "log-nf2" if path.endswith("-2.txt") else "log-nf"
    for ln in pathlib.Path(path).read_text().splitlines():
        parts = ln.split("\t")
        if len(parts) < 2 or parts[0] not in ("PASS", "CRASH", "HELD", "STALL"):
            continue
        verdict, name = parts[0], parts[1]
        why = parts[2] if len(parts) > 2 else ""
        if verdict != "PASS":
            log = logdir / f"{stem}-{name}.log"
            text = log.read_text(errors="replace") if log.exists() else ""
            for pattern, regrade, label in NEO_CAUSES:
                m = re.search(pattern, text)
                if not m:
                    continue
                if regrade:
                    verdict = regrade
                if not why:
                    why = label or m.group(0)[:150]
                why = re.sub(r"^\[[0-9:]+\] \[[^\]]*\] \[[^\]]*\]: ", "", why)[:150]
                break
        rows[name] = {"verdict": verdict, "why": why}
    return rows


# The NeoForge lane can be split across instances to halve wall-clock; each writes its own ledger.
neo = {}
for _p in sorted(glob.glob(str(W / "batch2/ledger-neoforge*.txt"))):
    neo.update(read_neoforge_ledger(_p))
if neo:
    neo_pass = sum(1 for v in neo.values() if v["verdict"] == "PASS")
    md += f"""

## NeoForge

Fox-Grade ports NeoForge mods too, and on NeoForge it does it without a restart: the loader asks
registered locators for candidates while the mod set is still open, so a ported jar goes straight
into the launch that ported it.

{len(neo)} of the most-downloaded mods with a real NeoForge build for 1.21.1, each run on a NeoForge
26.2 client launched into a world, graded by the same rule as everything above — the world starts
loading, the game is still up 8 seconds later, and the mod is in the loaded-mod list. NeoForge's own
26.2 libraries are supplied, except the real 26.2 build of whichever mod is under test.

**{neo_pass} of {len(neo)} boot.**

| Mod | Result | Why not |
|---|---|---|
""" + "\n".join(
        f"| {n} | {label.get(neo[n]['verdict'], neo[n]['verdict'])} | "
        f"{(neo[n]['why'][:110].replace('|', '/')) if neo[n]['verdict'] != 'PASS' else ''} |"
        for n in sorted(neo)) + """

Reproduce with `tools/run-neoforge.sh`; the corpus is built by `tools/fetch-nf-corpus.py`.
"""

(OUT / "compat.md").write_text(md)
print(f"wrote {OUT/'compat.md'}: {len(rows)} rows, counts {dict(counts)}, shots {len(list(SHOTS.glob('*.png')))}")
