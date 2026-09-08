#!/usr/bin/env python3
"""Mine the vanilla registry-id renames between the source version and the target from the TARGET jar's own DataFixers:
every "Rename …" fix registers (old, new) id pairs as string constants, in fix order. A pair is kept only when the old id
existed in the source version, no longer exists in the target, and the new id does — that filter (not the fix version,
which javap cannot read out of the giant addFixers method) is what separates post-1.21.1 renames from ancient ones.

Inputs (all optional; the existing table is kept when one is missing):
  /tmp/mc262o                     unpacked TARGET jar (mojmap names, 26.x ships unobfuscated)
  /tmp/ids-1211-blocks.txt etc.   SOURCE version block/item ids (javap -c of the obfuscated Blocks/Items <clinit>)
Output: foxgrade-mod/src/main/resources/foxgrade/data-id-renames.json  {old: new}
"""
import json, pathlib, re, struct, sys

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "foxgrade-mod/src/main/resources/foxgrade/data-id-renames.json"
TARGET = pathlib.Path("/tmp/mc262o")
DATAFIXERS = TARGET / "net/minecraft/util/datafix/DataFixers.class"
TARGET_IDS = TARGET / "net/minecraft/references/BlockItemIds.class"   # 26.x keeps the vanilla block+item ids here
SOURCE_ID_FILES = [pathlib.Path("/tmp/ids-1211-blocks.txt"), pathlib.Path("/tmp/ids-1211-items.txt")]


def literals(path):
    """CONSTANT_String entries of a class file, in constant-pool order (= first use in bytecode)."""
    b = path.read_bytes(); n = struct.unpack(">H", b[8:10])[0]; i = 10; utf = {}; strs = []; k = 1
    while k < n:
        t = b[i]
        if t == 1:
            L = struct.unpack(">H", b[i + 1:i + 3])[0]; utf[k] = b[i + 3:i + 3 + L].decode("utf-8", "replace"); i += 3 + L
        elif t in (7, 8, 16, 19, 20):
            if t == 8: strs.append(struct.unpack(">H", b[i + 1:i + 3])[0])
            i += 3
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t == 15: i += 4
        else: raise ValueError(f"bad constant tag {t} at {i}")
        k += 1
    return [utf[x] for x in strs if x in utf]


def main():
    if not (DATAFIXERS.exists() and TARGET_IDS.exists() and all(p.exists() for p in SOURCE_ID_FILES)):
        print("inputs missing; keeping", OUT.name); return
    source = set(); [source.update(p.read_text().split()) for p in SOURCE_ID_FILES]
    target = {s for s in literals(TARGET_IDS) if re.fullmatch(r"[a-z0-9_]+", s)}
    lits = literals(DATAFIXERS)
    pairs = []; i = 0
    while i < len(lits):
        if lits[i].lower().startswith("rename"):
            j = i + 1
            while j + 1 < len(lits) and re.fullmatch(r"minecraft:[a-z0-9_/.]+", lits[j]) and re.fullmatch(r"minecraft:[a-z0-9_/.]+", lits[j + 1]):
                pairs.append((lits[j], lits[j + 1])); j += 2
            i = j
        else: i += 1
    table = {}
    for old, new in pairs:
        o, n = old.split(":", 1)[1], new.split(":", 1)[1]
        if o in source and o not in target and n in target: table[old] = new
    OUT.write_text(json.dumps(table, indent=2) + "\n")
    print(f"{len(pairs)} rename pairs in DataFixers, {len(table)} apply to this port:", table)


if __name__ == "__main__":
    main()
