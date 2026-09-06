# Fox-Grade — the mod that ports your mods

**That mod you love that died two versions ago? Drop its jar in `mods/` (or `mods/fox-grade-inbox/` if it ships an access widener) and launch the game.**

Fox-Grade rewrites old Fabric mods to run on Minecraft 26.2 — at launch, automatically, on your
own machine. It translates names across mapping eras (intermediary → Mojang),
rewrites access wideners and mixin refmaps, checks every mixin target still exists,
and disables — surgically, with a plain-English report — only what can't be made safe.

⭐ **The panel** (F8, or the button on the title/pause screen)
- every ported mod with its icon, source version and health check
- details view: what was changed, what was turned off, and why — in English, not stack traces
- live verify: checks every Minecraft class the port touches against the game you're running
- boot test: clones your newest world, restarts into the clone, reports back — your real world untouched
- disable / retire / re-port with one click

⭐ **It knows when to step aside.** Fox-Grade watches Modrinth for official builds of the mods
it ported. The moment the author ships a real update: one click downloads it (checksum-verified),
installs it, and retires the port. If you install the official build yourself, the port retires
automatically at next launch. **The author's build always beats a port.**

⭐ **It re-creates what 26.2 removed.** The 1.21.x GUI drawing API, Fabric's world-render events,
and the entity/item/block API surface (save data, AI and damage signatures, spawn eggs, tool tiers,
game rules, moved and holder-wrapped constants, reload listeners…) are bridged onto the new game and
verified against its real class inventory. Block/item *model* rendering and the low-level texture
pipeline are not — those get reported, not guessed. The per-mod results table lives in the
repository (`docs/compat.md`).

⭐ **Check before you launch.** `foxgrade-check.sh` (in the repository) runs the real porting
pipeline on a jar without starting the game and lists exactly what the port would still miss.

⭐ **Nothing is ever deleted.** Originals are kept, ports are reversible, and every launch writes
a report of what happened.

**Tested, and honest about the result.** Batch-tested against 130 mods from 1.21.x and 26.1: 83 port
and boot into a world (six also verified on a dedicated server; Mod Menu's mod list opens and
renders through the GUI bridges; world-drawing mods run on the re-created render events) and 47
do not: renderer-tier internals 26.2 rewrote (entity models and textures, the texture stitcher, the HUD
layer stack, particle render types), API subsystems removed outright (item-model overrides, weighted lists,
loot entry types), one mod that is a rewrite rather than a port, the Sodium-dependent add-ons, and a few
datapack formats. The report lists every class, member and constructor a port still
references that the game no longer has; the per-mod table lives in the repository.

**Head-to-head.** The same 100 mods through Retromod (1.3.0-snapshot.10) and Fox-Grade on one
instance: Fox-Grade boots 64, Retromod boots 38 (28 boot only under Fox-Grade). The table with every cause is in the repository.

**Minecraft 26.2 only.** The translation tables are built for a single version. On any other
version Fox-Grade refuses to run rather than produce a port it cannot verify.

**Client and dedicated server.** On a client, Fox-Grade ports and restarts the game itself, so a
dropped jar is live on the same click. On a dedicated server it ports at startup and asks the
operator to restart instead — a server process is owned by systemd or a hosting panel, and forking
a replacement would kill it. The in-game panel is client-only; porting, the dependency pre-check
and the crash guard work on both.

**Honesty section:** a port is not a guarantee. Features whose APIs no longer exist get disabled
(the panel tells you exactly which), and heavyweight rendering mods are out of scope. Ported mods
run with the feature set that can be proven safe.

*Mapping data: FabricMC intermediary & Yarn, Mojang's published maps. Mixin-surgery approach
inspired by Retromod (by Bownlux), reimplemented from scratch. License: PolyForm Noncommercial.*
