#!/usr/bin/env python3
"""Derive the intermediary→Mojang bridge for another recent target from the one already built.

Intermediary ids are stable across Minecraft versions; what changes is the Mojang name each id points at. Between two
adjacent releases most of those names are identical, so the existing bridge is very nearly the bridge for the earlier
one too — and rules.json already records the class renames that separate them.

So: send every destination backwards through that step, then throw away everything the target cannot confirm. The
confirmation is the whole value. A class must be in the target's inventory; a member must be declared on the class it
is claimed for. Anything that fails is dropped, not carried across on the assumption that a name which exists in one
version exists in its neighbour. A dropped mapping costs an unresolved reference the port report names in English. A
wrong one costs a mod that loads and misbehaves, which is the failure Fox-Grade exists to avoid.

Usage: gen-bridge-for-target.py <newTarget> <stepBlock> [sourceTarget]
   e.g. gen-bridge-for-target.py 26.1.2 "26.1->26.2" 26.2
"""
import datetime, gzip, json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE / "foxgrade-mod/src/main/resources/foxgrade"


def shim_names():
    """Every name ShimGenerator can inject a class under, read from its own source.

    A bridge may point at a class the target does not have, but only when something will put that class into the
    ported jar. That list lives in ShimGenerator's SHIMS table and nowhere else, so it is read from there rather than
    guessed at or duplicated."""
    src = HERE / "foxgrade-mod/src/main/java/foxgrade/ShimGenerator.java"
    if not src.exists():
        return set()
    return set(re.findall(r'Map\.entry\("([^"]+)"', src.read_text()))


def redirect_names():
    """Every member name a bridge table redirects, as (owner, name) pairs and as bare names.

    A member mapping may point at something the target does not declare, but only when a redirect will catch the call
    and send it somewhere real. FoodData.getExhaustionLevel is the case: 26.2 has no such method and the bridge names
    it anyway, because callRedirects routes it to a shim. Anything not covered that way has to resolve on the target
    or it is a mapping to nothing."""
    path = HERE / "foxgrade-mod/src/main/resources/foxgrade/fabric-api-bridges.json"
    if not path.exists():
        return set(), set()
    table = json.load(path.open())
    pairs, bare = set(), set()
    for section in ("callRedirects", "fieldRedirects", "renames"):
        for owner, members in (table.get(section) or {}).items():
            if not isinstance(members, dict):
                continue
            for key in members:
                name = re.split(r"[(:]", key.split(" ")[-1], maxsplit=1)[0]
                pairs.add((owner.replace(".", "/"), name))
                bare.add(name)
    return pairs, bare


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

    src = json.load(gzip.open(RES / f"intermediary-to-mojang.{src_target}.json.gz"))
    rules = json.load(gzip.open(RES / "rules.json.gz"))
    inv = json.load(gzip.open(RES / f"mc-{new_target}.classes.json.gz"))

    back = back_map(rules, step_names)
    # Member names the target declares, per class and in aggregate. Descriptors are dropped: the bridge answers
    # "what is this called now", and a descriptor that moved with a renamed type would reject a correct answer.
    per_class = {c: {re.split(r"[(:]", m, maxsplit=1)[0] for m in v["m"]} | {f.split(":", 1)[0] for f in v["f"]}
                 for c, v in inv.items()}
    anywhere = set().union(*per_class.values()) if per_class else set()

    def moved(fqcn):
        return back.get(fqcn, fqcn)

    def has_class(fqcn):
        return fqcn.replace(".", "/") in per_class

    stats = {}

    # A class mapping is kept when the target has the class, or when Fox-Grade ships a shim under that exact name.
    # Both halves matter and each was learned by getting it wrong.
    #
    # Minecraft 26.2 has no InteractionResultHolder, and the shipped 26.2 bridge maps class_1271 to it anyway,
    # because ShimGenerator supplies that class inside the port. Filtering purely on the inventory dropped mappings
    # like that, and architectury, balm and JEI ported for 26.1.2 died on NoClassDefFoundError for a raw
    # intermediary name the engine cannot recognise.
    #
    # Keeping everything is worse. A destination that neither exists nor has a shim turns a working call into a
    # reference to nothing: AppleSkin and Cloth Config passed on 26.1.2 with the filter and crashed without it, on
    # MultiBufferSource$BufferSource, which 26.1.2 lacks and no shim covers on that version.
    shims = shim_names()
    classes, kept_for_shim, gone = {}, 0, []
    for im, moj in src["classes"].items():
        m = moved(moj)
        if has_class(m):
            classes[im] = m
        elif m.replace(".", "/") in shims:
            classes[im] = m
            kept_for_shim += 1
        else:
            gone.append(f"{im} -> {m} (not in {new_target}, and no shim supplies it)")
    stats["classes"] = (len(classes), len(gone))
    print(f"  {kept_for_shim} of those exist only because a shim supplies them")

    # Per-class member maps. Members are NOT filtered by whether the target declares them, and that is the whole
    # point of a bridge rather than a mapping: a name the target no longer has is exactly what needs a name, so a
    # redirect can recognise the call and send it somewhere that works. Minecraft 26.2's FoodData has no
    # getExhaustionLevel, and the shipped 26.2 bridge maps method_35219 to it anyway, because callRedirects then
    # routes that call to a shim. Filtering members by presence deletes precisely the entries redirects depend on,
    # leaving the call site holding an intermediary name nothing can match — which is how a 26.1.2 port reached a
    # world and then died on 'float FoodData.method_35219()'.
    #
    # Classes are still checked, above: a class rename pointing at a class that is not there helps nobody, since
    # there is no call to redirect, only a type that will not resolve.
    # A member is kept when the target declares it, or when a redirect covers it. Both halves were learned by getting
    # it wrong. Dropping everything the target lacks deleted FoodData.getExhaustionLevel, which exists only to be
    # redirected, and a 26.1.2 port died on the raw intermediary name. Keeping everything mapped
    # MultiBufferSource.immediateWithBuffers to its 26.2 signature, which 26.1.2 does not have, and AppleSkin,
    # Cloth Config and FerriteCore went from passing to crashing.
    redir_pairs, redir_bare = redirect_names()
    members, drop_m = {}, 0
    for im_cls, groups in src["members"].items():
        target_cls = classes.get(im_cls)
        if target_cls is None:
            drop_m += sum(len(g) for g in groups.values())
            continue                                   # the owning class itself is gone; its members name nothing
        owner = target_cls.replace(".", "/")
        owned = per_class.get(owner, set())
        kept = {}
        for kind, mapping in groups.items():
            good = {k: v for k, v in mapping.items()
                    if v in owned or v.startswith("lambda$") or (owner, v) in redir_pairs}
            drop_m += len(mapping) - len(good)
            if good:
                kept[kind] = good
        if kept:
            members[im_cls] = kept
    stats["members"] = (sum(len(g) for v in members.values() for g in v.values()), drop_m)

    def filter_global(mapping):
        # Same rule as the per-class maps, minus the owner: a global name is kept if the target has it anywhere, or
        # if some redirect names it.
        good = {k: v for k, v in mapping.items() if v in anywhere or v.startswith("lambda$") or v in redir_bare}
        return good, len(mapping) - len(good)

    global_methods, dm = filter_global(src["globalMethods"])
    global_fields, df = filter_global(src["globalFields"])
    stats["globals"] = (len(global_methods) + len(global_fields), dm + df)

    def filter_values(byowner, allowed):
        """Keep by destination name only, leaving owner keys untouched; same rule as the maps above."""
        out, dropped = {}, 0
        for owner, mapping in byowner.items():
            good = {k: v for k, v in mapping.items()
                    if v in allowed or v.startswith("lambda$") or v == "<clinit>" or v in redir_bare}
            dropped += len(mapping) - len(good)
            if good:
                out[owner] = good
        return out, dropped

    def filter_by_owner(byclass):
        out, dropped = {}, 0
        for owner, mapping in byclass.items():
            target_owner = moved(owner.replace("/", ".")).replace(".", "/")
            owned = per_class.get(target_owner)
            if owned is None:
                dropped += len(mapping); continue
            good = {k: v for k, v in mapping.items()
                    if v in owned or v.startswith("lambda$") or v == "<clinit>" or (target_owner, v) in redir_pairs}
            dropped += len(mapping) - len(good)
            if good:
                out[target_owner] = good
        return out, dropped

    # mojangMethods is keyed by the target's own class names, so the owner moves with the step and can be checked
    # against that class. The yarn maps are keyed by *yarn* class names, which name the source version and never move;
    # translating those through a Mojang rename would look up a class that was never meant to exist. Their owners stay
    # exactly as they are and only the destination names are checked.
    mojang_methods, d1 = filter_by_owner(src["mojangMethods"])
    yarn_methods, d2 = filter_values(src["yarnMethods"], anywhere)
    yarn_fields, d3 = filter_values(src["yarnFields"], anywhere)
    mojang_global, d4 = filter_global(src["mojangMethodsGlobal"])
    stats["byOwner"] = (len(mojang_methods) + len(yarn_methods) + len(yarn_fields), d1 + d2 + d3 + d4)

    out = {
        "schema": src["schema"],
        "targetMc": new_target,
        "sources": src["sources"] + [f"derived from {src_target} via rules[{",".join(step_names)}], verified against mc-{new_target}"],
        "generated": datetime.date.today().isoformat(),
        "classes": classes,
        "members": members,
        "globalMethods": global_methods,
        "globalFields": global_fields,
        "mojangMethods": mojang_methods,
        "mojangMethodsGlobal": mojang_global,
        "yarnMethods": yarn_methods,
        "yarnFields": yarn_fields,
        "removedIntermediary": src["removedIntermediary"],
    }
    dest = RES / f"intermediary-to-mojang.{new_target}.json.gz"
    with gzip.open(dest, "wt") as fh:
        json.dump(out, fh, separators=(",", ":"))
    for k, (kept, dropped) in stats.items():
        print(f"  {k:9s} {kept} kept, {dropped} dropped as unverifiable on {new_target}")
    print(f"wrote {dest.name} ({dest.stat().st_size // 1024} KB)")
    pathlib.Path(f"/tmp/bridge-{new_target}-dropped-classes.txt").write_text("\n".join(gone) + "\n")


main()
