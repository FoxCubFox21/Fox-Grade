# Changelog

## 1.1.0 (unreleased)
- **Quilt-only mods.** A jar with a `quilt.mod.json` and no `fabric.mod.json` is ported like any other:
  the manifest becomes a `fabric.mod.json` (ids, entrypoints, mixins, widener, dependencies), and Quilt's
  loader API (`QuiltLoader`, `ModContainer`, `ModMetadata`, `Version`, `MinecraftQuiltLoader`) and the QSL
  entrypoint interfaces are bridged onto Fabric Loader, and the port carries a 26.2 `quilt.mod.json` as well.
  Fox-Grade runs on Quilt Loader 26.2 (0.31 beta): the inbox folders carry Quilt's `quilt_loader_ignored` marker
  (Quilt scans `mods/` recursively), on a Quilt host the loader's own API classes are never shipped as stand-ins,
  and the self-relaunch drops Quilt's transform cache and waits for the old JVM to exit first (Quilt 0.31 cannot
  re-create that cache in place when the mod set changes).
  QSL's own API modules are not bridged.
- **Custom render types.** 1.21's `RenderType.create(...)` with a `CompositeState` of shards is rebuilt
  as a 26.2 `RenderSetup` on the nearest pipeline (glint layers on the glint pipeline); cit-resewn boots.
- **Access wideners by mapped name.** Shipped wideners carry intermediary member names; type changes
  (File → Path) and class moves are now looked up the way the bytecode is remapped. Distant Horizons boots,
  together with bridges for its chunk-level calls (paletted containers, proto chunks, structure generation).
- **Callbacks that gained parameters.** A Fabric API callback whose interface grew a trailing parameter
  (`ServerChunkEvents.Load`) gets a synthesised bridge at the lambda site.
- **Datapacks.** Template-pool weights are rescaled to the 26.2 cap of 150; vanilla ids the target renamed
  (`chain` → `iron_chain`) are rewritten in datapack and resource JSON, from a table mined out of the game's
  own DataFixers. YUNG's dungeons, jungle temples and strongholds boot.
- **Mixins whose superclass vanished** (or is the target itself) are neutralised; Mixin walks the parent chain.
- Dropped Fabric API event accessors return a dead event; the Fabric networking packet factories, pack
  metadata `TYPE`/`BuiltInMetadata`/`getMetadataSection`, and the block-entity renderer context are bridged.
- `DataComponents.HIDE_ADDITIONAL_TOOLTIP` gets a Fox-Grade-owned component registered at mod init.
- **Constructor references and bundled libraries.** `LeavesBlock::new`-style references to a
  constructor that changed shape now go through a synthesised bridge so the constructor adapters
  apply to them; a factory adapter no longer fires on a subclass's `super(...)` call. Jar-in-jar
  libraries are ported recursively as before, but a failure to port one is now logged instead of
  silently shipping the old jar (that bug hid behind a 1-based recipe slot).
- **Interface default methods get override adapters** (a reload-listener mix-in that implements
  the 1.21 `reload` by default now gets the 26.2 one synthesised).
- **Removed Fabric API types referenced only in signatures** are synthesised as empty
  interfaces; removed `Event` constants on surviving classes read as dead events.
- **Registries of codecs.** 26.2 turned the structure-processor, loot-entry/function/condition and
  similar "type" registries into registries of MapCodecs; a 1.21 type object (record or lambda)
  registered into one is unwrapped to its codec. `getType()` overrides on loot entries, functions
  and conditions become `codec()`.
- **Datapack JSON that only a lenient parser accepts** is re-serialised strictly at port time
  (26.2 loads registry data strictly).
- **More bridges.** `Registry.get/getOrThrow` (value forms), `EntityType.Builder.build()`,
  `EntityTypePredicate.matches(EntityType)`, `Util` executor pools, `IntProvider/FloatProvider.codec`,
  `PacketByteBufs`, `SimpleCraftingRecipeSerializer`, Fabric resource conditions, transfer-API
  component patches, `WeightedRandomList`/`WeightedEntry`/`Weight`, `FlyingMob`, `ShaderInstance`
  (type only), `LootPoolEntryType`, the block-appearance hook, leaves and chest constructors.
- **Faster launches.** The translation tables (intermediary and Mojang renames, verified class
  moves) are parsed once and kept as a binary cache under `.fox-grade/cache/`; a later launch
  reads them in about a tenth of a second instead of parsing several megabytes of JSON. The 26.2
  class inventory is parsed once per launch rather than once per ported jar. The launch log now
  prints per-phase timings for each port.
- **Mixin surgery, round two (from the 100-mod head-to-head).** Every injector is made non-required
  (`require = 0`, config `defaultRequire = 0`), so an injection that cannot apply logs instead of
  taking the game down. Handlers whose target still exists but changed its parameters are stripped
  (arity-aware, per injector kind); so are bare-name injectors (`method = "actuallyHurt"`) whose
  mirrored parameters match no 26.2 overload. Stripping is transitive: callers of a removed shadow
  or helper go with it, across mixin classes and through `this::method` references. Mixins whose
  target became an interface are neutralised like missing-target ones. Refmap-less mixins
  (intermediary selectors Fabric remaps at runtime) get their selectors translated and rewritten
  to 26.2 names; previously they were judged against Mojang names and working handlers were lost.
- **Removed Fabric API callback types are synthesised** into the port (interface + dead `EVENT`
  built from the mod's own lambda signature), and reads of removed `Event` constants on surviving
  classes go to the same dead event — registration is a no-op instead of a crash.
- **Loader-level fixes.** A pinned `java` dependency is dropped; a dependency on a Fabric API
  module 26.2 no longer ships (`fabric-key-binding-api-v1`, `fabric-item-group-api-v1`,
  `fabric-screen-handler-api-v1`, …) becomes `fabric-api`; header-only access wideners get their
  namespace rewritten too.
- **Stack-map frames are recomputed** for every class whose rewrite reshaped the operand stack
  (withheld constructors, argument recipes, holder/retype constants) — the copied frames no
  longer described the code.
- **More bridges.** `Minecraft.getGuiSprites` (atlas manager stand-in), `Tickable`,
  `ItemSubPredicate`, Codec→MapCodec constants, `Registries.*` keys of removed registries,
  early `ItemStack` construction before component binding, `ChunkPos(long)`,
  `TicketType.create`, biome sea-level methods, `BlockState.isSolidRender`, `Level.getSunAngle`,
  `Entity.createCommandSourceStack`, `FriendlyByteBuf.writeDate/readDate`,
  `WorldVersion.getPackVersion`, `PreparableModelLoadingPlugin.register`, ScreenEvents
  Before/AfterRender implementors, `TagsProvider.TagAppender`, `ItemParser.ItemResult`, lead
  sound names, and three narrowed vanilla fields widened (`Inventory.selected`,
  `ServerPlayer.server`, `Screen.children`).
- **Auto-inbox.** `mods/` is swept at launch: any jar whose declared Minecraft range excludes the
  running version, or that still references pre-26 intermediary names, is moved to the inbox and
  ported before Fabric loads the mod set. Dropping an old mod into `mods/` is enough — unless it
  ships an access widener, which Fabric Loader parses before any mod code runs (an old one aborts
  the launch); those still go in the inbox.
- **Standalone checker.** `foxgrade-check.sh mod.jar` runs the full pipeline without the game and
  prints every unresolved class, member and constructor; `--out DIR` writes the port.
- **Entity/item/block API layer.** Value-IO save data, `ServerLevel`-taking AI/hurt/pick-up/drop
  signatures, spawn eggs (constructor → factory), tool tiers (`Tier`/`SwordItem`/`DiggerItem`),
  targeting selectors, game rules, path types, holder-wrapped and moved constants, retyped
  constants, block `updateShape`/`fallOn`/`getCloneItemStack`/`useItemOn` overrides, item
  tooltips, reload listeners, brain activities, and the renderer super-calls
  (`getShadowRadius`/`shouldShowName`/`setupRotations`). Two creature mods went from ~190
  unresolved references to ~40, all in model/render internals.
- **Curated renames now walk the class chain after intermediary translation** — `Mob.moveTo` is
  renamed to `snapTo` because the rule is recorded on `Entity`; previously only direct owners
  matched, and the verifier reported the miss.
- **1.20.1 input.** The intermediary table now carries the 1.20.1 era (5,456 members, 714
  classes), so 1.20.1 jars translate to readable Mojang names and port through the same layers.
- **Compatibility page.** `docs/compat.md` is generated from the harness ledgers: verdict,
  unresolved count, server result, Retromod result and screenshot per mod.
- **Head-to-head with Retromod on 104 mods: Fox-Grade 66, Retromod 37** (29 boot only under Fox-Grade) (`docs/compat.md`, runner in
  `tools/run-h2h.sh`). The fresh ten added chat-event records, numeric permission levels, NbtUtils
  and optional item-stack codecs, recipe ingredients, screen extract events, `super.use` holder
  conversion, Fabric's creative-tab / menu API renames, and inactive-but-accepted shims for the
  removed tint, render-layer, fluid-render, fuel, trade and model-predicate registries (each
  logs once that it is inactive on 26.2).
- The verifier now checks Fabric API classes for existence, verifies members of shims that carry
  game names, and deregisters a mixin whose whole target class is gone.
- The standalone checker reads Fabric's injected interfaces from the installed modules, so
  attachment-API calls no longer show as false misses.
- Fixed: constructor adapters used 1-based slots in two entries and produced a `VerifyError`;
  inner-class shims (`VillagerTrades$ItemListing`, `GameRules$Key`) were never injected.
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
- **World rendering layer.** 26.2 draws the world through a submit API (extract a render state,
  then submit it to a collector) and deleted `MultiBufferSource`, `RenderType`'s factories,
  `LevelRenderer`'s line helpers, `ItemRenderer` and Fabric's `WorldRenderEvents`. Fox-Grade now
  re-creates the old buffer source as a recorder that hands each RenderType's vertices to
  `submitCustomGeometry`; drives 1.21.x entity and block-entity renderers from synthesised
  `extractRenderState`/`submit`/`createRenderState` methods that remember the entity behind the
  state and re-create the old `render(...)` call; keeps 1.21.x models working (root part supplied
  from the model's own constructor, `HierarchicalModel` re-created with keyframe animation,
  `setupAnim` fed from the render state, overrides of now-final `renderToBuffer`/`root` dropped);
  maps `RenderType` factories onto `RenderTypes`; re-implements the line-box and shape helpers;
  routes world text and the global buffer source to the frame being submitted; and serves
  Fabric's `WorldRenderEvents` from 26.2's `LevelRenderEvents`, firing every drawing phase while
  the frame collects submits.
- Verifier: third-party classes Fox-Grade re-creates (Fabric's events) are now checked by whether
  the loader can read them, so their shims are injected. Shim descriptors are compared post-rename.
- More renames: Fabric lifecycle events (`World` → `Level`), `Camera` accessors, `EntityType.Builder.build`,
  `MobEffects.CONFUSION`, `DirectionProperty` → `EnumProperty`.
- World corpus results: Lighty, Better Chunk Borders and Highlight Mobs boot; Light Level Overlay's
  own port is clean but its Architectury library, and every 1.21.x creature mod tried (Naturalist,
  Friends and Foes, the duck mod with GeckoLib), fail before rendering on the entity/item/block API
  rewrite — `ArmorItem`, `InteractionResultHolder`, `FlyingAnimal`, save data through
  `ValueInput`/`ValueOutput`, AI and combat methods that gained a `ServerLevel`. That layer is
  mapped but not built.
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
