#!/usr/bin/env python3
"""Derive Fabric API class renames and removals by diffing two Fabric API builds.

Measured across the 26.2 corpus: of 217 distinct classes a port still cannot resolve, 176 are Fabric API and only 41
are Minecraft. Fox-Grade's Minecraft tables are mature; its Fabric API coverage is the gap, and it clusters — event
lifecycle, client screen, transfer, datagen, biome, networking.

Unlike Minecraft, Fabric API is open and versioned per module, so this needs no guessing. A class that kept its
simple name and changed package moved; one whose name is gone entirely was deleted or renamed beyond recognition and
is reported rather than mapped. Modules live in META-INF/jars inside the umbrella jar, so both are walked.

Only same-simple-name matches are emitted, and only when the target has exactly one candidate. Anything ambiguous is
printed for a person, because a wrong class rename fails later and far less legibly than an unresolved reference the
port report can name.

Usage: gen-fabric-api-tables.py <old-fabric-api.jar> <new-fabric-api.jar> [--write]
"""
import collections, json, pathlib, sys, zipfile

W = pathlib.Path.home() / "foxgrade-work"
OUT = W / "foxgrade-mod/src/main/resources/foxgrade/fabric-api-bridges.json"


def classes_of(jar):
    """Every class in the umbrella jar and in every module nested inside it."""
    found = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith(".class"):
                found.add(n[:-6])
            elif n.startswith("META-INF/jars/") and n.endswith(".jar"):
                import io
                try:
                    with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                        found |= {m[:-6] for m in inner.namelist() if m.endswith(".class")}
                except Exception:
                    pass
    return {c for c in found if c.startswith("net/fabricmc/fabric/api/")}


def main():
    old_jar, new_jar = sys.argv[1], sys.argv[2]
    write = "--write" in sys.argv
    old, new = classes_of(old_jar), classes_of(new_jar)
    gone = sorted(old - new)
    print(f"old: {len(old)} api classes    new: {len(new)} api classes    gone from new: {len(gone)}")

    by_simple = collections.defaultdict(list)
    for c in new:
        by_simple[c.rsplit("/", 1)[-1]].append(c)

    renames, ambiguous, deleted = {}, [], []
    for c in gone:
        simple = c.rsplit("/", 1)[-1]
        cands = by_simple.get(simple, [])
        if len(cands) == 1:
            renames[c] = cands[0]
        elif len(cands) > 1:
            ambiguous.append((c, cands))
        else:
            deleted.append(c)

    print(f"  moved, one candidate each : {len(renames)}")
    print(f"  ambiguous, not emitted    : {len(ambiguous)}")
    print(f"  deleted outright          : {len(deleted)}")
    for c, v in list(renames.items())[:8]:
        print(f"    {c}\n      -> {v}")
    if ambiguous:
        print("  ambiguous examples:")
        for c, cands in ambiguous[:4]:
            print(f"    {c} -> {cands}")

    if not write:
        print("\n(dry run; pass --write to merge into fabric-api-bridges.json)")
        return
    d = json.loads(OUT.read_text())
    cr = d.setdefault("classRenames", {})
    added = sum(1 for k, v in renames.items() if cr.get(k) != v)
    cr.update(renames)
    OUT.write_text(json.dumps(d, indent=1) + "\n")
    print(f"\nmerged {added} new class rename(s) into {OUT.name}")


main()
