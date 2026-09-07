#!/usr/bin/env python3
"""Rewrite the head-to-head / test-count paragraphs in README, MODRINTH, CHANGELOG and the Reddit draft from
repo/docs/compat-summary.json (written by gen-compat-page.py). Run gen-compat-page.py first."""
import json, pathlib, re
W = pathlib.Path.home() / "foxgrade-work"; REPO = W / "repo"
S = json.loads((REPO / "docs/compat-summary.json").read_text())
T, F, R, FO, RO = S["h2h_total"], S["fox_boots"], S["retro_boots"], S["fox_only"], S["retro_only"]
A, B = S["all_mods"], S["all_boots"]

def sub(path, pattern, repl, flags=re.S):
    p = pathlib.Path(path); s = p.read_text(); n = len(re.findall(pattern, s, flags))
    assert n == 1, f"{path}: pattern matched {n}x: {pattern[:60]}"
    p.write_text(re.sub(pattern, lambda m: repl, s, flags=flags)); print("updated", path)

readme_h2h = f"""## Head-to-head with Retromod

The same {T} mods, same instance, same base jars, through Retromod (Modrinth's other 26.2
auto-porter, 1.3.0-snapshot.10) and Fox-Grade: **Fox-Grade boots {F}, Retromod boots {R}.**
{FO} boot only under Fox-Grade, {RO} only under Retromod, {S['both']} under both, {S['neither']} under neither.
The set is Modrinth's most-downloaded Fabric 1.21.1 mods (libraries and the renderer tier skipped,
required libraries pulled in), run through both tools under one rule: same launch command, same
base jars, same instance layout, and a pass means the world starts loading and the game is still
running 8 seconds later with the tested mod actually loaded, within a 150-second cap. Failures
were fixed in Fox-Grade where the game still has the API and left standing where it does not. Per-mod causes are
in [`docs/compat.md`](../docs/compat.md); the runner is `tools/run-h2h2.sh` and the corpus
builder `tools/h2h-corpus.py`.

"""
sub(REPO / "mod/README.md", r"## Head-to-head with Retromod\n.*?\n\n(?=## How well does it work\?)", readme_h2h)
sub(REPO / "mod/README.md", r"Batch-tested against \d+ mods from 1\.21\.x and 26\.1 \(every harness run folded to one row per mod,\nlast verdict wins\): \*\*\d+ port and boot into a world\*\*",
    f"Batch-tested against {A} mods from 1.21.x and 26.1 (every harness run folded to one row per mod,\nlast verdict wins): **{B} port and boot into a world**")
sub(REPO / "mod/MODRINTH.md", r"\*\*Head-to-head\.\*\* The same \d+ mods through Retromod \(1\.3\.0-snapshot\.10\) and Fox-Grade on one\ninstance: Fox-Grade boots \d+, Retromod boots \d+\.",
    f"**Head-to-head.** The same {T} mods through Retromod (1.3.0-snapshot.10) and Fox-Grade on one\ninstance: Fox-Grade boots {F}, Retromod boots {R} ({FO} boot only under Fox-Grade).")
sub(REPO / "mod/MODRINTH.md", r"Batch-tested against \d+ mods from 1\.21\.x and 26\.1: \d+ port\nand boot into a world", f"Batch-tested against {A} mods from 1.21.x and 26.1: {B} port\nand boot into a world")
sub(REPO / "mod/CHANGELOG.md", r"- \*\*Head-to-head with Retromod on \d+ mods: Fox-Grade \d+, Retromod \d+\*\*", f"- **Head-to-head with Retromod on {T} mods: Fox-Grade {F}, Retromod {R}** ({FO} boot only under Fox-Grade)")
rd = W / "publicity/reddit-mod-launch.md"
sub(rd, r"\*\*Against the other porter:\*\* I ran the same \d+ mods through Retromod \(the other auto-porter on\nModrinth\) and Fox-Grade on one instance\. Fox-Grade boots \d+, Retromod boots \d+\.",
    f"**Against the other porter:** I ran the same {T} mods (Modrinth's most-downloaded Fabric 1.21.1 list)\nthrough Retromod (the other auto-porter on Modrinth) and Fox-Grade on one instance. Fox-Grade boots {F},\nRetromod boots {R}; {FO} boot only under Fox-Grade.")
sub(rd, r"I tested it against \d+ mods from 1\.21\.x and 26\.1\. \d+ port and boot\ninto a world", f"I tested it against {A} mods from 1.21.x and 26.1. {B} port and boot\ninto a world")
sub(rd, r"\d+ mods tested, \d+ boot in-world\.", f"{A} mods tested, {B} boot in-world.")
NB = A - B
fail_readme = (f"— and {NB} do not. The failures are the 26.2 rewrites a bytecode port cannot paper over: renderer-tier\n"
  "internals (entity models and textures, the texture stitcher, the HUD layer stack, custom particle render\n"
  "types), API subsystems that were removed outright (item-model overrides, weighted lists, loot entry types),\n"
  "mods that are a rewrite rather than a port (Cobblemon: 251 unresolved references), the Sodium-dependent\n"
  "add-ons, and a few datapack formats.")
sub(REPO / "mod/README.md", r"— and \d+ crash\. The crashes are the 26\.2 rewrites Fox-Grade does not\nbridge yet:.*?\(a flower is no longer a bush\)\.", fail_readme)
fail_modrinth = (f"and {NB}\ndo not: renderer-tier internals 26.2 rewrote (entity models and textures, the texture stitcher, the HUD\n"
  "layer stack, particle render types), API subsystems removed outright (item-model overrides, weighted lists,\n"
  "loot entry types), one mod that is a rewrite rather than a port, the Sodium-dependent add-ons, and a few\n"
  "datapack formats.")
sub(REPO / "mod/MODRINTH.md", r"and \d+\ncrash on internals 26\.2 rewrote that are not bridged yet — block/item model rendering, the\nlow-level texture pipeline, a bundled animation engine, native code, and the last hierarchy changes\nbehind the two creature mods\.", fail_modrinth)
fail_reddit = (f"{NB} don't — 26.2 rewrote internals a\nbytecode port can't paper over: renderer-tier stuff (entity models/textures, the texture stitcher, the HUD\n"
  "layer stack, particle render types), API subsystems that were removed outright (item-model overrides,\n"
  "weighted lists, loot entry types), one mod that's a rewrite rather than a port (Cobblemon), the\n"
  "Sodium-dependent add-ons, and a couple of datapack formats.")
sub(rd, r"\d+ crash — 26\.2 rewrote internals\nFox-Grade doesn't bridge yet:.*?last hierarchy changes\.", fail_reddit)
print("done:", {k: v for k, v in S.items() if not k.endswith("_names")})
