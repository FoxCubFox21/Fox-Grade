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
import glob, hashlib, json, os, pathlib, shutil, struct, subprocess, sys, tempfile, zipfile

W = pathlib.Path.home() / "foxgrade-work"
JAR = sorted(glob.glob(str(W / "foxgrade-mod/dist/foxgrade-*.jar")), key=os.path.getmtime)[-1]
MCLIB = pathlib.Path.home() / "Library/Application Support/minecraft/libraries"
MAX_MAJOR = 65


def classpath():
    asm = sorted(glob.glob(str(MCLIB / "org/ow2/asm/asm/*/asm-*.jar")))[-1]
    extra = [sorted(glob.glob(str(MCLIB / f"org/ow2/asm/asm-{k}/*/asm-{k}-*.jar")))[-1]
             for k in ("tree", "commons", "analysis")]
    gson = sorted(glob.glob(str(MCLIB / "com/google/code/gson/gson/*/gson-*.jar")))[-1]
    return ":".join([JAR, gson, asm] + extra)


def port(jars, loader=None, out=None):
    """Run the real pipeline over these jars; returns (outdir, stderr)."""
    out = out or tempfile.mkdtemp(prefix="fg-regress-")
    game = tempfile.mkdtemp(prefix="fg-game-")
    cmd = ["java"]
    if loader:
        cmd.append(f"-Dfoxgrade.loader={loader}")
    cmd += ["-cp", classpath(), "foxgrade.CheckMain", "26.2", game, "--out", out] + [str(j) for j in jars]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    shutil.rmtree(game, ignore_errors=True)
    return out, r.stdout + r.stderr


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
          log_a[log_a.find("audit:"):][:110] if "audit:" in log_a else "")
    structural(out_a, "fabric")

    print("\nNeoForge corpus")
    out_n, log_n = port(nf, loader="neoforge")
    check("[neoforge] pipeline reported no audit findings", "audit:" not in log_n,
          log_n[log_n.find("audit:"):][:110] if "audit:" in log_n else "")
    structural(out_n, "neoforge")

    print("\nDeterminism")
    out_b, _ = port(fab[:2])
    out_c, _ = port(fab[:2])
    for a in sorted(glob.glob(f"{out_b}/*.jar")):
        b = pathlib.Path(out_c) / pathlib.Path(a).name
        same = b.exists() and hashlib.sha256(pathlib.Path(a).read_bytes()).digest() == hashlib.sha256(b.read_bytes()).digest()
        check(f"[determinism] {pathlib.Path(a).name[:38]} byte-identical across two ports", same)

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


main()
