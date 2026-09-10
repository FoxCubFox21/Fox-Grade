#!/usr/bin/env python3
"""Download the asset index and objects a version needs, into the launcher's shared assets directory.

make-instance.py builds everything else a lane needs but assumes the assets are already there, which is true only
for versions the launcher has actually run. For the rest the client starts, logs "Can't open the resource index
file", and never reaches a world — every mod in the lane graded a stall for a fact about the assets folder rather
than anything about the port. 1.19.4 scored 0 against the same corpus that 1.20.1, whose index was present, scored
10 out of 10 on.

Objects are content-addressed and shared between versions, so a second version costs only what it does not already
have. Existing files are left alone.

Usage: fetch-assets.py <version>...
"""
import json, pathlib, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = {"User-Agent": "foxgrade/1.1 (mod porter test harness)"}
ASSETS = pathlib.Path.home() / "Library/Application Support/minecraft/assets"
RES = "https://resources.download.minecraft.net"


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()


def main():
    manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    by = {v["id"]: v["url"] for v in manifest["versions"]}
    for version in sys.argv[1:]:
        if version not in by:
            print(f"{version}: not in the manifest"); continue
        meta = json.loads(get(by[version]))
        ai = meta["assetIndex"]
        idx = ASSETS / "indexes" / f"{ai['id']}.json"
        idx.parent.mkdir(parents=True, exist_ok=True)
        if not idx.exists():
            idx.write_bytes(get(ai["url"]))
        objects = json.loads(idx.read_text())["objects"]
        todo = []
        for entry in objects.values():
            h = entry["hash"]
            dest = ASSETS / "objects" / h[:2] / h
            if not dest.exists():
                todo.append((h, dest))
        print(f"{version}: index {ai['id']}, {len(objects)} objects, {len(todo)} to download")

        def one(job):
            h, dest = job
            dest.parent.mkdir(parents=True, exist_ok=True)
            try:
                dest.write_bytes(get(f"{RES}/{h[:2]}/{h}"))
            except Exception:
                pass                       # a missing object degrades a sound, it does not fail a lane

        if todo:
            with ThreadPoolExecutor(max_workers=16) as pool:
                list(pool.map(one, todo))
        have = sum(1 for e in objects.values() if (ASSETS / "objects" / e["hash"][:2] / e["hash"]).exists())
        print(f"  {have} of {len(objects)} objects present")


main()
