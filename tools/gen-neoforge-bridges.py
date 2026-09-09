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

# class -> (superclass, interfaces), filled in as classes are parsed; used to build stand-ins for deleted types.
HIERARCHY = {}


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
    this_class, super_class = struct.unpack(">HH", data[i:i + 4]); i += 4
    name = utf.get(refs.get(this_class), "?")
    supername = utf.get(refs.get(super_class)) if super_class else None
    ifc = struct.unpack(">H", data[i:i + 2])[0]
    interfaces = [utf.get(refs.get(x)) for x in struct.unpack(f">{ifc}H", data[i + 2:i + 2 + 2 * ifc])] if ifc else []
    i += 2 + 2 * ifc

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
    pub = {k: a for k, a in methods.items() if a & ACC_PUBLIC}
    HIERARCHY[name] = (supername, [x for x in interfaces if x])
    return name, statics, pub


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


# Renames a person checked against both APIs and the generator will not infer, because inferring them would mean
# trusting name similarity between siblings — the one thing that produces confident nonsense. Each entry here was
# read off both versions: the old class is gone, the new one does the same job.
CURATED = {
    # 1.21.1 registered client reload listeners; 26.2 adds them. Same event, renamed verb.
    "net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent":
        "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent",
    # The server-side counterpart, which 26.2 renamed to say which side it is on.
    "net/neoforged/neoforge/event/AddReloadListenerEvent":
        "net/neoforged/neoforge/event/AddServerReloadListenersEvent",
    # 26.2 promoted the nested block events out to their own package and put the verb first.
    "net/neoforged/neoforge/event/level/BlockEvent$BreakEvent":
        "net/neoforged/neoforge/event/level/block/BreakBlockEvent",
    # The container-screen events folded into the general screen events, keeping their nested shape.
    "net/neoforged/neoforge/client/event/ContainerScreenEvent":
        "net/neoforged/neoforge/client/event/ScreenEvent",
    "net/neoforged/neoforge/client/event/ContainerScreenEvent$Render":
        "net/neoforged/neoforge/client/event/ScreenEvent$Render",
    # Same event, renamed from what the client did to what it was given.
    "net/neoforged/neoforge/client/event/RecipesUpdatedEvent":
        "net/neoforged/neoforge/client/event/RecipesReceivedEvent",
}

# Static calls that stopped being static. FML 11 moved most of FMLLoader's statics onto an instance reached through
# getCurrent(), which is not a rename and not a field, so nothing above can see it — a mod calling
# FMLLoader.isProduction() simply gets "Expected static method". Only redirects to a method that is still static, on
# the target, with the identical signature are listed; the rest of FMLLoader's old statics have no static equivalent
# and are left to the port report to name.
CURATED_CALLS = {
    # Registering a reload listener gained a name in 26.2, which sorts listeners into a dependency graph. There is no
    # id in the old call to carry across, so the shim makes one. Keyed on the *new* owner because the class rename
    # above has already been applied by the time a call is redirected.
    "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent": {
        "registerReloadListener(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V":
            ["foxgrade/shim/ReloadListenerCompat", "registerReloadListener",
             "(Ljava/lang/Object;Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V"],
    },
    "net/neoforged/neoforge/event/AddServerReloadListenersEvent": {
        "registerReloadListener(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V":
            ["foxgrade/shim/ReloadListenerCompat", "registerReloadListener",
             "(Ljava/lang/Object;Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V"],
    },
    # Removed outright in FML 11, with nothing on that interface to replace it.
    "net/neoforged/neoforgespi/language/IModFileInfo": {
        "moduleName()Ljava/lang/String;":
            ["foxgrade/shim/FmlCompat", "moduleName", "(Ljava/lang/Object;)Ljava/lang/String;"],
    },
    "net/neoforged/fml/loading/FMLLoader": {
        "isProduction()Z": ["net/neoforged/fml/loading/FMLEnvironment", "isProduction", "()Z"],
        "getDist()Lnet/neoforged/api/distmarker/Dist;":
            ["net/neoforged/fml/loading/FMLEnvironment", "getDist", "()Lnet/neoforged/api/distmarker/Dist;"],
        "getLoadingModList()Lnet/neoforged/fml/loading/LoadingModList;":
            ["net/neoforged/fml/loading/LoadingModList", "get", "()Lnet/neoforged/fml/loading/LoadingModList;"],
        # No static equivalent for this one: the game directory hangs off the loader instance now, so it needs a shim.
        "getGamePath()Ljava/nio/file/Path;":
            ["foxgrade/shim/FmlCompat", "getGamePath", "()Ljava/nio/file/Path;"],
    },
}


def target_classes():
    """Every class the target actually has: Minecraft 26.2 plus NeoForge's own. Used to reject an inferred
    substitution whose source class is still present (nothing to fix) or whose destination is not (a bad guess)."""
    import gzip
    p = HERE / "foxgrade-mod/src/main/resources/foxgrade/mc-26.2.classes.json.gz"
    return set(json.load(gzip.open(p))) if p.exists() else set()


def library_classes(new_jars):
    """Everything else on the target's launch classpath. The event bus, the dist markers, Brigadier and DataFixerUpper
    all ship as their own jars, and a supertype living in one of them is no less present for that — without this,
    every event whose parent is net/neoforged/bus/api/Event looks unsupportable.

    The launcher's library tree also holds the *source* version's NeoForge, installed alongside for its own profile.
    Those jars are excluded by version: counting them as present would declare the classes this port exists to fix
    already fine."""
    lib = pathlib.Path.home() / "Library/Application Support/minecraft/libraries"
    if not lib.is_dir():
        return set()
    keep_versions = {j.parent.name for j in new_jars}
    out = set()
    for jar in lib.rglob("*.jar"):
        parts = str(jar)
        if ("/neoforge/" in parts or "/fancymodloader/" in parts) and jar.parent.name not in keep_versions:
            continue
        try:
            with zipfile.ZipFile(jar) as z:
                out |= {e[:-6] for e in z.namelist() if e.endswith(".class")}
        except Exception:
            pass
    return out


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
    # The whole simple name, nesting included. Comparing only the last $ segment reads
    # RenderHighlightEvent$Block as "block" and happily matches it to RegisterColorHandlersEvent$BlockTintSources,
    # which is two unrelated events sharing a word.
    la = a.rsplit("/", 1)[-1].lower()
    lb = b.rsplit("/", 1)[-1].lower()
    # Anonymous inner classes are never part of an API and their names carry no information at all.
    if re.search(r"\$\d", la) or re.search(r"\$\d", lb):
        return False
    # A nested class is not a rename of the class it is nested in, however well the names match. Without this,
    # "starts with" reads NeoForgeConfig$Client as a rename of NeoForge, and ClientHooks$ClientEvents as one of
    # ClientHooks. Equal nesting depth is the cheap way to say a rename keeps a type where it was.
    if la.count("$") != lb.count("$"):
        return False
    # Sidedness is meaning, not spelling. ClientPayloadHandler and ServerPayloadHandler are near-identical names for
    # opposite things, and a client class losing its Client is a different class, not a renamed one.
    # A client class and a server class are never each other, whatever the spelling. Gaining a side is different:
    # AddReloadListenerEvent became AddServerReloadListenersEvent by saying out loud which side it was always on.
    if ("client" in la) != ("client" in lb) and ("server" in la) != ("server" in lb):
        return False
    return name_score(la, lb) == 1.0


def name_score(la, lb):
    """How strongly two simple names read as the same type. One name containing the other whole — ItemHandler over
    Item, ContainerScreenEvent$Render over ScreenEvent$Render — is the strongest signal there is and outranks any
    amount of incidental overlap, which matters because sibling classes under one outer class overlap heavily by
    construction: RegisterColorHandlersEvent$Item resembles $BlockTintSources almost as much as $ItemTintSources."""
    import difflib
    # Containment, but the shorter name has to be most of the longer one. Without that floor, every class whose name
    # starts with the mod's own prefix contains every other: NeoForgeConfig "contains" NeoForge, which is a config
    # holder being mistaken for the mod class.
    if (len(la) >= 4 and len(lb) >= 4 and min(len(la), len(lb)) / max(len(la), len(lb)) >= 0.6
            and (la.startswith(lb) or lb.startswith(la) or la.endswith(lb) or lb.endswith(la))):
        return 1.0
    return difflib.SequenceMatcher(None, la, lb).ratio()


def related(a, b):
    """Whether two internal names look like the same type before and after an API tidy-up.

    Looser than {@code name_related} on purpose. That one separates siblings inside a package and has to insist a
    rename keeps a type where it was; this one backs a rename the signatures already voted for, where an API is just
    as likely to have promoted a nested class out to its own file while moving it down a package:
    BlockEvent$BreakEvent became block/BreakBlockEvent. Sidedness still holds — client and server are never each
    other, however the packages move."""
    la_, lb_ = a.lower(), b.lower()
    if ("client" in la_) != ("client" in lb_) and ("server" in la_) != ("server" in lb_):
        return False
    pa = a.rsplit("/", 1)[0] if "/" in a else ""
    pb = b.rsplit("/", 1)[0] if "/" in b else ""
    if pa == pb or name_related(a, b):
        return True
    inner_a = a.rsplit("/", 1)[-1].lower().rsplit("$", 1)[-1]
    inner_b = b.rsplit("/", 1)[-1].lower().rsplit("$", 1)[-1]
    return len(inner_a) >= 4 and len(inner_b) >= 4 and name_score(inner_a, inner_b) == 1.0


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

    def by_name(cls, why):
        """Fall back to the names when the members cannot decide. Same package, and one candidate clearly ahead of
        the next — a near-tie among siblings is not an answer, it is three classes that look alike."""
        pkg = cls.rsplit("/", 1)[0]
        near = sorted(((name_score(cls.rsplit("/", 1)[-1].lower(), c.rsplit("/", 1)[-1].lower()), c)
                       for c in new if c.rsplit("/", 1)[0] == pkg and name_related(cls, c)), reverse=True)
        if near and (len(near) == 1 or near[0][0] - near[1][0] >= 0.05):
            out[cls] = near[0][1]
            report.append(f"{cls} -> {near[0][1]} ({why}; best same-package name at {near[0][0]:.2f})")
            return True
        if near:
            report.append(f"{cls} -> ambiguous by name: " + ", ".join(f"{c}({sc:.2f})" for sc, c in near[:3]))
        return False
    for cls, (ostatics, omethods) in sorted(old.items()):
        if cls in new or cls in already:
            continue
        want = shape(ostatics, omethods)
        if len(want) < MIN_MEMBERS:
            # Too few members to have a shape worth matching. Events are the common case — a handful of them carry
            # one accessor and nothing else — and they are also where an API renames most freely. When a class like
            # that has exactly one same-package neighbour whose name reads as the same thing, the name is the
            # evidence: RegisterClientReloadListenersEvent -> AddClientReloadListenersEvent, and
            # RegisterColorHandlersEvent$Item -> $ItemTintSources. More than one neighbour and it stays unmatched.
            if not by_name(cls, "too few members to match on shape"):
                pass
            continue
        scored = sorted(((len(want & have) / len(want), c) for c, have in new_shapes.items() if have), reverse=True)
        if not scored or scored[0][0] < MIN_COVER:
            # Having members is no guarantee they survived. RegisterColorHandlersEvent$Item became $ItemTintSources
            # and kept not one method, so its shape says nothing while its name says everything.
            by_name(cls, f"shape matched nothing above {MIN_COVER:.0%}")
            continue
        top = scored[0][0]
        tied = [c for score, c in scored if top - score < 1e-9]
        if len(tied) > 1:
            # Sibling classes can have identical shapes — NeoForge's capability holders are three bags of the same
            # three fields — and then only the names separate them. One related name breaks the tie; none or several
            # means this is not a call a generator should be making.
            byname = sorted(((name_score(cls.rsplit("/", 1)[-1].lower(), c.rsplit("/", 1)[-1].lower()), c)
                             for c in tied if name_related(cls, c)), reverse=True)
            if not byname or (len(byname) > 1 and byname[0][0] - byname[1][0] < 0.05):
                report.append(f"{cls} -> ambiguous at {top:.2f}: " + ", ".join(tied[:4]))
                continue
            out[cls] = byname[0][1]
            report.append(f"{cls} -> {byname[0][1]} (shape tied with {len(tied) - 1} other(s); names decided at {byname[0][0]:.2f})")
            continue
        runner_up = scored[1][0] if len(scored) > 1 else 0.0
        if top - runner_up < MIN_MARGIN:
            report.append(f"{cls} -> ambiguous: {scored[0][1]} ({top:.2f}) vs {scored[1][1]} ({runner_up:.2f})")
            continue
        out[cls] = scored[0][1]
        report.append(f"{cls} -> {scored[0][1]} (covers {top:.0%} of {len(want)} members)")
    pathlib.Path("/tmp/nf-bridge-successors.txt").write_text("\n".join(report) + "\n")
    return out


def build_stand_ins(old, new, renamed, present):
    """Descriptors for deleted API types that a mod can still be compiled against.

    Some classes an API deletes have no successor at all. NeoForge's CustomizeGuiOverlayEvent$DebugText is one: 26.2
    replaced "hand the handler two lists of strings" with "register debug entries", which is a different idea, not a
    renamed class. No rename can be honest about that, and the mod dies before it starts — not inside the feature that
    was removed, but at class load, because the loader reflects over the mod looking for its event handlers and one
    parameter type will not resolve.

    A stand-in is the smallest thing that lets the rest of the mod run: an empty class under the deleted name,
    extending whatever the deleted class extended, if that supertype still exists. The type resolves, the loader
    finishes reading the class, and the handler is simply never called, because nothing in the game posts an event of
    a type the game no longer has. The mod comes up with one feature inert and the port report says which.

    Only classes whose supertype survives get one. Without a real supertype the stand-in would not be the deleted
    type in any sense the loader cares about — an event bus checks that what you register extends Event — and an
    empty class pretending otherwise is the kind of thing that loads and then misbehaves."""
    out = {}
    for cls, (_, methods) in sorted(old.items()):
        if cls in new or cls in renamed:
            continue
        sup, ifaces = HIERARCHY.get(cls, (None, []))
        if not sup:
            continue
        sup = renamed.get(sup, sup)
        if sup not in present:
            continue                                   # no surviving supertype: a stand-in would be a lie
        keep_ifaces = [renamed.get(i, i) for i in ifaces]
        keep_ifaces = [i for i in keep_ifaces if i in present]
        # Only methods every type in the signature still resolves to; one that mentions another deleted class would
        # stop the stand-in itself from loading, which defeats the point.
        sigs = []
        for (mname, mdesc) in sorted(methods):
            if mname == "<init>":
                continue
            mapped = mdesc
            for t in set(param_types(mdesc)):
                mapped = mapped.replace(f"L{t};", f"L{renamed.get(t, t)};")
            if all(t in present for t in param_types(mapped)):
                sigs.append(mname + mapped)
        out[cls] = {"super": sup, "interfaces": keep_ifaces, "methods": sigs}
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    old = surface([pathlib.Path(p) for p in sys.argv[1].split(",")])
    new = surface([pathlib.Path(p) for p in sys.argv[2].split(",")])
    class_renames = infer_class_renames(old, new)
    class_renames.update(match_removed_classes(old, new, class_renames))
    class_renames.update(CURATED)                       # hand-verified entries always win over an inference
    new_jars = [pathlib.Path(x) for x in sys.argv[2].split(",")]
    stand_ins = build_stand_ins(old, new, class_renames, target_classes() | set(new) | library_classes(new_jars))
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
        "callRedirects": CURATED_CALLS,
        "standIns": stand_ins,
        "fieldRedirects": field_redirects,
        "renames": renames,
    }
    OUT.write_text(json.dumps(table, indent=2) + "\n")
    print(f"{len(class_renames)} type substitutions inferred from signature alignment")
    print(f"{len(stand_ins)} stand-ins for deleted types whose supertype survives")
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
