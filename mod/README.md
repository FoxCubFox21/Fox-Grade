# Fox-Grade

**Drop an old Fabric mod in a folder. Launch Minecraft. It's ported.**

Fox-Grade is a Fabric mod that ports other mods forward across Minecraft versions —
automatically, at launch, with zero typing. No CLI, no config editing, no waiting for authors
to update abandoned mods.

## How to use it

1. Put `foxgrade-x.y.z.jar` in `mods/` (needs Fabric API).
2. Drop any older mod's jar into `mods/` like any other mod — or into `mods/fox-grade-inbox/` if you
   prefer to be explicit. Fox-Grade sweeps `mods/` at launch: a jar built for an older game (its
   declared version excludes yours, or it still speaks the pre-26 intermediary names) is moved to the
   inbox and ported before Fabric ever sees it. One exception: a mod that ships an *access
   widener* is read by Fabric Loader itself before any mod code runs, and an old one stops the
   loader cold — those jars go in `mods/fox-grade-inbox/` (Fox-Grade rewrites the widener there).
3. Launch. Fox-Grade ports it, installs the result, and restarts the game itself.
   The mod comes up running — same click.

**Will it work before I launch?** `./foxgrade-check.sh some-old-mod.jar` runs the exact porting
pipeline without starting the game and prints every class, member and constructor the port would
still reference that 26.2 no longer has. `--out DIR` also writes the ported jar. It needs a Fabric
26.2 install on the machine (the game jar and libraries are read from there) and the built
`dist/foxgrade-*.jar`.

Press **F8** in-game (or use the Fox-Grade button on the title/pause screen) to open the panel:
every port with its icon, source version, and health; per-mod details showing exactly which
features were disabled for safety (in plain English); one-click disable/retire/re-port;
a boot test that clones your latest world and verifies stability; and a Modrinth watcher that
tells you the moment the author ships a real build — with one-click install that retires
Fox-Grade's port, because **the author's official build always beats a port**.

## How it works

- A bundled multi-scheme rename bridge (intermediary ↔ Mojang ↔ Yarn, 16k+ classes, 78k+ members)
  rewrites the mod's bytecode via ASM at preLaunch, before Fabric locks in the mod set.
- Access wideners and mixin refmaps are rewritten to the target namespace.
- Every mixin target is verified against the *actual* target-version class inventory —
  handlers whose targets no longer exist are surgically removed (or their whole feature
  disabled) instead of crashing your game.
- A verification pass walks the ported bytecode and reports every reference that could not be
  resolved, so you know the risk before you play.
- Nothing is ever deleted: originals go to `mods/fox-grade-inbox/processed/`, retired ports to
  `mods-backup/`.

## What it can carry across

26.2 rewrote whole subsystems rather than renaming them. Fox-Grade re-creates the old surface on
top of the new one, verified against the game's real class inventory:

- **GUI drawing** — the 1.21.x `GuiGraphics` API (text, fills, sprites, items, tooltips, scissor)
  over 26.2's extract/render-state model; Mod Menu's mod list opens and renders.
- **World drawing** — Fabric's removed `WorldRenderEvents` and the tessellator/buffer-source calls
  over the 26.2 submit API; overlay mods draw again.
- **Entities, items, blocks** — save data (`CompoundTag` ↔ value IO), the `ServerLevel`-taking
  rewrites of AI/hurt/pick-up/drop methods, spawn eggs, tool tiers, targeting selectors, game
  rules, path types, holder-wrapped constants, moved constants (`EntityType.FOX` →
  `EntityTypes.FOX`), block shape/fall/clone-item overrides, reload listeners, and ~250 smaller
  signature changes. Creature mods now port down to their model/render internals.
- **Not carried**: block/item *model* rendering (`BakedModel`, `BlockRenderDispatcher`,
  `ItemRenderer`), the low-level texture/render-type pipeline (what GeckoLib is built on), custom
  brains/behaviours, and data-component rewrites of container/NBT serialisation. Those are
  reported, not guessed.

The live per-mod table with every verdict, unresolved count and screenshot is in
[`docs/compat.md`](../docs/compat.md), regenerated from the harness ledgers.

**Older inputs.** The translation tables now include the 1.20.1 intermediary era, so 1.20.1 jars
translate to readable names and port through the same layers; what they still hit is the 1.20.x
immediate-mode drawing (`BufferBuilder.begin/end`) and the pre-1.20.5 networking API, which are not
bridged yet.

## Head-to-head with Retromod

The same 104 mods, same instance, same base jars, through Retromod (Modrinth's other 26.2
auto-porter, 1.3.0-snapshot.10) and Fox-Grade: **Fox-Grade boots 51, Retromod boots 37.**
21 boot only under Fox-Grade, 7 only under Retromod, 30 under both, 46 under neither.
The set is Modrinth's most-downloaded Fabric 1.21.1 mods (libraries and the renderer tier skipped,
required libraries pulled in), run through both tools under one rule: same launch command, same
base jars, same instance layout, and a pass means the world starts loading and the game is still
running 8 seconds later with the tested mod actually loaded, within a 150-second cap. Failures
were fixed in Fox-Grade where the game still has the API and left standing where it does not. Per-mod causes are
in [`docs/compat.md`](../docs/compat.md); the runner is `tools/run-h2h2.sh` and the corpus
builder `tools/h2h-corpus.py`.

## How well does it work?

Batch-tested against 130 mods from 1.21.x and 26.1 (every harness run folded to one row per mod,
last verdict wins): **67 port and boot into a world** — six of them also verified on a dedicated
server, Mod Menu's mod list opens and renders through the GUI bridges, Lighty boots on the
re-created world-render events — and 63 do not. The failures are the 26.2 rewrites a bytecode port cannot paper over: renderer-tier
internals (entity models and textures, the texture stitcher, the HUD layer stack, custom particle render
types), API subsystems that were removed outright (item-model overrides, weighted lists, loot entry types),
mods that are a rewrite rather than a port (Cobblemon: 251 unresolved references), the Sodium-dependent
add-ons, and a few datapack formats. Ports that cannot be completed fail safely, with a report
listing every class, member and constructor the game no longer has. The per-mod table is
[`docs/compat.md`](../docs/compat.md).

Fox-Grade requires **Minecraft 26.2 exactly**. Its translation tables are built for a single
version; on anything else it refuses to run rather than produce a port it cannot verify.

## Client and dedicated server

Fox-Grade runs on both. The porting engine never cared which side it was on; what needed fixing was
the restart-to-apply step.

On a **client** it ports and relaunches the game itself, so a dropped jar is live on the same click.
On a **dedicated server** it ports at startup and then asks the operator to restart, because a
server process is owned by systemd, a hosting panel or a screen session — forking a replacement
would orphan the child or kill the server. It detects the environment and never forks there.

Quilt loads Fabric mods, Fox-Grade included; it is not separately tested there. Forge and NeoForge
mods are out of scope — the loader APIs are different products.

The in-game panel is client-only, for the obvious reason. Everything else — porting, the dependency
pre-check, the crash guard, the reports — works on both.

Verified on a real 26.2 Fabric dedicated server: three mods ported from the inbox and loaded
(including `alternate-current`, a redstone engine that mixins into server tick logic), two more
correctly held back for missing libraries, server reached `Done` with no errors.

## What it is not

A guarantee. A port disables what it cannot prove safe — the panel tells you exactly what and
why. If a mod's core feature depends on an API that no longer exists, Fox-Grade will boot it
safely but that feature will be off. Mods that ship their own native libraries or rendering
pipelines (e.g. Sodium-tier renderers) are out of scope.

## Credits

- Mapping data derives from [FabricMC intermediary](https://github.com/FabricMC/intermediary),
  [Yarn](https://github.com/FabricMC/yarn), and Mojang's published obfuscation maps, composed
  offline into Fox-Grade's bridge tables.
- The AccessWidener + refmap remapping approach and the surgical mixin-handler blocklist were
  inspired by [Retromod](https://modrinth.com/mod/retromod) by Bownlux — reimplemented from
  scratch for Fox-Grade's pipeline. Thanks for proving it could be done.

## License

PolyForm Noncommercial 1.0.0 — free to use, share, and modify, not to sell.
