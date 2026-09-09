#!/usr/bin/env python3
"""Derive, for each pre-26 target, the fields whose TYPE changed since the source era.

A renamed member is the failure everyone expects and Fox-Grade already handles. A member that kept its name and
changed its type is quieter and just as fatal, because the JVM resolves a field by name *and* descriptor: a mod
compiled against 1.21.1 reads InteractionResult.PASS as `Lnet/minecraft/class_1269;`, 1.21.2 declares it as
`Lnet/minecraft/class_1269$class_9859;`, and the port dies on NoSuchFieldError having renamed nothing wrongly.
That one field takes out Architectury, REI and Trinkets between them.

Intermediary is the join key — it is the namespace these mods actually ship in and the one name that is stable
across versions — so this compares the source era's intermediary field descriptors against the target's and emits a
row wherever they differ. Fox-Grade already knows what to do with such a row: read the field at its new type and
cast back to the one the old code expects, which is sound precisely when the new type is a subtype of the old, and
that is what a class being split into a sealed hierarchy looks like from the outside.

Nothing is guessed. A field whose type changed to something unrelated is reported and not emitted.

Usage: gen-intermediary-fixups.py <targetVersion>...
Output: foxgrade-mod/src/main/resources/foxgrade/intermediary-fixups.json
"""
import collections, json, pathlib, re, sys, urllib.request, zipfile

W = pathlib.Path.home() / "foxgrade-work"
CACHE = W / "intermediary-cache"
OUT = W / "foxgrade-mod/src/main/resources/foxgrade/intermediary-fixups.json"
SOURCE = "1.21.1"          # the era the corpus is built for
UA = {"User-Agent": "foxgrade/1.1 (mod porter)"}
# (intermediary class, official field name) -> intermediary field name, filled while parsing a version.
OFFICIAL_FIELD = {}


def tiny(version):
    """The intermediary mappings for a version, fetched once and cached."""
    CACHE.mkdir(exist_ok=True)
    jar = CACHE / f"intermediary-{version}.jar"
    if not jar.exists():
        url = f"https://maven.fabricmc.net/net/fabricmc/intermediary/{version}/intermediary-{version}-v2.jar"
        req = urllib.request.Request(url, headers=UA)
        jar.write_bytes(urllib.request.urlopen(req, timeout=60).read())
    with zipfile.ZipFile(jar) as z:
        return z.read("mappings/mappings.tiny").decode("utf-8", "replace")


def parse(text):
    """(class off->inter, fields {interClass: {interField: officialDesc}})."""
    cls, fields = {}, collections.defaultdict(dict)
    off = inter = None
    for ln in text.split("\n"):
        p = ln.split("\t")
        if p and p[0] == "c":
            off, inter = p[1], p[2]; cls[off] = inter; continue
        if off is None or len(p) < 5 or p[1] != "f":
            continue
        fields[inter][p[4]] = p[2]
        OFFICIAL_FIELD[(inter, p[3])] = p[4]
    return cls, fields


def to_inter(desc, cls):
    """An official descriptor rewritten into intermediary, so two versions can be compared at all."""
    return re.sub(r"L([^;]+);", lambda m: "L" + cls.get(m.group(1), m.group(1)) + ";", desc)


# InteractionResult's own constants, which the InteractionResultHolder shim has to push. Named here in Mojang terms
# because that is how a human checks them; resolved to this target's intermediary name and descriptor below, so the
# shim is built from what the version actually declares rather than from anything remembered about it.
HOLDER_CONSTANTS = ["SUCCESS", "SUCCESS_SERVER", "CONSUME", "PASS", "FAIL"]


def proguard_fields(version):
    """(mojang class -> obf class, (obf class, mojang field) -> obf field) from the published Mojang mappings."""
    for name in (f"mappings-{version}.txt", f"mappings-{version}-client.txt"):
        f = W / name
        if f.exists():
            break
    else:
        return {}, {}
    moj2off, fields, cur = {}, {}, None
    for ln in f.read_text(encoding="utf-8", errors="replace").split("\n"):
        if ln.startswith("#"):
            continue
        if not ln.startswith("    "):
            m = re.match(r"(\S+) -> (\S+):", ln.strip())
            if m:
                cur = m.group(2); moj2off[m.group(1)] = cur
            continue
        m = re.match(r"(\S+) (\S+) -> (\S+)$", ln.strip())
        if m and "(" not in ln:
            fields[(cur, m.group(2))] = m.group(3)
    return moj2off, fields


def constants(target, tcls, tfields):
    """{Mojang constant name: "interName:interDescriptor"} for InteractionResult on this target."""
    moj2off, pfields = proguard_fields(target)
    if not moj2off:
        return {}
    off = moj2off.get("net.minecraft.world.InteractionResult")
    inter_owner = tcls.get(off)
    if not inter_owner:
        return {}
    # tiny keys fields by intermediary name, so walk back from the obfuscated one the proguard log gives us.
    by_off = {}
    for iname, desc in tfields.get(inter_owner, {}).items():
        by_off[iname] = desc
    out = {}
    for want in HOLDER_CONSTANTS:
        offf = pfields.get((off, want))
        if not offf:
            continue
        # The proguard log names the field in official terms; OFFICIAL_FIELD carries the pairing recorded while
        # the tiny file was parsed, which is the only place the two namespaces meet.
        hit = OFFICIAL_FIELD.get((inter_owner, offf))
        if hit and hit in by_off:
            out[want] = f"{hit}:{to_inter(by_off[hit], tcls)}"
    return out


def main():
    scls, sfields = parse(tiny(SOURCE))
    out = {}
    if OUT.exists():
        out = json.loads(OUT.read_text())
    for target in sys.argv[1:]:
        try:
            tcls, tfields = parse(tiny(target))
        except Exception as why:
            print(f"{target}: no intermediary mappings ({why})"); continue
        rows, unrelated = {}, []
        tnames = set(tcls.values())
        for c, fs in sfields.items():
            if c not in tfields:
                continue                                  # the class itself is gone: a shim question, not a retype
            for f, sdesc in fs.items():
                tdesc = tfields[c].get(f)
                if tdesc is None:
                    continue                              # the field is gone: unresolved, and the report names it
                si, ti = to_inter(sdesc, scls), to_inter(tdesc, tcls)
                if si == ti:
                    continue
                # Sound only when the new type is a subtype of the old, which for these splits shows up as the new
                # type being a nested class of the old one. Anything else is a real semantic change; say so, skip it.
                old_owner = si[1:-1] if si.startswith("L") else None
                if not (old_owner and ti.startswith("L" + old_owner + "$")):
                    unrelated.append(f"{c}.{f}: {si} -> {ti}")
                    continue
                rows.setdefault(c, {})[f"getstatic {f}:{si}"] = ["retype", ti, old_owner]
        out[target] = {"fieldRetypes": rows, "constants": constants(target, tcls, tfields)}
        n = sum(len(v) for v in rows.values())
        print(f"{target}: {n} field retypes in {len(rows)} classes, {len(unrelated)} reported not emitted")
        for u in unrelated[:5]:
            print(f"     not emitted: {u}")
    OUT.write_text(json.dumps(out, indent=1, sort_keys=True) + "\n")
    print(f"\nwrote {OUT}")


main()
