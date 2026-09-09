#!/usr/bin/env python3
"""Add a Minecraft version as a Fox-Grade target: fetch what it needs, and derive its tables from 26.2's.

Everything Fox-Grade knows is written against one version. Aiming it at another means saying, for every name in
those tables, what this version calls it — and rules.json already records that release by release, as adjacent step
blocks. This walks from 26.2 back to the version asked for and hands the chain to the generators.

Two things are fetched from Mojang: the client jar, and for anything before 26.x the published mappings, because
those versions ship obfuscated and an inventory of classes called "a" and "aa" is no use to a rename table.

Nothing here decides a version is supported. It builds the tables; Targets decides, and only on evidence.

Usage: add-target.py <version> [<version>...]
"""
import gzip, json, pathlib, subprocess, sys, urllib.request, zipfile

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"
UA = {"User-Agent": "foxgrade/1.1 (target builder)"}
NEWEST = "26.2"


def manifest():
    return json.load(urllib.request.urlopen(urllib.request.Request(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=UA), timeout=30))


def chain_to(target, rules):
    """Step blocks from 26.2 back to `target`, newest first — the order back_map composes in.

    Walks backwards from the newest version, taking any step that lands where we already are. Shortest path, so a
    shortcut block like 1.16.5->1.20.1 is preferred over five hops when one exists: fewer compositions, fewer
    chances for a name to be carried through a rename it did not actually undergo."""
    steps = {}
    for name, block in rules.items():
        if "->" not in name or ":" in name or not block.get("renames"):
            continue
        src, dst = name.split("->", 1)
        steps.setdefault(dst, []).append((src, name))
    frontier, seen = [(NEWEST, [])], {NEWEST}
    while frontier:
        here, path = frontier.pop(0)
        for src, name in steps.get(here, []):
            if src == target:
                return path + [name]
            if src not in seen:
                seen.add(src)
                frontier.append((src, path + [name]))
    return None


def fetch(version):
    """(client jar, mappings or None) for a version, downloaded once."""
    entry = next((v for v in manifest()["versions"] if v["id"] == version), None)
    if entry is None:
        raise SystemExit(f"Mojang does not publish a version called {version!r}")
    meta = json.load(urllib.request.urlopen(urllib.request.Request(entry["url"], headers=UA), timeout=60))
    jar = HERE / f"mc-{version}.jar"
    if not jar.exists():
        jar.write_bytes(urllib.request.urlopen(
            urllib.request.Request(meta["downloads"]["client"]["url"], headers=UA), timeout=600).read())
    maps = None
    if obfuscated(jar):
        maps = HERE / f"mappings-{version}.txt"
        if not maps.exists():
            src = meta["downloads"].get("client_mappings")
            if src is None:
                raise SystemExit(f"{version} is obfuscated and publishes no mappings; it cannot be a target")
            maps.write_bytes(urllib.request.urlopen(
                urllib.request.Request(src["url"], headers=UA), timeout=600).read())
    return jar, maps


def obfuscated(jar):
    """True when the jar's own class names are obfuscated, judged by how many are absurdly short."""
    with zipfile.ZipFile(jar) as z:
        names = [n[:-6] for n in z.namelist() if n.endswith(".class")][:400]
    if not names:
        return False
    short = sum(1 for n in names if len(n.rsplit("/", 1)[-1].split("$")[0]) <= 3)
    return short > len(names) / 2


def run(*argv):
    print("   $", " ".join(str(a) for a in argv[1:]))
    subprocess.run([sys.executable, *argv], cwd=HERE, check=True)


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    rules = json.load(gzip.open(RES / "rules.json.gz"))
    for version in sys.argv[1:]:
        print(f"\n=== {version} ===")
        steps = chain_to(version, rules)
        if steps is None:
            print(f"   no chain of step blocks reaches {version} from {NEWEST}; it needs one mining first")
            continue
        print("   chain:", " , ".join(steps))
        jar, maps = fetch(version)
        print(f"   jar: {jar.name}{' + published mappings' if maps else ' (already named)'}")
        run("gen-mc-classes.py", jar, version, *( [maps] if maps else [] ))
        run("gen-target-rules.py", version, ",".join(steps), NEWEST)
        run("gen-bridge-for-target.py", version, ",".join(steps), NEWEST)


main()
