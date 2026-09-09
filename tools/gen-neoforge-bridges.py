#!/usr/bin/env python3
"""Build the NeoForge loader-API bridge table by diffing the source version's NeoForge against the target's.

Fabric and NeoForge fail differently, and this file exists because of that difference. Fabric's loader API barely
moves, so porting a Fabric mod is almost entirely a Minecraft problem. NeoForge ships the loader and the modding API
as one product and reshapes both between Minecraft versions, so a NeoForge mod breaks on NeoForge's own classes
before it ever reaches a Minecraft one. AppleSkin 3.0.9 is the plain case:

    NoSuchFieldError: FMLEnvironment does not have member field 'Dist dist'

`FMLEnvironment.dist` became `FMLEnvironment.getDist()`. Nothing about Minecraft changed; the accessor did.

What is emitted is deliberately narrow. Only two shapes are trusted without a human reading them:
  * a public static field that is gone, where the same class now has a no-argument static method returning that
    exact descriptor under a name derived from the field's (`dist` -> `getDist`, `production` -> `isProduction`);
  * a public method that is gone, where the same class has exactly one method left with an identical descriptor and
    a name derived the same way.
Both are same-class, same-descriptor, name-derived. A rename that needs a judgement call is reported, not emitted,
because a wrong redirect fails later and less legibly than an unresolved reference the port report can name.

Usage: gen-neoforge-bridges.py OLD_JAR[,OLD_JAR...] NEW_JAR[,NEW_JAR...]
Output: foxgrade-mod/src/main/resources/foxgrade/neoforge-api-bridges.json
"""
import json, pathlib, re, struct, sys, zipfile

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "foxgrade-mod/src/main/resources/foxgrade/neoforge-api-bridges.json"
ACC_PUBLIC, ACC_STATIC = 0x0001, 0x0008
# Only these package roots. NeoForge's jars also carry relocated third-party code that is not its API to promise.
API_ROOTS = ("net/neoforged/",)


def parse_class(data):
    """(internal name, {static fields}, {methods}) from a class file, without javap, which fails on large classes."""
    n = struct.unpack(">H", data[8:10])[0]
    utf, refs, i, k = {}, {}, 10, 1
    while k < n:
        t = data[i]
        if t == 1:
            L = struct.unpack(">H", data[i + 1:i + 3])[0]
            utf[k] = data[i + 3:i + 3 + L].decode("utf-8", "replace"); i += 3 + L
        elif t == 7:
            refs[k] = struct.unpack(">H", data[i + 1:i + 3])[0]; i += 3
        elif t in (8, 16, 19, 20): i += 3
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t == 15: i += 4
        else: raise ValueError(f"tag {t}")
        k += 1
    i += 2                                                     # access_flags
    this_class = struct.unpack(">H", data[i:i + 2])[0]; i += 4  # this_class + super_class
    name = utf.get(refs.get(this_class), "?")
    ifc = struct.unpack(">H", data[i:i + 2])[0]; i += 2 + 2 * ifc

    def members():
        nonlocal i
        out = {}
        cnt = struct.unpack(">H", data[i:i + 2])[0]; i += 2
        for _ in range(cnt):
            acc, ni, di, ac = struct.unpack(">HHHH", data[i:i + 8]); i += 8
            for _ in range(ac):
                L = struct.unpack(">I", data[i + 2:i + 6])[0]; i += 6 + L
            out[(utf.get(ni, "?"), utf.get(di, "?"))] = acc
        return out

    fields, methods = members(), members()
    statics = {k: a for k, a in fields.items() if a & ACC_PUBLIC and a & ACC_STATIC}
    return name, statics, {k: a for k, a in methods.items() if a & ACC_PUBLIC}


def surface(jars):
    api = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for e in z.namelist():
                if not e.endswith(".class") or not e.startswith(API_ROOTS):
                    continue
                try:
                    name, statics, methods = parse_class(z.read(e))
                except Exception:
                    continue
                cur = api.setdefault(name, ({}, {}))
                cur[0].update(statics); cur[1].update(methods)
    return api


def candidate_names(field):
    """The names a getter for `field` could plausibly have, most specific first."""
    cap = field[:1].upper() + field[1:]
    camel = "".join(p.capitalize() for p in field.lower().split("_")) if "_" in field else cap
    return [f"get{cap}", f"is{cap}", field, f"get{camel}", f"is{camel}", camel]


def target_classes():
    """Every class the target actually has: Minecraft 26.2 plus NeoForge's own. Used to reject an inferred
    substitution whose source class is still present (nothing to fix) or whose destination is not (a bad guess)."""
    import gzip
    p = HERE / "foxgrade-mod/src/main/resources/foxgrade/mc-26.2.classes.json.gz"
    return set(json.load(gzip.open(p))) if p.exists() else set()


def param_types(desc):
    """Internal names of the object types in a descriptor, parameters then return, in order; primitives skipped."""
    return [m.group(1) for m in re.finditer(r"L([^;]+);", desc)]


def erase(desc):
    """The descriptor with every object type replaced by a placeholder, so two signatures that differ only in which
    classes they name compare equal and can be aligned position by position."""
    return re.sub(r"L[^;]+;", "L;", desc)


def name_related(a, b):
    """Whether two simple names read as the same type renamed: one is a prefix of the other, or they are close enough
    that no other reading is sensible. Package is deliberately ignored — this is the test used to separate siblings
    that share a package, where package tells you nothing."""
    import difflib
    la = a.rsplit("/", 1)[-1].lower().rsplit("$", 1)[-1]
    lb = b.rsplit("/", 1)[-1].lower().rsplit("$", 1)[-1]
    if len(la) >= 3 and len(lb) >= 3 and (la.startswith(lb) or lb.startswith(la)):
        return True                                   # ItemHandler -> Item, EnergyStorage -> Energy
    return difflib.SequenceMatcher(None, la, lb).ratio() >= 0.7


def related(a, b):
    """Whether two internal names look like the same type before and after an API tidy-up: it stayed in its package,
    or the names themselves say so."""
    pa = a.rsplit("/", 1)[0] if "/" in a else ""
    pb = b.rsplit("/", 1)[0] if "/" in b else ""
    return pa == pb or name_related(a, b)


def infer_class_renames(old, new):
    """Type substitutions read off the API itself.

    When a class keeps a method under the same name, the same shape and the same arity across the two versions, the
    object types in the two descriptors line up position for position. Every position where they differ is one
    version's name for a thing and the next version's name for the same thing:

        registerAboveAll(ResourceLocation, LayeredDraw$Layer)V   ->   registerAboveAll(Identifier, GuiLayer)V

    reads as ResourceLocation -> Identifier and LayeredDraw$Layer -> GuiLayer. One such pair proves little; the same
    pair falling out of dozens of unrelated signatures is the API telling you what it renamed. A substitution is kept
    only when every alignment that mentions the old type agrees on the replacement, the old type is really gone from
    the target, and the replacement really exists there."""
    present = target_classes() | set(new)
    votes = {}
    for cls, (_, omethods) in old.items():
        if cls not in new:
            continue
        nmethods = new[cls][1]
        obyshape, nbyshape = {}, {}
        for (n, d) in omethods: obyshape.setdefault((n, erase(d)), []).append(d)
        for (n, d) in nmethods: nbyshape.setdefault((n, erase(d)), []).append(d)
        for shape, olds in obyshape.items():
            news = nbyshape.get(shape)
            # Exactly one candidate on each side, or the alignment is a guess about which overload became which.
            if not news or len(olds) != 1 or len(news) != 1:
                continue
            for a, b in zip(param_types(olds[0]), param_types(news[0])):
                if a != b:
                    votes.setdefault(a, {}).setdefault(b, 0)
                    votes[a][b] += 1
    out, rejected = {}, []
    for a, cand in sorted(votes.items()):
        b, n = max(cand.items(), key=lambda kv: kv[1])
        why = None
        if a.startswith(("java/", "javax/", "jdk/", "sun/")):
            # A JDK type never disappears. Seeing one on the left means a signature changed what it takes, not what
            # a class is called, and String -> ProcessorName would be a catastrophic thing to believe.
            why = "source is a JDK type"
        elif len(cand) != 1:
            why = "alignments disagree: " + ", ".join(f"{k}({v})" for k, v in sorted(cand.items(), key=lambda kv: -kv[1]))
        elif n < 2 and not related(a, b):
            # One alignment is thin evidence on its own, and one coincidence is all it takes to believe something
            # absurd — a lone alignment once claimed ModelBuilder became Identifier. Two independent signatures
            # agreeing is enough. So is one, when the two names are obviously the same thing: an API that moves a
            # class between packages or lengthens its name leaves exactly one alignment behind and is still a rename.
            why = "only one alignment, and the names are unrelated"
        elif a in present:
            why = "source still exists in the target"
        elif b not in present:
            why = "destination does not exist in the target"
        if why:
            rejected.append(f"{a} -> {b} ({n}) rejected: {why}")
        else:
            out[a] = b
    pathlib.Path("/tmp/nf-bridge-rejected.txt").write_text("\n".join(rejected) + "\n")
    return out


def match_removed_classes(old, new, already):
    """Successors for classes that are gone, found by what their members look like.

    Signature alignment can only speak about classes that survived — it reads renames off methods that exist on both
    sides. A class deleted outright leaves no such method to align, and it is the deletions that stop a mod dead:

        NoClassDefFoundError: net/neoforged/neoforge/client/event/CustomizeGuiOverlayEvent$DebugText

    What a deleted class does leave behind is its shape. If the target has a class carrying the same method names with
    the same arities, that is almost always the same type under a new name — a package move, a split, a rename the API
    made without telling anyone. Matching on shape finds those.

    The bar is deliberately high, because a wrong successor is worse than none: it turns a legible missing-class report
    into a mod that loads and then misbehaves. A class needs enough members to have a shape at all, the best candidate
    has to cover most of them, and it has to be clearly better than the runner-up. Ties are left for a human."""
    MIN_MEMBERS, MIN_COVER, MIN_MARGIN = 3, 0.7, 0.2
    boring = {("toString", "()Ljava/lang/String;"), ("hashCode", "()I"), ("equals", "(Ljava/lang/Object;)Z")}

    def shape(statics, methods):
        # Static fields count as members, not just methods. A class that is a bag of constants — NeoForge's
        # capability holders are the case that forced this — has almost no methods, so a methods-only shape says
        # nothing about it and its rename goes unseen. Descriptors are erased first so a field whose type was itself
        # renamed still lines up.
        out = {("#" + n, erase(d)) for (n, d) in statics}
        out |= {(n, erase(d)) for (n, d) in methods if n != "<init>" and (n, d) not in boring}
        return out

    new_shapes = {c: shape(m[0], m[1]) for c, m in new.items()}
    out, report = {}, []
    for cls, (ostatics, omethods) in sorted(old.items()):
        if cls in new or cls in already:
            continue
        want = shape(ostatics, omethods)
        if len(want) < MIN_MEMBERS:
            continue
        scored = sorted(((len(want & have) / len(want), c) for c, have in new_shapes.items() if have), reverse=True)
        if not scored or scored[0][0] < MIN_COVER:
            continue
        top = scored[0][0]
        tied = [c for score, c in scored if top - score < 1e-9]
        if len(tied) > 1:
            # Sibling classes can have identical shapes — NeoForge's capability holders are three bags of the same
            # three fields — and then only the names separate them. One related name breaks the tie; none or several
            # means this is not a call a generator should be making.
            byname = [c for c in tied if name_related(cls, c)]
            if len(byname) != 1:
                report.append(f"{cls} -> ambiguous at {top:.2f}: " + ", ".join(tied[:4]))
                continue
            out[cls] = byname[0]
            report.append(f"{cls} -> {byname[0]} (shape tied with {len(tied) - 1} other(s); the names decided)")
            continue
        runner_up = scored[1][0] if len(scored) > 1 else 0.0
        if top - runner_up < MIN_MARGIN:
            report.append(f"{cls} -> ambiguous: {scored[0][1]} ({top:.2f}) vs {scored[1][1]} ({runner_up:.2f})")
            continue
        out[cls] = scored[0][1]
        report.append(f"{cls} -> {scored[0][1]} (covers {top:.0%} of {len(want)} members)")
    pathlib.Path("/tmp/nf-bridge-successors.txt").write_text("\n".join(report) + "\n")
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    old = surface([pathlib.Path(p) for p in sys.argv[1].split(",")])
    new = surface([pathlib.Path(p) for p in sys.argv[2].split(",")])
    class_renames = infer_class_renames(old, new)
    class_renames.update(match_removed_classes(old, new, class_renames))
    field_redirects, renames, unresolved, removed_classes = {}, {}, [], []

    for cls, (ofields, omethods) in sorted(old.items()):
        if cls not in new:
            removed_classes.append(cls)
            continue
        nfields, nmethods = new[cls]
        for (fname, fdesc), _ in sorted(ofields.items()):
            if (fname, fdesc) in nfields:
                continue
            getter = next((c for c in candidate_names(fname)
                           if (c, f"(){fdesc}") in nmethods and nmethods[(c, f"(){fdesc}")] & ACC_STATIC), None)
            if getter:
                field_redirects.setdefault(cls, {})[f"getstatic {fname}:{fdesc}"] = [cls, getter, f"(){fdesc}"]
            else:
                unresolved.append(f"field {cls}.{fname}:{fdesc}")
        for (mname, mdesc), _ in sorted(omethods.items()):
            if (mname, mdesc) in nmethods or mname == "<init>":
                continue
            was_static = omethods[(mname, mdesc)] & ACC_STATIC
            # Static-ness has to match. FMLLoader.versionInfo() was static and getVersionInfo() is not, and a rename
            # that ignored that would turn a working call into a verify error instead of a legible missing reference.
            same = [c for (c, d) in nmethods if d == mdesc and c in candidate_names(mname)
                    and bool(nmethods[(c, d)] & ACC_STATIC) == bool(was_static)]
            if len(same) == 1:
                renames.setdefault(cls, {})[mname + mdesc] = same[0]
            else:
                unresolved.append(f"method {cls}.{mname}{mdesc}")

    table = {
        "_comment": ("NeoForge's own loader/API surface, source version -> target. Generated by gen-neoforge-bridges.py "
                     "from the two NeoForge releases; only same-class, same-descriptor, name-derived matches are "
                     "emitted. Anything needing a judgement call is left out and shows up in the port report as an "
                     "unresolved reference."),
        "classRenames": class_renames,
        "fieldRedirects": field_redirects,
        "renames": renames,
    }
    OUT.write_text(json.dumps(table, indent=2) + "\n")
    print(f"{len(class_renames)} type substitutions inferred from signature alignment")
    nf = sum(len(v) for v in field_redirects.values())
    nr = sum(len(v) for v in renames.values())
    print(f"{len(old)} API classes in the source version, {len(new)} in the target")
    print(f"{nf} field->accessor redirects across {len(field_redirects)} classes")
    print(f"{nr} method renames across {len(renames)} classes")
    print(f"{len(removed_classes)} classes gone entirely, {len(unresolved)} members left for a human")
    pathlib.Path("/tmp/nf-bridge-unresolved.txt").write_text(
        "\n".join(["== classes removed =="] + removed_classes + ["", "== members unmatched =="] + unresolved) + "\n")
    print("detail: /tmp/nf-bridge-unresolved.txt")


main()
