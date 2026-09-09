#!/usr/bin/env python3
"""Write the class inventory Fox-Grade checks references against: every class in a Minecraft jar with the members it
declares. AutoBlocklistFromRefmap reads it as /foxgrade/mc-<version>.classes.json.gz to decide whether a name a mod
asks for exists on the target at all.

Supporting a second target version starts here, because "does this still exist" cannot be answered without it.

Versions before 26.x ship obfuscated: their classes are named "a", "aa", "aae$a", and an inventory built from the
jar alone says nothing a rename table can use. Mojang publishes the mappings for those versions, and the mappings
name every class and member in full, with real types — so for an obfuscated jar the inventory is built from the
mappings and topped up with the classes the jar already names (bundled libraries, which were never obfuscated).

Usage: gen-mc-classes.py <minecraft.jar> <version> [mappings.txt]
Output: foxgrade-mod/src/main/resources/foxgrade/mc-<version>.classes.json.gz
"""
import gzip, json, pathlib, re, struct, sys, zipfile

HERE = pathlib.Path(__file__).resolve().parent


def members(data):
    """(internal name, method signatures, field signatures) read straight from the class file.

    Not javap: it fails silently on Minecraft's largest classes — Blocks and DataFixers among them — and those are
    exactly the classes a porter is asked about most."""
    n = struct.unpack(">H", data[8:10])[0]
    utf, cls, i, k = {}, {}, 10, 1
    while k < n:
        t = data[i]
        if t == 1:
            L = struct.unpack(">H", data[i + 1:i + 3])[0]
            utf[k] = data[i + 3:i + 3 + L].decode("utf-8", "replace"); i += 3 + L
        elif t == 7:
            cls[k] = struct.unpack(">H", data[i + 1:i + 3])[0]; i += 3
        elif t in (8, 16, 19, 20): i += 3
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t == 15: i += 4
        else: raise ValueError(f"constant tag {t}")
        k += 1
    i += 2                                                       # access_flags
    this_class = struct.unpack(">H", data[i:i + 2])[0]; i += 4    # this_class + super_class
    name = utf.get(cls.get(this_class), "?")
    i += 2 + 2 * struct.unpack(">H", data[i:i + 2])[0]            # interfaces

    def table(joiner):
        nonlocal i
        out = []
        cnt = struct.unpack(">H", data[i:i + 2])[0]; i += 2
        for _ in range(cnt):
            _acc, ni, di, ac = struct.unpack(">HHHH", data[i:i + 8]); i += 8
            for _ in range(ac):
                L = struct.unpack(">I", data[i + 2:i + 6])[0]; i += 6 + L
            out.append(joiner(utf.get(ni, "?"), utf.get(di, "?")))
        return out

    fields = table(lambda a, b: f"{a}:{b}")
    methods = table(lambda a, b: f"{a}{b}")
    return name, methods, fields


PRIMITIVES = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
              "int": "I", "long": "J", "float": "F", "double": "D"}


def descriptor(java_type):
    """A Java source type as a JVM descriptor: int[] -> [I, com.foo.Bar -> Lcom/foo/Bar;."""
    dims = java_type.count("[]")
    base = java_type.replace("[]", "").strip()
    return "[" * dims + (PRIMITIVES.get(base) or "L" + base.replace(".", "/") + ";")


def from_mappings(path):
    """The inventory as Mojang's published mappings describe it.

    Proguard format: a class line "net.minecraft.Foo -> a:" followed by indented members,
    "12:34:void bar(int) -> b" and "java.lang.String baz -> c". The left-hand side is the real name and the real
    types, which is all an inventory needs — the obfuscated name on the right is not recorded, because nothing
    downstream asks what a class used to be called, only what it has."""
    out, current = {}, None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if raw.startswith("#") or not raw.strip():
            continue
        if not raw.startswith(" "):
            m = re.match(r"([\w.$]+) -> ([\w.$]+):", raw.strip())
            current = m.group(1).replace(".", "/") if m else None
            if current:
                out.setdefault(current, {"m": set(), "f": set()})
            continue
        if current is None:
            continue
        body = raw.strip()
        m = re.match(r"(?:\d+:\d+:)?([\w.$\[\]]+) ([\w$<>]+)\((.*?)\) -> [\w$<>]+$", body)
        if m:
            ret, name, params = m.group(1), m.group(2), m.group(3)
            args = "".join(descriptor(p) for p in params.split(",") if p.strip())
            out[current]["m"].add(f"{name}({args}){descriptor(ret)}")
            continue
        m = re.match(r"([\w.$\[\]]+) ([\w$]+) -> [\w$]+$", body)
        if m:
            out[current]["f"].add(f"{m.group(2)}:{descriptor(m.group(1))}")
    return out


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__); sys.exit(2)
    jar, version = pathlib.Path(sys.argv[1]), sys.argv[2]
    mappings = pathlib.Path(sys.argv[3]) if len(sys.argv) == 4 else None
    out = HERE / f"foxgrade-mod/src/main/resources/foxgrade/mc-{version}.classes.json.gz"
    table, skipped = {}, 0
    with zipfile.ZipFile(jar) as z:
        for e in z.namelist():
            if not e.endswith(".class"):
                continue
            try:
                name, m, f = members(z.read(e))
            except Exception:
                skipped += 1
                continue
            table[name] = {"m": sorted(m), "f": sorted(f)}
    if mappings is not None:
        named = from_mappings(mappings)
        # The mappings win where they overlap: they carry the real names, the jar carries the obfuscated ones.
        # Everything the mappings do not mention was never obfuscated and is kept as the jar has it.
        obf_named = {c for c in table if len(c.rsplit("/", 1)[-1].split("$")[0]) <= 3}
        for c in obf_named:
            table.pop(c, None)
        for cls, members in named.items():
            table[cls] = {"m": sorted(members["m"]), "f": sorted(members["f"])}
    with gzip.open(out, "wt") as fh:
        json.dump(table, fh, separators=(",", ":"), sort_keys=True)
    # An obfuscated jar's own classes are expected to be unusable — that is why the mappings were read — so saying
    # "unreadable" about them reads as a fault when it is the normal case.
    note = ""
    if skipped and mappings is None:
        note = f", {skipped} unreadable"
    elif skipped:
        note = f" (from the mappings; the jar's {skipped} obfuscated classes were superseded)"
    print(f"{len(table)} classes from {jar.name} -> {out.name} ({out.stat().st_size // 1024} KB)" + note)


main()
