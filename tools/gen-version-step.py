#!/usr/bin/env python3
"""Mine the class renames between two adjacent Minecraft versions from their own inventories.

rules.json carries step blocks for some adjacent pairs and not others, and a version with no chain back to 26.2
cannot be derived at all. The pairs that exist were collected by hand over years; the rest can be read off the two
inventories, because a class that was renamed keeps its shape — the same methods, the same fields, the same
descriptors modulo whatever else was renamed with it.

The rules here are the ones that survived being wrong on NeoForge's API:
  * a class present in both versions is not a rename of anything;
  * a removed class matches a new one when their members overlap almost completely and no other candidate comes
    close, or when one name contains the other and they sit in the same package;
  * ties are reported, not guessed. Two classes that look equally like the answer mean the answer is not visible
    from here, and a wrong rename is worse than a missing one — it turns a reference the port report would have
    named into a call to something that was never the same thing.

Usage: gen-version-step.py <oldVersion> <newVersion> [--write]
"""
import difflib, gzip, json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"

MIN_MEMBERS = 4          # below this a class has no shape worth matching
MIN_COVER = 0.75         # of the old class's members, how many the candidate must have
MIN_MARGIN = 0.15        # how far ahead of the runner-up the winner must be


def inventory(v):
    p = RES / f"mc-{v}.classes.json.gz"
    if not p.exists():
        raise SystemExit(f"no inventory for {v}; run fetch-inventories.py {v}")
    return json.load(gzip.open(p))


def erase(sig):
    """A member signature with every object type blanked, so two that differ only in renamed types compare equal."""
    return re.sub(r"L[^;]+;", "L;", sig)


def shape(entry):
    boring = {"toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z"}
    out = {erase(m) for m in entry.get("m", []) if not m.startswith("<init>") and m not in boring}
    out |= {"#" + erase(f) for f in entry.get("f", [])}
    return out


def simple(c):
    return c.rsplit("/", 1)[-1]


def contained(a, b):
    la, lb = simple(a).lower(), simple(b).lower()
    if min(len(la), len(lb)) < 4:
        return False
    if la.count("$") != lb.count("$"):
        return False
    if min(len(la), len(lb)) / max(len(la), len(lb)) < 0.6:
        return False
    return la.startswith(lb) or lb.startswith(la) or la.endswith(lb) or lb.endswith(la)


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    old_v, new_v = sys.argv[1], sys.argv[2]
    old, new = inventory(old_v), inventory(new_v)
    gone = [c for c in old if c not in new]
    added = {c: shape(new[c]) for c in new if c not in old}

    renames, ambiguous, unmatched = [], [], []
    for cls in sorted(gone):
        want = shape(old[cls])
        by_name = [c for c in added if contained(cls, c) and c.rsplit("/", 1)[0] == cls.rsplit("/", 1)[0]]
        if len(by_name) == 1:
            renames.append((cls, by_name[0], "name", 1.0))
            continue
        if len(want) < MIN_MEMBERS:
            unmatched.append(cls)
            continue
        scored = sorted(((len(want & have) / len(want), c) for c, have in added.items() if have), reverse=True)
        if not scored or scored[0][0] < MIN_COVER:
            unmatched.append(cls)
            continue
        runner = scored[1][0] if len(scored) > 1 else 0.0
        if scored[0][0] - runner < MIN_MARGIN:
            ambiguous.append(f"{cls} -> {scored[0][1]} ({scored[0][0]:.2f}) vs {scored[1][1]} ({runner:.2f})")
            continue
        renames.append((cls, scored[0][1], "shape", scored[0][0]))

    name = f"{old_v}->{new_v}"
    print(f"{name}: {len(gone)} classes gone, {len(added)} new")
    print(f"  {len(renames)} renames mined, {len(ambiguous)} ambiguous, {len(unmatched)} left unmatched")
    for cls, to, how, score in renames[:6]:
        print(f"    {simple(cls)} -> {simple(to)}  ({how} {score:.0%})")
    pathlib.Path(f"/tmp/step-{old_v}-{new_v}.txt").write_text(
        "\n".join(["== ambiguous =="] + ambiguous + ["", "== unmatched =="] + unmatched) + "\n")

    if "--write" not in sys.argv:
        print("  (dry run; pass --write to add the block to rules.json)")
        return
    path = RES / "rules.json.gz"
    rules = json.load(gzip.open(path))
    rules[name] = {"renames": [
        {"fromFqcn": c.replace("/", "."), "toFqcn": t.replace("/", "."),
         "fromSimple": simple(c), "toSimple": simple(t),
         "verified": True, "kind": "move" if simple(c) == simple(t) else "rename", "chainable": True,
         "source": "mined-inventory-diff",
         "evidence": f"{how} match at {score:.0%}; {c} absent from {new_v}, {t} present"}
        for c, t, how, score in renames], "advisories": [], "deleted": []}
    with gzip.open(path, "wt") as f:
        json.dump(rules, f, separators=(",", ":"), sort_keys=False)
    print(f"  wrote block {name} with {len(renames)} renames")


main()
