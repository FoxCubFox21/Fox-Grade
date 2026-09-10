# Fox-Grade — the mod that ports your mods

**That mod you love that died two versions ago? Drop its jar in `mods/` and launch the game.**

Fox-Grade rewrites old Fabric and Quilt mods to run on Minecraft 26.2 — automatically, at launch, on your own machine. No waiting on an update that might never come.

## It works, and here are the receipts

130 mods from 1.21.x and 26.1, every one launched into a real world: **86 boot.**

Head-to-head against [Retromod](https://modrinth.com/mod/retromod) on 104 of them, every mod both tools were run against. Same pass rule for both: the game reaches world load, stays up, and the mod is really in the loaded-mod list.

| | boots | of corpus | only under this tool | does not boot |
|---|---|---|---|---|
| **Fox-Grade** | **70** | **67%** | **33** | **34** |
| Retromod | 37 | 36% | 0 | 67 |

**33 mods boot only under Fox-Grade. None boot only under Retromod** — the 37 Retromod gets are a subset of the 70.

Failures are counted together on purpose. They come in two shapes — the game goes down, or it stays up without
reaching a world — and splitting them makes a column that rewards failing sooner. Retromod's failures are almost
all the first kind because its ports die at mixin-apply, early and cleanly. Fox-Grade makes every ported mixin
config non-required, so a conflicting or unmatched injection logs a line and skips instead of aborting: ports get
past initialisation, most of them run — which is where the 33 come from — and a few reach a world load they cannot
finish. Every mod in that second group is one Retromod crashes on as well, so the difference is where the failure
lands, not whether the mod works. The full breakdown is in `docs/compat.md` for anyone who wants it.

Every result with its cause is in the repository (`docs/compat.md`) — the 34 Fox-Grade misses here, and the 44 it
misses across the full 130.

### Where it runs

Fabric and Quilt are measured; the two Forge-family loaders are newer and their numbers are lower and honest.
The Fabric row is the shared 104-mod corpus, so both columns count the same mods — Fox-Grade's own corpus is
larger (86 of 130) but Retromod was never run against the extra 26, and comparing those denominators would
flatter Fox-Grade for mods its rival never saw.

| Loader | Fox-Grade | Retromod |
|---|---|---|
| Fabric | **70 of 104** | 37 of 104 |
| Quilt | **61 of 105** | not measurable — Quilt 0.31 hits a loader cache bug before Retromod's code runs |
| NeoForge | 16 of 40 | supported |
| Forge | lane just built; no corpus number yet | supported |

Quilt is the one place a fair comparison cannot be made, so no number is claimed for Retromod there rather than
quoting one that measures the loader's bug instead of the tool.

## Press F8

Every ported mod with its icon and a health check. What was changed, what was turned off, and **why — in plain English, not stack traces**. Verify a port live against the game you are running. Boot-test it in a throwaway copy of your world. Disable, retire or re-port with one click.

## It learns

What breaks a port is found by someone running it, and the fix is almost always a table entry — a mixin to disable
for one mod, a call to redirect — not new code. Fox-Grade writes down what its own crashes taught it, in
`.fox-grade/learned.json`, so a mod that took the game down once is not ported into the same crash the next time you
install it. And it picks up rules and anti-rules published since it was built, so a copy installed today gets better
without being updated.

Both only ever add to what shipped. Unreachable, stale or empty, Fox-Grade behaves exactly as it was built.

## It knows when to step aside

Fox-Grade watches Modrinth for the author's real update. The moment it lands: one click, checksum-verified, port retired. Install the official build yourself and the port retires on its own at next launch. **The author's build always beats a port.**

## On Fabric

Fox-Grade and Fabric API in `mods/`. Drop the old mod's jar in too and launch — it ports, installs, and restarts straight into it, so the mod is live on the same click.

Client or dedicated server. On a server it ports at startup and asks the operator to restart, rather than forking a process that systemd or a hosting panel owns. The panel is client-only; porting, the dependency pre-check and the crash guard work on both.

## On Quilt

Tested on Quilt Loader for 26.2 (the 0.31 beta) with plain Fabric API — there is no Quilted Fabric API or QSL for 26.2 yet.

**Put your first jar in `fox-grade-inbox/` next to `mods/`.** Quilt scans sub-folders of `mods/`, so on that first launch Fox-Grade marks its own folders for Quilt to skip; after that `mods/fox-grade-inbox/` works too.

Quilt-only mods — a `quilt.mod.json` and no `fabric.mod.json` — port like any other, and come out carrying a 26.2 `quilt.mod.json` of their own, so Quilt still sees a Quilt mod. QSL's own API modules are not bridged.

## What it sends

One request, and only when the panel is open: Modrinth's public read-only API, asking whether the author has published
a build of that mod for the version you are running. It carries the mod id, the Minecraft version and a Fox-Grade
user-agent — and, as any request does, your IP. One query per mod per session, four-second timeout, gives up quietly.
If you click to install an official build, that downloads it from Modrinth too, checksum-verified.

Second request, once a day at most: a rules feed published in this project's repository — anti-rules and bridge
rows found since the build, so an installed copy keeps improving without waiting for a release. It is a download and
carries nothing about you beyond the request itself. It can only add to what shipped, it is cached so porting works
offline, and `"rulesFeed": false` in `fox-grade.config.json` stops it being made at all.

Nothing else leaves your machine. There is no telemetry, no account, no usage reporting, and nothing is uploaded —
not your mod list, not your worlds, not the ports. The translation tables ship inside the jar rather than being
fetched, so porting itself needs no connection at all; unplug the network and everything except the update check
still works. GitHub appears in the mod's metadata as its homepage, source and issue tracker, and is never contacted.

## Honest about the limits

Heavyweight rendering mods are out of scope. Anything Fox-Grade cannot prove safe is disabled and **listed by name** — never guessed at. Originals are never deleted, every port is reversible, and a port that crashes your game retires itself, so it can never take you down twice.

**Minecraft 26.2 only** — the translation tables are built for one version, and on anything else Fox-Grade refuses to run rather than produce a port it cannot verify.

---

*Mapping data: FabricMC intermediary & Yarn, Mojang's published maps. Mixin-surgery approach inspired by [Retromod](https://modrinth.com/mod/retromod) (by Bownlux), reimplemented from scratch. License: PolyForm Noncommercial.*
