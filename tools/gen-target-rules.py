#!/usr/bin/env python3
"""Derive the rules block for another target version from the one Fox-Grade already has.

A target block is mostly porting knowledge that does not depend on which recent version you are aiming at: what
net.minecraft.util.BlockPos is called now, that Material was deleted, that @SideOnly has no Fabric analogue. Aiming at
26.1.2 instead of 26.2 changes only the handful of entries whose destination 26.2 itself renamed, and rules.json
already records those as a step block. So the derivation is: rewrite each destination backwards through that step,
then keep only what the new target actually has.

That last part is the point. Every rename kept has been checked against the target's own class inventory, and anything
that does not resolve is dropped and named in the report rather than shipped on the assumption that it is close
enough. A rename pointing at a class that is not there is worse than no rename: it turns a missing reference the port
report would have listed into a reference to something that never existed.

Usage: gen-target-rules.py <newTarget> <stepBlock> [sourceTarget]
   e.g. gen-target-rules.py 26.1.2 "26.1->26.2" 26.2
Rewrites rules.json.gz in place, adding the new target block.
"""
import gzip, json, pathlib, sys

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"


def back_map(rules, step_names):
    """One "call it what this version called it" map, composed from a chain of adjacent steps.

    Going down more than one release means walking back through each in turn: a name is what 26.2 calls it, then
    what 1.21.1 called it, then what 1.20.4 did. Steps are given newest-first and applied in that order, so a class
    renamed twice ends up under the oldest name rather than a halfway one.

    Names that no step mentions pass through untouched, which is the common case — most classes were not renamed."""
    back = {}
    for name in step_names:
        block = rules.get(name)
        if block is None:
            raise SystemExit(f"rules.json has no step block {name!r}")
        step = {r["toFqcn"]: r["fromFqcn"] for r in block.get("renames", [])}
        if not back:
            back = dict(step)
            continue
        # Send everything already mapped one release further back, then add names this step is the first to touch.
        back = {new: step.get(old, old) for new, old in back.items()}
        for new, old in step.items():
            back.setdefault(new, old)
    return back


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    new_target, step_names = sys.argv[1], sys.argv[2].split(",")
    src_target = sys.argv[3] if len(sys.argv) > 3 else "26.2"

    rules_path = RES / "rules.json.gz"
    rules = json.load(gzip.open(rules_path))
    for needed in (src_target, *step_names):
        if needed not in rules:
            print(f"rules.json has no block {needed!r}"); sys.exit(1)
    inv_path = RES / f"mc-{new_target}.classes.json.gz"
    if not inv_path.exists():
        print(f"no class inventory at {inv_path.name} — run gen-mc-classes.py first"); sys.exit(1)
    present = set(json.load(gzip.open(inv_path)))

    back = back_map(rules, step_names)                         # target name -> what this version calls it

    def exists(fqcn):
        return fqcn.replace(".", "/") in present

    kept, dropped, rewritten = [], [], 0
    for r in rules[src_target].get("renames", []):
        entry = dict(r)
        if entry["toFqcn"] in back:
            entry["toFqcn"] = back[entry["toFqcn"]]
            entry["toSimple"] = entry["toFqcn"].rsplit(".", 1)[-1]
            rewritten += 1
        if exists(entry["toFqcn"]):
            kept.append(entry)
        else:
            dropped.append(f"{entry['fromFqcn']} -> {entry['toFqcn']} (not in {new_target})")

    # A class deleted by the newer target may still be present in this one, and then saying it was deleted is simply
    # wrong. Keep only the entries the inventory agrees with.
    deleted, undeleted = [], []
    for d in rules[src_target].get("deleted", []):
        (deleted if not exists(d["fqcn"]) else undeleted).append(d)

    rules[new_target] = {
        "renames": kept,
        "advisories": rules[src_target].get("advisories", []),   # prose guidance, not version-specific transforms
        "deleted": deleted,
    }
    with gzip.open(rules_path, "wt") as fh:
        json.dump(rules, fh, separators=(",", ":"), sort_keys=False)

    report = (["== renames dropped: destination absent from the target =="] + dropped
              + ["", "== 'deleted' entries dropped: the class still exists on this target =="]
              + [d["fqcn"] for d in undeleted])
    pathlib.Path(f"/tmp/rules-{new_target}-report.txt").write_text("\n".join(report) + "\n")
    print(f"{new_target}: {len(kept)} renames kept ({rewritten} redirected through {",".join(step_names)}), "
          f"{len(dropped)} dropped; {len(deleted)} deletions kept, {len(undeleted)} dropped as still present")
    print(f"detail: /tmp/rules-{new_target}-report.txt")


main()
