#!/usr/bin/env python3
"""Build the class inventory for a version without deriving anything else.

An inventory needs only the version itself — the jar, and the published mappings when it is obfuscated. Rename
tables need a chain of step blocks and most versions have none, but the inventory can be built first and is what
decides which versions share an API, which is what decides how many chains are needed at all.

Usage: fetch-inventories.py <version> [<version>...]
"""
import json, pathlib, subprocess, sys, urllib.request, zipfile

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"
UA = {"User-Agent": "foxgrade/1.1 (inventory builder)"}


def obfuscated(jar):
    with zipfile.ZipFile(jar) as z:
        names = [n[:-6] for n in z.namelist() if n.endswith(".class")][:400]
    if not names:
        return False
    return sum(1 for n in names if len(n.rsplit("/", 1)[-1].split("$")[0]) <= 3) > len(names) / 2


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    manifest = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=UA), timeout=30))
    index = {v["id"]: v["url"] for v in manifest["versions"]}
    for version in sys.argv[1:]:
        out = RES / f"mc-{version}.classes.json.gz"
        if out.exists():
            print(f"{version}: already built"); continue
        if version not in index:
            print(f"{version}: Mojang publishes no such version"); continue
        meta = json.load(urllib.request.urlopen(urllib.request.Request(index[version], headers=UA), timeout=60))
        jar = HERE / f"mc-{version}.jar"
        if not jar.exists():
            jar.write_bytes(urllib.request.urlopen(
                urllib.request.Request(meta["downloads"]["client"]["url"], headers=UA), timeout=900).read())
        args = [sys.executable, "gen-mc-classes.py", str(jar), version]
        if obfuscated(jar):
            maps = HERE / f"mappings-{version}.txt"
            src = meta["downloads"].get("client_mappings")
            if src is None:
                print(f"{version}: obfuscated and publishes no mappings; skipped"); continue
            if not maps.exists():
                maps.write_bytes(urllib.request.urlopen(
                    urllib.request.Request(src["url"], headers=UA), timeout=900).read())
            args.append(str(maps))
        subprocess.run(args, cwd=HERE, check=True)


main()
