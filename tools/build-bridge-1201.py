#!/usr/bin/env python3
"""Extend the intermediary→26.2 bridge with the 1.20.1 intermediary era.

Intermediary ids are stable across versions, so anything that survived to 1.21.1 is already in the
table. What's missing is every class/member whose intermediary id was retired between 1.20.1 and
1.21.1 — a 1.20.1 mod references those and the bridge has no name for them. This joins the 1.20.1
intermediary tiny (official↔intermediary) with the 1.20.1 Mojang proguard log (mojang↔official) to
give each retired id its Mojang name, then points it at 26.2: exact name if 26.2 still has it,
the unique package move if the class only moved, else the 1.20.1 Mojang name so the port report
names the thing in English instead of `class_1234`."""
import gzip, json, pathlib, re, collections, datetime
W = pathlib.Path.home() / "foxgrade-work"
TABLE = W / "foxgrade-mod/src/main/resources/foxgrade/intermediary-to-mojang.26.2.json.gz"
TINY = W / "im1201/tiny/mappings/mappings.tiny"; PRO = W / "im1201/mappings-1.20.1-client.txt"
OUT = W / "im1201/intermediary-to-mojang.26.2+1201.json.gz"
sigs = json.load(open("/tmp/mc-26.2-classes-sigs.json"))
t = json.load(gzip.open(TABLE))

# ---- proguard: mojang -> official, members keyed by official (class, name, desc)
PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}
moj2off, off2moj = {}, {}
pro_methods, pro_fields = {}, {}          # (offClass, offName, offDesc) -> mojang name ; (offClass, offName) -> mojang name
cur = None; raw_methods = []
for ln in open(PRO, encoding="utf-8"):
    if ln.startswith("#"): continue
    if not ln.startswith("    "):
        m = re.match(r"(\S+) -> (\S+):", ln.strip())
        if m: cur = (m.group(1), m.group(2)); moj2off[m.group(1)] = m.group(2); off2moj[m.group(2)] = m.group(1)
        continue
    body = ln.strip()
    m = re.match(r"(?:\d+:\d+:)?(\S+) (\S+)\((.*?)\) -> (\S+)$", body)
    if m: raw_methods.append((cur[1], m.group(1), m.group(2), m.group(3), m.group(4))); continue
    m = re.match(r"(\S+) (\S+) -> (\S+)$", body)
    if m: pro_fields[(cur[1], m.group(3))] = m.group(2)
def jdesc(jt):
    dims = jt.count("[]"); base = jt.replace("[]", "")
    d = PRIM.get(base) or ("L" + moj2off.get(base, base).replace(".", "/") + ";")
    return "[" * dims + d
for offc, ret, name, params, offn in raw_methods:
    desc = "(" + "".join(jdesc(p.strip()) for p in params.split(",") if p.strip()) + ")" + jdesc(ret)
    pro_methods[(offc, offn, desc)] = name
print(f"proguard 1.20.1: {len(moj2off)} classes, {len(pro_methods)} methods, {len(pro_fields)} fields")

# ---- 26.2 lookup: exact, else unique simple name
by_simple = collections.defaultdict(list)
for k in sigs:
    if "$" not in k: by_simple[k.rsplit("/", 1)[-1]].append(k)
def target_for(moj_slash):
    if moj_slash in sigs: return moj_slash, "exact"
    if "$" in moj_slash:
        outer, inner = moj_slash.split("$", 1)
        o, how = target_for(outer)
        if o + "$" + inner in sigs: return o + "$" + inner, "moved"
        return moj_slash, "unmapped"
    c = by_simple.get(moj_slash.rsplit("/", 1)[-1], [])
    if len(c) == 1: return c[0], "moved"
    return moj_slash, "unmapped"

# ---- tiny 1.20.1
classes, members, gm, gf = t["classes"], t["members"], t["globalMethods"], t["globalFields"]
removed = set(t.get("removedIntermediary", []))
stats = collections.Counter(); samples = []
curOff = curInter = None
for ln in open(TINY, encoding="utf-8"):
    p = ln.rstrip("\n").split("\t")
    if p[0] == "c":
        curOff, curInter = p[1], p[2]; interDot = curInter.replace("/", ".")
        if interDot in classes: stats["class already"] += 1; continue
        moj = off2moj.get(curOff)
        if not moj: stats["class no proguard"] += 1; continue
        tgt, how = target_for(moj.replace(".", "/"))
        classes[interDot] = tgt.replace("/", ".")
        if how != "unmapped": removed.discard(interDot)
        stats["class " + how] += 1
        if len(samples) < 8 and how != "exact": samples.append((interDot, moj, tgt, how))
    elif len(p) >= 5 and p[0] == "" and p[1] in ("m", "f") and curOff is not None:
        kind, desc, offn, intern = p[1], p[2], p[3], p[4]
        table = gm if kind == "m" else gf
        if intern in table: stats["member already"] += 1; continue
        moj = pro_methods.get((curOff, offn, desc)) if kind == "m" else pro_fields.get((curOff, offn))
        if not moj: stats["member no proguard"] += 1; continue
        table[intern] = moj
        interDot = curInter.replace("/", ".")
        members.setdefault(interDot, {"methods": {}, "fields": {}})["methods" if kind == "m" else "fields"][intern] = moj
        stats["member added"] += 1
t["removedIntermediary"] = sorted(removed)
t["sources"] = list(t.get("sources", [])) + ["intermediary-1.20.1-v2 + mappings-1.20.1-client (1.20.1 era, joined " + datetime.date.today().isoformat() + ")"]
with gzip.open(OUT, "wt") as f: json.dump(t, f)
print(dict(stats)); print("samples of moved/unmapped classes:"); [print("  ", s) for s in samples]
print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB; was {TABLE.stat().st_size // 1024} KB)")
