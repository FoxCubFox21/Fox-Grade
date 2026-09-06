# Fox-Grade

**Drop an old Fabric mod in a folder. Launch Minecraft. It's ported.**

Fox-Grade is a Fabric mod that ports other mods forward across Minecraft versions —
automatically, at launch, with zero typing. No CLI, no config editing, no waiting for authors
to update abandoned mods.

## How to use it

1. Put `foxgrade-x.y.z.jar` in `mods/` (needs Fabric API).
2. Drop any older mod's jar into `<game folder>/fox-grade-inbox/`.
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
- Nothing is ever deleted: originals go to `fox-grade-inbox/processed/`, retired ports to
  `mods-backup/`.

## Client-side only

Fox-Grade declares `environment: client`, so Fabric will not load it on a dedicated server. The
porting engine itself is environment-agnostic, but three things are not server-ready yet: the
self-relaunch would fork and exit a server process managed by systemd or a hosting panel, the
access widener touches client-only classes, and two compatibility shims reach into client code.
Server support is a real possibility, not a present claim.

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
