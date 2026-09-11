#!/usr/bin/env python3
"""Assertions about ported jars, checked without launching Minecraft.

A harness lane costs half an hour and needs a machine nobody else is using; every fault found today showed up in the
port itself and could have been caught in seconds. This ports the corpora through the real pipeline and asserts what
should be true of the output — structural soundness, determinism, and the specific rewrites that fix known mods.

It is not a replacement for running the game. A port can be perfectly well formed and still not boot. It is the
layer below that: the things that are never acceptable, checked cheaply enough to run after every build.

Usage: regress.py [--quick]
Exit 0 = all assertions hold.
"""
import glob, hashlib, json, os, pathlib, re, shutil, struct, subprocess, sys, tempfile, zipfile

W = pathlib.Path.home() / "foxgrade-work"
JAR = sorted([g for g in glob.glob(str(W / "foxgrade-mod/dist/foxgrade-*.jar")) if "-measure" not in g], key=os.path.getmtime)[-1]
MCLIB = pathlib.Path.home() / "Library/Application Support/minecraft/libraries"
MAX_MAJOR = 65


def classpath():
    asm = sorted(glob.glob(str(MCLIB / "org/ow2/asm/asm/*/asm-*.jar")))[-1]
    extra = [sorted(glob.glob(str(MCLIB / f"org/ow2/asm/asm-{k}/*/asm-{k}-*.jar")))[-1]
             for k in ("tree", "commons", "analysis")]
    gson = sorted(glob.glob(str(MCLIB / "com/google/code/gson/gson/*/gson-*.jar")))[-1]
    # Fabric API modules too. Without them every Fabric API class a mod touches counts as unresolved and the suite
    # measures its own classpath instead of the port — the mistake that made a corpus look four times worse than it
    # is. Extracted once from the umbrella jar, whose modules live in META-INF/jars.
    return ":".join([JAR, gson, asm] + extra + fabric_modules())


def fabric_modules():
    import io, zipfile
    out = W / "regress-fabric-modules"
    if not out.is_dir() or not list(out.glob("*.jar")):
        out.mkdir(exist_ok=True)
        umbrella = sorted(glob.glob(str(W / "batch121/base/fabric-api-*.jar")))
        if not umbrella:
            return []
        with zipfile.ZipFile(umbrella[-1]) as z:
            for e in z.namelist():
                if e.startswith("META-INF/jars/") and e.endswith(".jar"):
                    (out / e.split("/")[-1]).write_bytes(z.read(e))
    return [str(p) for p in sorted(out.glob("*.jar"))]


def port(jars, loader=None, out=None, target="26.2"):
    """Run the real pipeline over these jars; returns (outdir, stderr)."""
    out = out or tempfile.mkdtemp(prefix="fg-regress-")
    game = tempfile.mkdtemp(prefix="fg-game-")
    cmd = ["java"]
    if loader:
        cmd.append(f"-Dfoxgrade.loader={loader}")
    cmd += ["-cp", classpath(), "foxgrade.CheckMain", target, game, "--out", out] + [str(j) for j in jars]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    shutil.rmtree(game, ignore_errors=True)
    return out, r.stdout + r.stderr


def class_refs(data):
    """(owner, name) for every method/field reference in a class file."""
    n, i, k, raw = struct.unpack(">H", data[8:10])[0], 10, 1, {}
    while k < n and i < len(data):
        t = data[i]
        if t == 1:
            L = struct.unpack(">H", data[i + 1:i + 3])[0]
            raw[k] = ("utf", data[i + 3:i + 3 + L].decode("utf-8", "replace")); i += 3 + L
        elif t == 7: raw[k] = ("cls", struct.unpack(">H", data[i + 1:i + 3])[0]); i += 3
        elif t == 12: raw[k] = ("nat", struct.unpack(">HH", data[i + 1:i + 5])); i += 5
        elif t in (9, 10, 11): raw[k] = ("ref", struct.unpack(">HH", data[i + 1:i + 5])); i += 5
        elif t in (8, 16, 19, 20): i += 3
        elif t in (3, 4, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t == 15: i += 4
        else: return
        k += 1
    def utf(x):
        return raw.get(x, ("utf", ""))[1]
    for kind, v in raw.values():
        if kind != "ref":
            continue
        ci, nti = v
        if raw.get(ci, ("", ""))[0] != "cls" or raw.get(nti, ("", ""))[0] != "nat":
            continue
        yield utf(raw[ci][1]), utf(raw[nti][1][0])


GSON_280 = str(MCLIB / "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar")


def _declared(jarpath):
    """owner -> ({name+desc}, superclass) for every class in a jar."""
    import zipfile
    out = {}
    with zipfile.ZipFile(jarpath) as z:
        for e in z.namelist():
            if not e.endswith(".class"):
                continue
            d = z.read(e)
            names, i, k, n = {}, 10, 1, struct.unpack(">H", d[8:10])[0]
            cls_ref = {}
            while k < n and i < len(d):
                t = d[i]
                if t == 1:
                    L = struct.unpack(">H", d[i + 1:i + 3])[0]
                    names[k] = d[i + 3:i + 3 + L].decode("utf-8", "replace"); i += 3 + L
                elif t == 7:
                    cls_ref[k] = struct.unpack(">H", d[i + 1:i + 3])[0]; i += 3
                elif t in (8, 16, 19, 20): i += 3
                elif t == 15: i += 4
                elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
                elif t in (5, 6): i += 9; k += 1
                else: break
                k += 1
            i += 2                                   # access
            i += 2                                   # this
            sup_idx = struct.unpack(">H", d[i:i + 2])[0]; i += 2
            sup = names.get(cls_ref.get(sup_idx), "java/lang/Object")
            i += 2 + 2 * struct.unpack(">H", d[i:i + 2])[0]
            members = set()
            for _ in range(2):
                cnt = struct.unpack(">H", d[i:i + 2])[0]; i += 2
                for _ in range(cnt):
                    acc, nm, ds = struct.unpack(">HHH", d[i:i + 6]); i += 6
                    att = struct.unpack(">H", d[i:i + 2])[0]; i += 2
                    for _ in range(att):
                        al = struct.unpack(">I", d[i + 2:i + 6])[0]; i += 6 + al
                    members.add(names.get(nm, "?") + names.get(ds, "?"))
            out[e[:-6]] = (members, sup)
    return out


def gson_too_new():
    """Gson calls Fox-Grade makes that Gson 2.8.0 cannot answer, inheritance included."""
    import zipfile
    if not os.path.exists(GSON_280):
        return []
    have = _declared(GSON_280)

    def resolves(owner, sig, depth=0):
        # Anything not in the jar (Object, Iterable, …) is a JDK type and fine.
        if owner not in have or depth > 8:
            return True
        members, sup = have[owner]
        return sig in members or resolves(sup, sig, depth + 1)

    bad = set()
    with zipfile.ZipFile(JAR) as z:
        for e in z.namelist():
            if not e.endswith(".class") or not e.startswith("foxgrade/"):
                continue
            # Forge-family hosts only ever run on 26.x-era loaders, which carry a modern Gson.
            if "ForgeHost" in e:
                continue
            try:
                for owner, name, desc in class_refs_full(z.read(e)):
                    if owner.startswith("com/google/gson/") and not resolves(owner, name + desc):
                        bad.add(f"{e[:-6]} calls {owner.split('/')[-1]}.{name}")
            except Exception:
                pass
    return sorted(bad)


def class_refs_full(data):
    """(owner, name, descriptor) for every method/field reference in a class file."""
    n, i, k, raw = struct.unpack(">H", data[8:10])[0], 10, 1, {}
    while k < n and i < len(data):
        t = data[i]
        if t == 1:
            L = struct.unpack(">H", data[i + 1:i + 3])[0]
            raw[k] = ("utf", data[i + 3:i + 3 + L].decode("utf-8", "replace")); i += 3 + L
        elif t == 7: raw[k] = ("cls", struct.unpack(">H", data[i + 1:i + 3])[0]); i += 3
        elif t == 12: raw[k] = ("nat", struct.unpack(">HH", data[i + 1:i + 5])); i += 5
        elif t in (9, 10, 11): raw[k] = ("ref", struct.unpack(">HH", data[i + 1:i + 5])); i += 5
        elif t in (8, 16, 19, 20): i += 3
        elif t in (3, 4, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t == 15: i += 4
        else: return
        k += 1
    def utf(x):
        return raw.get(x, ("utf", ""))[1]
    for kind, v in raw.values():
        if kind != "ref":
            continue
        ci, nti = v
        if raw.get(ci, ("", ""))[0] != "cls" or raw.get(nti, ("", ""))[0] != "nat":
            continue
        nt = raw[nti][1]
        yield utf(raw[ci][1]), utf(nt[0]), utf(nt[1])


FAILURES = []


def check(name, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def entries(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n) for n in z.namelist()}


def pool_strings(d):
    out, n, i, k = [], struct.unpack(">H", d[8:10])[0], 10, 1
    while k < n and i < len(d):
        t = d[i]
        if t == 1:
            L = struct.unpack(">H", d[i + 1:i + 3])[0]
            out.append(d[i + 3:i + 3 + L].decode("utf-8", "replace")); i += 3 + L
        elif t in (7, 8, 16, 19, 20): i += 3
        elif t == 15: i += 4
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        else: return out
        k += 1
    return out


def structural(outdir, label):
    for jar in sorted(glob.glob(f"{outdir}/*.jar")):
        es = entries(jar)
        names = {n[:-6] for n in es if n.endswith(".class")}
        too_new, dangling = [], set()
        for n, b in es.items():
            if not n.endswith(".class") or len(b) < 8 or b[:2] != b"\xca\xfe":
                continue
            # Multi-release payload: only read by a JVM at least that new, inert on anything older. See PortAudit.
            if n.startswith("META-INF/versions/"):
                continue
            if ((b[6] << 8) | b[7]) > MAX_MAJOR:
                too_new.append(n)
            for s in pool_strings(b):
                if s.startswith("foxgrade/shim/") and not s.endswith(";") and s not in names:
                    dangling.add(s)
        base = pathlib.Path(jar).name[:38]
        check(f"[{label}] {base}: no class newer than Java 21", not too_new, str(too_new[:2]))
        check(f"[{label}] {base}: no dangling shim reference", not dangling, str(sorted(dangling)[:2]))


def main():
    quick = "--quick" in sys.argv
    print(f"Fox-Grade regression suite  ({pathlib.Path(JAR).name})\n")

    fab = sorted(glob.glob(str(W / "batch2/h2h/*.jar")))[: 4 if quick else None]
    nf = sorted(glob.glob(str(W / "nf-corpus/*.jar")))[: 4 if quick else 12]

    print("Fabric corpus")
    out_a, log_a = port(fab)
    check("[fabric] pipeline reported no audit findings", "audit:" not in log_a,
          log_a[log_a.find("audit:"):].split("\n")[0] if "audit:" in log_a else "")
    structural(out_a, "fabric")

    print("\nNeoForge corpus")
    out_n, log_n = port(nf, loader="neoforge")
    check("[neoforge] pipeline reported no audit findings", "audit:" not in log_n,
          log_n[log_n.find("audit:"):].split("\n")[0] if "audit:" in log_n else "")
    structural(out_n, "neoforge")

    print("\nIntermediary targets")
    inter = sorted(glob.glob(str(W / "era-corpus/1.20.1/*.jar")))[: 4 if quick else 10]
    if inter:
        out_i, _ = port(inter, target="1.21.1")
        offenders = []
        for jar in sorted(glob.glob(out_i + "/*.jar")):
            with zipfile.ZipFile(jar) as z:
                for n in z.namelist():
                    if not n.startswith("foxgrade/shim/") or not n.endswith(".class"):
                        continue
                    moj = {m.decode() for m in re.findall(rb"net/minecraft/[a-zA-Z0-9_/$]+", z.read(n))
                           if b"/class_" not in m}
                    if moj:
                        offenders.append(f"{pathlib.Path(jar).name}:{n.split('/')[-1][:-6]} -> {sorted(moj)[0]}")
        check("no Mojang-named shim in a port built for an intermediary target", not offenders,
              "; ".join(offenders[:2]))

    print("\nDocumented coverage")
    # Every version claim a reader acts on. Writing this section by hand put 1.20 and 1.20.3 on the Modrinth page
    # as supported when they are only family entries -- a user on 1.20 would install Fox-Grade and be refused.
    # The working source, which is what the jar under test was built from. Reading the repo's synced copy meant
    # the check compared the docs against whatever Targets looked like at the last sync, so it failed on versions
    # that had just been added and passed on ones that had just been removed.
    targets = (W / "foxgrade-mod/src/main/java/foxgrade/Targets.java").read_text()
    supported = set(re.findall(r'"([^"]+)"', re.search(r"SUPPORTED\s*=\s*Set\.of\(([^)]*)\)", targets, re.S).group(1)))
    doc = (W / "repo/docs/versions.md").read_text()
    listed = {row.split("|")[1].strip() for row in doc.splitlines()
              if row.startswith("|") and re.match(r"^\|\s*(\d|26)", row)}
    listed = {v for v in listed if re.fullmatch(r"[\d.]+", v)}
    check("docs/versions.md lists no version Targets refuses",
          listed <= supported, f"documented but unsupported: {sorted(listed - supported)}")

    print("\nDeterminism")
    out_b, _ = port(fab[:2])
    out_c, _ = port(fab[:2])
    for a in sorted(glob.glob(f"{out_b}/*.jar")):
        b = pathlib.Path(out_c) / pathlib.Path(a).name
        same = b.exists() and hashlib.sha256(pathlib.Path(a).read_bytes()).digest() == hashlib.sha256(b.read_bytes()).digest()
        check(f"[determinism] {pathlib.Path(a).name[:38]} byte-identical across two ports", same)

    print("\nOld-Gson safety")
    # Minecraft ships its own Gson and old versions ship an old one. The oldest currently supported target
    # carries 2.10, so 2.8.0 is a floor held deliberately rather than the version in play: the pre-1.20 targets
    # being measured need it, and holding it now means adding one of them is not a fresh round of this. Three
    # separate crashes today
    # were Fox-Grade calling Gson methods that did not exist yet (JsonParser.parseString, JsonObject.keySet,
    # JsonArray.isEmpty), each landing in preLaunch where the porter takes the game down before it can report
    # anything. A hand-written list of banned names missed two of the three, so this asks the real 2.8.0 jar what it
    # declares instead of guessing — and walks superclasses, because JsonArray.forEach comes from Iterable and
    # JsonObject.toString from Object, and calling those is perfectly fine.
    offenders = gson_too_new()
    check("no Gson call newer than the 2.8.0 floor", not offenders, "; ".join(offenders[:3]))

    print("\nKnown rewrites")
    def refs(outdir, needle, jarpart):
        for jar in glob.glob(f"{outdir}/*.jar"):
            if jarpart not in pathlib.Path(jar).name:
                continue
            for n, b in entries(jar).items():
                if n.endswith(".class") and needle in pool_strings(b):
                    return True
        return False
    xa = sorted(glob.glob(str(W / "nf-corpus/xaeros-minimap*.jar")))
    if xa:
        out_x, _ = port(xa, loader="neoforge")
        check("NBT getAsInt rewritten to intValue (xaeros)", refs(out_x, "intValue", "xaeros"))
        check("no getAsInt left on an NBT owner (xaeros)", not refs(out_x, "getAsInt", "xaeros"))
        shutil.rmtree(out_x, ignore_errors=True)
    pz = sorted(glob.glob(str(W / "nf-corpus/puzzles-lib*.jar")))
    if pz:
        out_p, _ = port(pz, loader="neoforge")
        check("PlayerRenderer rewritten to AvatarRenderer (puzzles-lib)",
              refs(out_p, "net/minecraft/client/renderer/entity/player/AvatarRenderer", "puzzles"))
        shutil.rmtree(out_p, ignore_errors=True)

    for d in (out_a, out_n, out_b, out_c):
        shutil.rmtree(d, ignore_errors=True)
    print()
    if FAILURES:
        print(f"{len(FAILURES)} assertion(s) failed:")
        for f in FAILURES:
            print("   ", f)
        sys.exit(1)
    print("all assertions hold")


if __name__ == "__main__":
    main()
