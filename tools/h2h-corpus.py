#!/usr/bin/env python3
"""Build the 100-mod head-to-head corpus: Modrinth's most-downloaded Fabric mods for 1.21.1 that neither tool has
seen yet, each with its required dependencies resolved, downloaded into batch2/h2h/<slug>/. Writes
h2h-groups.txt (slug, flags, jar paths). Skips libraries and the renderer tier honestly (out of scope)."""
import json, os, pathlib, re, sys, time, urllib.parse, urllib.request
B = pathlib.Path.home() / "foxgrade-work/batch2"; OUT = B / "h2h"; OUT.mkdir(exist_ok=True)
TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 85
UA = {"User-Agent": "FoxCubFox21/fox-grade-harness (compat testing)"}
def api(path, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request("https://api.modrinth.com/v2" + path, headers=UA), timeout=30) as r: return json.load(r)
        except Exception as e:
            time.sleep(2 + 3 * i)
    return None
# already run through the harness (any verdict): fold the same way the compat page does
done = set()
for f in [B / "ledger.txt", B / "retromod-ledger.txt", pathlib.Path.home() / "foxgrade-work/batch121/ledger.txt"]:
    if f.exists():
        for ln in f.read_text().splitlines():
            p = ln.split("\t")
            if len(p) >= 2 and p[0] in ("PASS", "CRASH", "HELD", "STALL"): done.add(re.sub(r"-(fix|screen|A)$", "", p[1]).split("+")[0])
done |= {"betterhurtcam", "sound-physics-remastered", "better-stats", "friends-and-foes", "modmenu", "zoomify", "yacl", "lithium", "entityculling", "betterf3", "chat-heads", "shulkerboxtooltip", "wthit", "mouse-wheelie", "mouse-tweaks"}
BASE_DEPS = {"fabric-api", "fabric-language-kotlin", "minecraft", "fabricloader", "java"}
SKIP = {"sodium", "iris", "indium", "sodium-extra", "reeses-sodium-options", "immediatelyfast", "moreculling", "c2me-fabric", "nvidium", "distant-horizons", "vulkanmod",
        "enhanced-block-entities", "geckolib", "architectury-api", "cloth-config", "owo-lib", "balm", "collective", "puzzles-lib", "forge-config-api-port", "midnightlib",
        "cardinal-components-api", "resourceful-lib", "badpackets", "fabric-language-kotlin", "fabric-api", "yet-another-config-lib", "modmenu", "bobby", "krypton", "ferrite-core",
        "continuity", "lambdynamiclights", "lithium", "entityculling", "physics-mod", "simple-voice-chat", "replaymod", "flashback", "viafabricplus", "essential", "axiom",
        "vanilla-experience-modpack", "fabric-carpet", "spark", "xaeros-minimap", "xaeros-world-map", "jei", "rei", "appleskin", "dynamic-fps", "no-chat-reports", "trinkets",
        "inventory-profiles-next", "waystones", "capes", "noxesium", "worldedit", "polymer", "servux", "litematica", "malilib", "minihud", "tweakeroo", "itemscroller"}
def version_for(slug):
    v = api(f"/project/{slug}/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D")
    return v[0] if v else None
def download(url, dest):
    if dest.exists(): return True
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r, open(dest, "wb") as f: f.write(r.read())
        return True
    except Exception: return False
groups = []; seen = set(); offset = 0
existing = {ln.split("\t")[0] for ln in (B / "h2h-groups.txt").read_text().splitlines()} if (B / "h2h-groups.txt").exists() else set()
while len(groups) + len(existing) < TARGET and offset < 600:
    res = api(f"/search?facets=%5B%5B%22categories%3Afabric%22%5D%2C%5B%22versions%3A1.21.1%22%5D%2C%5B%22project_type%3Amod%22%5D%5D&index=downloads&limit=100&offset={offset}")
    if not res: break
    for hit in res["hits"]:
        slug = hit["slug"]
        if slug in seen or slug in done or slug in existing or slug in SKIP or "library" in hit.get("categories", []): continue
        seen.add(slug)
        v = version_for(slug); time.sleep(0.25)
        if not v or not v.get("files"): continue
        jars = []; flags = set(); ok = True
        gdir = OUT / slug; gdir.mkdir(exist_ok=True)
        primary = next((f for f in v["files"] if f.get("primary")), v["files"][0])
        dest = gdir / (slug + "--" + urllib.parse.unquote(primary["filename"]))
        if not download(primary["url"], dest): continue
        jars.append(str(dest))
        for dep in v.get("dependencies", []):
            if dep.get("dependency_type") != "required" or not dep.get("project_id"): continue
            proj = api(f"/project/{dep['project_id']}"); time.sleep(0.2)
            if not proj: continue
            ds = proj["slug"]
            if ds in BASE_DEPS: continue
            if ds == "cloth-config": flags.add("nobasecloth")
            if ds in ("geckolib",): flags.add("geckolib")
            dv = version_for(ds); time.sleep(0.2)
            if not dv or not dv.get("files"): ok = False; break
            dp = next((f for f in dv["files"] if f.get("primary")), dv["files"][0])
            ddest = gdir / (ds + "--" + urllib.parse.unquote(dp["filename"]))
            if not download(dp["url"], ddest): ok = False; break
            jars.append(str(ddest))
        if not ok: continue
        groups.append((slug, ",".join(sorted(flags)) or "-", jars))
        print(f"{len(groups) + len(existing):3d} {slug} ({len(jars)} jar{'s' if len(jars) > 1 else ''}{' ' + ','.join(sorted(flags)) if flags else ''})", flush=True)
        if len(groups) + len(existing) >= TARGET: break
    offset += 100
with open(B / "h2h-groups.txt", "a") as f:
    for slug, flags, jars in groups: f.write(f"{slug}\t{flags}\t{' '.join(jars)}\n")
print(f"groups written: {len(groups)} (total {len(groups) + len(existing)})")
