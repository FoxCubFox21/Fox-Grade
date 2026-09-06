# Changelog

## 1.1.0 (unreleased)
- **Dedicated server support.** The restart-to-apply step now detects a dedicated server and asks
  the operator to restart rather than forking the process, which on systemd or a hosting panel
  would have orphaned the child or killed the server outright.
- Removed the client-only access widener entries; they were redundant even on the client, because
  the code that used them goes through plain reflection.
- Verified on a real 26.2 Fabric server: three mods ported and loaded, two correctly held for
  missing libraries, server reached `Done` clean. Client behaviour unchanged and re-tested.
- **Inbox moved to `mods/fox-grade-inbox/`.** One folder to know about instead of two; the old
  `fox-grade-inbox/` next to `mods/` is still read, so nothing already there is stranded.
- Constructor adapters and call redirects now also apply to method references (`Vec3::new`,
  `Helper::method`), which compile to an invokedynamic handle rather than a call instruction.
- Translation tables now cover `MinecraftServer` and five other classes whose names never
  changed but whose members did; mods touching the server directly (spark, carpet) resolve.
- New bridges: `WorldVersion` accessors, `Vec3(Vector3f)` -> `Vec3(Vector3fc)`,
  `Util.backgroundExecutor()`, and the `KeyBindingHelper` -> `KeyMappingHelper` move.
- **Field redirects.** A field that stopped existing can now be routed to a shim getter/setter
  the same way removed methods are; first use is `Entity.noCulling`, which 26.2 keeps only on
  `Display`.
- **`HudRenderCallback` bridge.** Fabric API removed it after 1.21.5; every 1.21.1-era HUD mod
  registers with it. Fox-Grade now supplies a real one, backed by `HudElementRegistry`, so those
  overlays render instead of crashing at init.
- **Member-level verification.** The port report now lists missing fields, static methods and
  constructors, not just missing classes, resolved through the game's real class hierarchy so an
  inherited member is never a false alarm. Instance methods stay out on purpose: Fabric's
  interface injection adds those at runtime. Previously each removed field cost one launch to find.
- **`Minecraft` field compat.** `cameraEntity` and `screen` became methods in 26.2; reads and
  writes of the old fields are routed to them, and `setScreen` to `setScreenAndShow`.
- `LazyLoadedValue` is regenerated for ports that still use it.

## 1.0.0
First public release.
- One-click inbox porting with automatic self-relaunch (no double launch)
- Multi-scheme bridge: intermediary / Mojang / Yarn / obfuscated, 16,335 classes, 78k members
- Mixin safety: signature-strict target verification, surgical handler removal,
  whole-feature deregistration when a fatal @Shadow/@Overwrite can't be saved
- Reflection-string remapping, nested Jar-in-Jar transformation, per-port shim namespacing
- In-game panel (F8 / title / pause): lifetime stats, mod icons, plain-English feature-loss
  reports, live verification against the running game, clipboard reports, re-port,
  enable/disable, retire, boot test with world cloning, one-click restart
- Modrinth watcher: spots official builds for your MC version, one-click install
  (SHA-1 verified) that retires the port — the author's build always wins
- Yield-to-official at launch: if you install the real mod yourself, the port retires itself
- Batch-tested against 14 mods from 1.21.x and 26.1: 11 port and boot into a world, 2 cannot be
  ported because 26.2 removed the APIs they are built on, and 1 was correctly held back because a
  library it depends on was not present. Two further libraries were co-ported alongside their mods.
- Requires Minecraft 26.2 exactly. The bundled translation tables are built for one version, so
  running on any other is refused outright rather than producing an untranslated port.
