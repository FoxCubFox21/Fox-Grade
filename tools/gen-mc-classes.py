#!/usr/bin/env python3
"""Write the class inventory Fox-Grade checks references against: every class in a Minecraft jar with the members it
declares. AutoBlocklistFromRefmap reads it as /foxgrade/mc-<version>.classes.json.gz to decide whether a name a mod
asks for exists on the target at all.

Supporting a second target version starts here, because "does this still exist" cannot be answered without it.

Usage: gen-mc-classes.py <minecraft.jar> <version>
Output: foxgrade-mod/src/main/resources/foxgrade/mc-<version>.classes.json.gz
"""
import gzip, json, pathlib, struct, sys, zipfile

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


def main():
    if len(sys.argv) != 3:
        print(__doc__); sys.exit(2)
    jar, version = pathlib.Path(sys.argv[1]), sys.argv[2]
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
    with gzip.open(out, "wt") as fh:
        json.dump(table, fh, separators=(",", ":"), sort_keys=True)
    print(f"{len(table)} classes from {jar.name} -> {out.name} ({out.stat().st_size // 1024} KB)"
          + (f", {skipped} unreadable" if skipped else ""))


main()
