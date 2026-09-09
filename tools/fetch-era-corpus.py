#!/usr/bin/env python3
"""Build a Fabric test corpus for one version era the same way the NeoForge one was built: the most-used mods that really ship a
Forge build for the source version — Forge and NeoForge are separate loaders with separate APIs
(net.minecraftforge.* against net.neoforged.*), so a NeoForge jar is not a Forge test and reusing that corpus
would measure nothing, minus the ones Fox-Grade declares out of scope (heavyweight rendering)."""
import json, pathlib, sys, urllib.parse, urllib.request

UA = "foxgrade/1.1 (mod porter test harness)"
SRC = sys.argv[1] if len(sys.argv) > 1 else "1.21.1"
WANT = int(sys.argv[2]) if len(sys.argv) > 2 else 40
OUT = pathlib.Path.home() / f"foxgrade-work/era-corpus/{SRC}"
# Declared out of scope on the project page: world/terrain rendering engines and their addons.
SKIP = {"sodium", "iris", "sodium-extra", "reeses-sodium-options", "distanthorizons", "nvidium",
        "optifine", "embeddium", "oculus", "rubidium"}


def api(path, **params):
    q = urllib.parse.urlencode({k: json.dumps(v) if not isinstance(v, str) else v for k, v in params.items()})
    req = urllib.request.Request(f"https://api.modrinth.com/v2/{path}?{q}", headers={"User-Agent": UA})
    return json.load(urllib.request.urlopen(req, timeout=30))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    hits, offset = [], 0
    while len(hits) < WANT * 4 and offset < 300:
        page = api("search", facets=[["categories:fabric"], [f"versions:{SRC}"], ["project_type:mod"]],
                   limit="100", offset=str(offset), index="downloads")
        if not page["hits"]:
            break
        hits += page["hits"]
        offset += 100
    got = 0
    for h in hits:
        if got >= WANT:
            break
        slug = h["slug"]
        if slug in SKIP:
            continue
        if list(OUT.glob(f"{slug}--*.jar")):
            got += 1
            continue
        try:
            vs = api(f"project/{slug}/version", loaders=["neoforge"], game_versions=[SRC])
        except Exception as e:
            print(f"  ! {slug}: {e}")
            continue
        if not vs:
            continue
        f = vs[0]["files"][0]
        dest = OUT / f"{slug}--{f['filename']}"
        try:
            req = urllib.request.Request(f["url"], headers={"User-Agent": UA})
            dest.write_bytes(urllib.request.urlopen(req, timeout=120).read())
        except Exception as e:
            print(f"  ! {slug} download: {e}")
            continue
        got += 1
        print(f"{got:3d}. {dest.name}  ({dest.stat().st_size // 1024} KB)")
    print(f"\n{got} Fabric {SRC} mods in {OUT}")


main()
