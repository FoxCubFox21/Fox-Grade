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
- **GUI rendering layer.** 26.2 replaced immediate-mode GUI drawing with an extract-then-render
  design; 1.21.x mods draw through APIs that no longer exist. Fox-Grade now bridges that layer:
  `GuiGraphics` calls map onto `GuiGraphicsExtractor` (text, fill, blit, sprites, items, tooltips,
  a forwarded `pose()`), `RenderSystem` state calls become no-ops because pipelines own that state
  (the shader colour survives as a tint, the shader texture feeds replay), `Tesselator` /
  `BufferUploader` / `VertexFormat.Mode` are re-created so quads a mod builds itself are captured
  and submitted as GUI elements, and the shader getters reachable only through method references
  are erased. Raw GL calls stay unresolved on purpose.
- **Input events.** Handlers moved from `(int,int,int)` / `(double,double,int)` arguments to
  `KeyEvent` / `MouseButtonEvent` objects. Calls into the game are repacked, and a mod class that
  overrides the old signature gets the new one synthesised so the game still reaches it.
- **Verifier covers instance methods** (with loaded mods' injected interfaces honoured) and inner
  classes, and the port metadata keeps the whole unresolved list instead of five entries.
- New mechanisms behind the above: descriptor widening (joml `Matrix4f` → `Matrix4fc`),
  invokedynamic-only redirects, call adapters, override adapters, method-entry hooks, shim
  dependencies with per-port namespacing, and shims re-created at Minecraft class names.
- Smaller bridges: `I18n.exists/getOrDefault`, `ChatFormatting` colour/name queries,
  `Window.getGuiScale`, `Camera.getLookVector`, `Minecraft.getToasts`, selection-list renames,
  `Screen.hasShiftDown/isCopy/…`, `InputConstants.isKeyDown/getKey`, `KeyMapping.matches`.
- The build now compiles against the fabric-rendering-v1 module plus joml, jspecify and fastutil.
- **Widener fixes.** Access wideners now translate member names too (an intermediary
  `method_NNNN` was left untouched before, so the widening silently missed), and Fabric's newer
  class-tweaker format (`.ct`) is rewritten the same way; YACL ships one.
- Renames follow the class chain: a curated rename filed under `AbstractWidget` applies to a call
  on `Button`, and an inherited rename can depend on ancestry (`renderWidget` becomes
  `extractContents` under `AbstractButton`, whose own hook is final).
- Lists: 26.2's `children()` hands out a read-only view; 1.21.x list code that mutates it now
  reaches the live list, and the removed list fields/hooks (`headerHeight`, `itemHeight`,
  `clickedHeader`) are carried per list.
- Resource reload listeners implementing the 1.21.x `reload(...)` signature get the 26.2 one
  synthesised. Fabric API renames: channel events (`S2C`/`C2S` → `Clientbound`/`Serverbound`),
  entity level-change events, `ClientCommands`, `ClientTooltipComponentCallback`,
  `Screens.getWidgets`.
- GUI corpus results: BetterF3, Chat Heads and Zoomify (co-ported with its YACL config library)
  boot; Mod Menu's mod list screen opens and renders end to end. Jade, WTHIT and Shulker Box
  Tooltip stay out — their remaining references are game internals (block state, tooltips, container
  serialisation), not rendering.

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
