# Fox-Grade — the mod that ports your mods

**That mod you love that died two versions ago? Drop its jar in `mods/` and launch the game.**

Fox-Grade rewrites old Fabric and Quilt mods to run on Minecraft 26.2 — automatically, at launch, on your own machine. No waiting on an update that might never come.

## It works, and here are the receipts

130 mods from 1.21.x and 26.1, every one launched into a real world: **86 boot.**

Head-to-head against [Retromod](https://modrinth.com/mod/retromod) on the same 104 mods, same pass rule for both — the game reaches world load, stays up, and the mod is really in the loaded-mod list:

| | boots |
|---|---|
| **Fox-Grade** | **70** |
| Retromod | 37 |

**33 mods boot only under Fox-Grade. None boot only under Retromod.** Every result with its cause is in the repository (`docs/compat.md`).

## Press F8

Every ported mod with its icon and a health check. What was changed, what was turned off, and **why — in plain English, not stack traces**. Verify a port live against the game you are running. Boot-test it in a throwaway copy of your world. Disable, retire or re-port with one click.

## It knows when to step aside

Fox-Grade watches Modrinth for the author's real update. The moment it lands: one click, checksum-verified, port retired. Install the official build yourself and the port retires on its own at next launch. **The author's build always beats a port.**

## Honest about the limits

Heavyweight rendering mods are out of scope. Anything Fox-Grade cannot prove safe is disabled and **listed by name** — never guessed at. Originals are never deleted, every port is reversible, and a port that crashes your game retires itself, so it can never take you down twice.

## How

Fox-Grade and Fabric API in `mods/`. Drop the old mod's jar in too. Launch. It ports, installs, and restarts straight into it. *(On Quilt, put that first jar in `fox-grade-inbox/` next to `mods/`.)*

Fabric or Quilt. Client or dedicated server. **Minecraft 26.2 only** — the translation tables are built for one version, and on anything else Fox-Grade refuses to run rather than produce a port it cannot verify.

---

*Mapping data: FabricMC intermediary & Yarn, Mojang's published maps. Mixin-surgery approach inspired by [Retromod](https://modrinth.com/mod/retromod) (by Bownlux), reimplemented from scratch. License: PolyForm Noncommercial.*
