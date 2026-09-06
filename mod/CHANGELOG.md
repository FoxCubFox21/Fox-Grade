# Changelog

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
