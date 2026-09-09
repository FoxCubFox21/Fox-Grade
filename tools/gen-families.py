#!/usr/bin/env python3
"""Group versions that share an API, so one measurement can speak for several.

Fox-Grade supports a version when a corpus has been run against it, and a corpus run costs an instance, a world and
the better part of an hour. That is the real limit on how many versions can be offered — not the tables, which
generate in minutes.

But Minecraft's patch releases frequently do not move the API at all. 26.1, 26.1.1 and 26.1.2 declare identical
class sets and differ by a single added method across all three; Fabric API ships one build for the lot. Where that
holds, one corpus run is evidence for every version in the group, and one set of rename tables describes them all.

Two versions are family if their class sets are identical and almost no class differs in its members. "Almost" is
doing real work and is deliberately tight: the threshold is a handful of classes, because a family is a claim that a
port built for one will behave on another, and a claim like that should be boring to check.

The class inventory itself is never shared — that file answers "does this member exist here", and it is exactly
where a single added method matters. Families share the heavy rename tables; each version keeps its own inventory.

Usage: gen-families.py [--write]
Without --write, prints what it found and changes nothing.
"""
import gzip, json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"

# How many classes may differ in their members before two versions are different APIs.
MAX_DIFFERING_CLASSES = 8


def version_key(v):
    return [int(x) if x.isdigit() else 0 for x in v.split(".")]


def inventories():
    out = {}
    for f in sorted(RES.glob("mc-*.classes.json.gz")):
        v = re.fullmatch(r"mc-(.+)\.classes\.json\.gz", f.name).group(1)
        out[v] = json.load(gzip.open(f))
    return out


def same_api(a, b):
    if set(a) != set(b):
        return False, None
    differing = sum(1 for k in a if a[k] != b[k])
    return differing <= MAX_DIFFERING_CLASSES, differing


def main():
    inv = inventories()
    versions = sorted(inv, key=version_key)
    families, seen = [], set()
    for v in versions:
        if v in seen:
            continue
        group = [v]
        seen.add(v)
        for w in versions:
            if w in seen:
                continue
            ok, _ = same_api(inv[v], inv[w])
            if ok:
                group.append(w)
                seen.add(w)
        families.append(group)

    print(f"{len(inv)} versions with inventories, in {len(families)} API families:\n")
    mapping = {}
    for group in families:
        rep = max(group, key=version_key)          # the newest member has the most complete tables
        detail = ""
        if len(group) > 1:
            worst = max(same_api(inv[rep], inv[g])[1] or 0 for g in group if g != rep)
            detail = f"   ({worst} class(es) differ at most)"
            for g in group:
                if g != rep:
                    mapping[g] = rep
        print(f"  {', '.join(sorted(group, key=version_key))}  ->  tables of {rep}{detail}")

    if "--write" in sys.argv:
        out = HERE / "families.json"
        out.write_text(json.dumps(mapping, indent=2, sort_keys=True) + "\n")
        print(f"\nwrote {out.name}: {len(mapping)} version(s) mapped onto a family representative")
    else:
        print(f"\n{len(mapping)} version(s) would be mapped onto a representative; pass --write to record it")


main()
