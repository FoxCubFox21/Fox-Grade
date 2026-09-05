# Fox-Grade — the mod that ports your mods

**That mod you love that died two versions ago? Drop its jar in a folder and launch the game.**

Fox-Grade rewrites old Fabric mods to run on the Minecraft version you're playing — at launch,
automatically, on your machine. It translates names across mapping eras (intermediary → Mojang),
rewrites access wideners and mixin refmaps, verifies every mixin target against the real game,
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

⭐ **Nothing is ever deleted.** Originals are kept, ports are reversible, and every launch writes
a report of what happened.

**Honesty section:** a port is not a guarantee. Features whose APIs no longer exist get disabled
(the panel tells you exactly which), and heavyweight rendering mods are out of scope. Ported mods
run with the feature set that can be proven safe.

*Mapping data: FabricMC intermediary & Yarn, Mojang's published maps. Mixin-surgery approach
inspired by Retromod (by Bownlux), reimplemented from scratch. License: PolyForm Noncommercial.*
