# Fox-Grade

**Drop an old Fabric mod in a folder. Launch Minecraft. It's ported.**

Fox-Grade is a Fabric mod that ports other mods forward across Minecraft versions —
automatically, at launch, with zero typing. No CLI, no config editing, no waiting for authors
to update abandoned mods.

## How to use it

1. Put `foxgrade-x.y.z.jar` in `mods/` (needs Fabric API).
2. Drop any older mod's jar into `mods/fox-grade-inbox/` (Fox-Grade creates the folder on first launch).
3. Launch. Fox-Grade ports it, installs the result, and restarts the game itself.
   The mod comes up running — same click.

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

## How well does it work?

Batch-tested against 31 mods from 1.21.x and 26.1: **20 port and boot into a world** (six of them
also verified on a dedicated server, and Mod Menu's mod list opens and renders through the GUI
bridges), 6 cannot be ported — game internals 26.2 rewrote outright, from world rendering to
container serialisation, plus one native profiler — and 5 were correctly held back because a
library they depend on was not provided. Ports that cannot be completed fail safely, with a report
listing every class, member and constructor the game no longer has.

Fox-Grade requires **Minecraft 26.2 exactly**. Its translation tables are built for a single
version; on anything else it refuses to run rather than produce a port it cannot verify.

## Client and dedicated server

Fox-Grade runs on both. The porting engine never cared which side it was on; what needed fixing was
the restart-to-apply step.

On a **client** it ports and relaunches the game itself, so a dropped jar is live on the same click.
On a **dedicated server** it ports at startup and then asks the operator to restart, because a
server process is owned by systemd, a hosting panel or a screen session — forking a replacement
would orphan the child or kill the server. It detects the environment and never forks there.

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
