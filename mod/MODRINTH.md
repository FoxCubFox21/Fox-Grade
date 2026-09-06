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

**Tested, and honest about the result.** Batch-tested against 14 mods from 1.21.x and 26.1: 11 port
and boot into a world, 2 cannot be ported because 26.2 removed the APIs they are built on, and 1 was
correctly held back because a library it needed was missing.

**Minecraft 26.2 only.** The translation tables are built for a single version. On any other
version Fox-Grade refuses to run rather than produce a port it cannot verify.

**Client-side only.** Fox-Grade won't load on a dedicated server: its restart-to-apply step would
fork and exit a server process, and parts of it touch client-only classes. Server support is
possible but is not built or tested yet, so it isn't claimed.

**Honesty section:** a port is not a guarantee. Features whose APIs no longer exist get disabled
(the panel tells you exactly which), and heavyweight rendering mods are out of scope. Ported mods
run with the feature set that can be proven safe.

*Mapping data: FabricMC intermediary & Yarn, Mojang's published maps. Mixin-surgery approach
inspired by Retromod (by Bownlux), reimplemented from scratch. License: PolyForm Noncommercial.*
