#!/usr/bin/env python3
"""The corpus jars one mod needs before it can be judged.

A mod that declares a hard dependency and is launched without it does not fail, it is refused: Fabric puts up a
"Startup Errors!" dialog naming the missing mod and never reaches a world. Grading that as the porter's failure
measures the harness, not the port — Roughly Enough Items was recorded as a stall on 1.21.2 for exactly that reason,
with Architectury API and Cloth Config sitting unused in the same corpus directory.

The opposite mistake is worse and was made first: loading every library into every run. Mods then collide with each
other, and a run that fails says nothing about the mod under test. So this resolves what a jar actually declares,
transitively, and nothing else.

Prints one absolute path per line, excluding the mod under test itself. Anything not in the corpus is left out
silently — a dependency Fox-Grade cannot supply is a real reason the mod does not boot.

Usage: deps-closure.py <mod.jar> <corpus-dir>
"""
import json, pathlib, sys, zipfile

# Supplied by the instance itself, not by the corpus: the loader, the game, the JDK, and the Fabric API build that
# matches the target, which every lane copies in from <instance>-base.
AMBIENT = {"minecraft", "java", "fabricloader", "fabric", "fabric-api", "quilt_loader", "quilt_base"}


def manifest(jar):
    try:
        with zipfile.ZipFile(jar) as z:
            for name in ("fabric.mod.json", "quilt.mod.json"):
                if name in z.namelist():
                    return json.loads(z.read(name).decode("utf-8", "replace")), name
    except Exception:
        pass
    return None, None


def ids_and_deps(jar):
    """Every id this jar answers to, and every modId it hard-depends on."""
    m, kind = manifest(jar)
    if not m:
        return set(), set()
    if kind == "quilt.mod.json":
        ql = m.get("quilt_loader", {})
        ids = {ql.get("id", "")}
        deps = set()
        for d in ql.get("depends", []):
            d = d.get("id") if isinstance(d, dict) else d
            if d:
                deps.add(str(d).split(":")[-1])
        return {i for i in ids if i}, deps - AMBIENT
    ids = {m.get("id", "")}
    for p in m.get("provides", []):
        ids.add(p)
    deps = set(m.get("depends", {}).keys())
    # Fabric API is shipped as ~40 modules named fabric-*-v1; the instance supplies the whole build, so a dependency
    # on any one of them is already satisfied and must not send us looking for a corpus jar that does not exist.
    deps = {d for d in deps if d not in AMBIENT and not d.startswith("fabric-")}
    return {i for i in ids if i}, deps


def curated(here):
    """modId -> extra required modIds, for requirements enforced in code rather than declared."""
    out = {}
    f = here / "extra-deps.txt"
    if not f.exists():
        return out
    for line in f.read_text().splitlines():
        line = line.split("#")[0].split()
        if len(line) >= 2:
            out.setdefault(line[0], set()).update(line[1:])
    return out


def main():
    target, corpus = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    extra = curated(pathlib.Path(__file__).resolve().parent)
    index = {}
    for jar in sorted(corpus.glob("*.jar")):
        for i in ids_and_deps(jar)[0]:
            index.setdefault(i, jar)

    def needs(jar):
        ids, deps = ids_and_deps(jar)
        for i in ids:
            deps |= extra.get(i, set())
        return deps

    seen, out, queue = set(), [], list(needs(target))
    while queue:
        want = queue.pop()
        if want in seen:
            continue
        seen.add(want)
        jar = index.get(want)
        if jar is None or jar.resolve() == target.resolve():
            continue
        out.append(jar)
        queue.extend(needs(jar))
    for jar in out:
        print(jar.resolve())


main()
