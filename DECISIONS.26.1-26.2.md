# Decision ledger — what still blocks each mod, and whose move it is

Generated from the retargeted jars under the registration-aware checker.

## amecs
✗ MixinMouse: @At target "Minecraft.screen" is gone from the target version — it moved to net.minecraft.client.gui.Gui
    fix: the @At string needs rewriting at its new home — and if the member became a method, a decision
✗ MixinKeyboard: @At target "Minecraft.screen" is gone from the target version — it moved to net.minecraft.client.gui.Gui
    fix: the @At string needs rewriting at its new home — and if the member became a method, a decision

## architectury-api
✗ MixinGameRenderer: @Inject/@Redirect target "extractGui" was renamed to "gameRenderState" on GameRenderer
    fix: rewrite the annotation's method selector
✗ MixinMouseHandler: @Inject target "screen" is no longer on Minecraft — it moved to net.minecraft.client.gui.Gui
    fix: retarget this mixin at net.minecraft.client.gui.Gui

## asyncparticles
✗ MixinLevelRenderer: @Inject/@Redirect target "extractLevel" existed on LevelRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ MixinParticlesRenderState: target net.minecraft.client.renderer.SubmitNodeCollector$ParticleGroupRenderer does not exist in the target version
✗ MixinParticlesRenderState: @Inject/@Redirect target "submitParticleGroup" existed on SubmitNodeStorage before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ MixinWeatherEffectRenderer: @Inject/@Redirect target "tickRainParticles" existed on WeatherEffectRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite

## badoptimizations
✗ MixinToastComponent: @At target "Options.hideGui" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite

## balm
✗ MinecraftMixin: @At target "Minecraft.screen" is gone from the target version — it moved to net.minecraft.client.gui.Gui
    fix: the @At string needs rewriting at its new home — and if the member became a method, a decision
✗ MinecraftMixin: @Inject target "screen" is no longer on Minecraft — it moved to net.minecraft.client.gui.Gui
    fix: retarget this mixin at net.minecraft.client.gui.Gui
✗ MinecraftMixin: @Inject/@Redirect target "setScreen" was renamed to "setScreenAndShow" on Minecraft
    fix: rewrite the annotation's method selector
✗ LevelRendererMixin: @Inject/@Redirect target "bufferSource" was renamed to "endFrame" on RenderBuffers
    fix: rewrite the annotation's method selector
✗ LevelRendererMixin: target net.minecraft.client.renderer.MultiBufferSource$BufferSource does not exist in the target version
✗ LevelRendererMixin: target net.minecraft.client.renderer.MultiBufferSource does not exist in the target version
✗ KeyboardHandlerMixin: @Inject target "screen" is no longer on Minecraft — it moved to net.minecraft.client.gui.Gui
    fix: retarget this mixin at net.minecraft.client.gui.Gui
✗ MouseHandlerMixin: @Inject target "screen" is no longer on Minecraft — it moved to net.minecraft.client.gui.Gui
    fix: retarget this mixin at net.minecraft.client.gui.Gui

## bclib
✗ AtlasSetMixin: @At target "SpriteSourceList.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite

## bedrockify
✗ LevelRendererMixin: @Inject/@Redirect target "allChanged" was renamed to "resetLevelRenderData" on LevelRenderer
    fix: rewrite the annotation's method selector
✗ ChatComponentMixin: @Inject/@Redirect target "getDebugOverlay" existed on Gui before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ ExperienceBarRendererMixin: target net.minecraft.client.gui.contextualbar.ExperienceBarRenderer does not exist in the target version
✗ GuiRendererMixin: @At target "BlitRenderState.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ ItemInHandRendererMixin: @Inject/@Redirect target "renderHandsWithItems" was renamed to "submitHandsWithItems" on ItemInHandRenderer
    fix: rewrite the annotation's method selector
✗ ExperienceBarRendererMixin: target net.minecraft.client.gui.contextualbar.ExperienceBarRenderer does not exist in the target version
✗ LocatorBarRendererMixin: @At target "LocatorBar.top" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite

## better-block-entities
✗ BlockEntityRenderersAccessor: @Inject/@Redirect target "register" existed on BlockEntityType before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ LevelRendererMixin: @Inject/@Redirect target "clear" existed on SubmitNodeStorage before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ LevelRendererMixin: @Inject/@Redirect target "cullTerrain" existed on LevelRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ LevelRendererMixin: @Inject/@Redirect target "extractLevel" existed on LevelRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ ModelFeatureRendererMixin: target net.minecraft.client.renderer.MultiBufferSource$BufferSource does not exist in the target version
✗ ModelFeatureRendererMixin: @Inject/@Redirect target "clear" existed on SubmitNodeCollection before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ ModelFeatureRendererMixin: target net.minecraft.client.renderer.OutlineBufferSource does not exist in the target version
✗ ModelFeatureRendererMixin: @Inject/@Redirect target "renderTranslucent" existed on ModelFeatureRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ ModelFeatureRendererMixin: @Inject/@Redirect target "renderSolid" existed on ModelFeatureRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ BedBlockEntityMixin: target net.minecraft.world.level.block.entity.BedBlockEntity does not exist in the target version

## better-clouds
✗ GameRendererMixin: @At target "LevelRenderer.renderLevel" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ GameRendererMixin: @Inject/@Redirect target "renderLevel" was renamed to "render" on LevelRenderer
    fix: rewrite the annotation's method selector
✗ WorldRendererMixin: @Inject target "getProfiler" is no longer on Minecraft — it moved to net.minecraft.util.profiling.metrics.profiling.MetricsRecorder
    fix: retarget this mixin at net.minecraft.util.profiling.metrics.profiling.MetricsRecorder
✗ WorldRendererMixin: @Inject/@Redirect target "onResourceManagerReload" existed on LevelRenderer before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ WorldRendererMixin: @Inject target "setLevel" is no longer on LevelRenderer — it moved to net.minecraft.client.renderer.GameRenderer
    fix: retarget this mixin at net.minecraft.client.renderer.GameRenderer
✗ AbstractWidgetMixin: @Inject target "screen" is no longer on Minecraft — it moved to net.minecraft.client.gui.Gui
    fix: retarget this mixin at net.minecraft.client.gui.Gui

## better-f1-reborn
✗ MixinHandRenderer: @At target "Options.hideGui" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ MixinHandRenderer: @Inject/@Redirect target "hideGui" existed on Options before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ GameRendererMixin: @At target "OptionsRenderState.hideGui" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ GameRendererMixin: @Inject/@Redirect target "hideGui" existed on OptionsRenderState before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ KeyboardMixin: @At target "Options.hideGui" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ KeyboardMixin: @Inject/@Redirect target "hideGui" existed on Options before and does not now
    fix: needs a new injection point — a decision, not a rewrite

## betterf3
✗ DebugOptionMixin: @Inject/@Redirect target "hideGui" existed on Options before and does not now
    fix: needs a new injection point — a decision, not a rewrite

## blur-plus
✗ MixinGui: @At target "Minecraft.screen" is gone from the target version — it moved to net.minecraft.client.gui.Gui
    fix: the @At string needs rewriting at its new home — and if the member became a method, a decision
✗ MixinOptions: @At target "OptionInstance$IntRange.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ MixinOptionsScreen: @Inject/@Redirect target "title" existed on Gui before and does not now
    fix: needs a new injection point — a decision, not a rewrite

## bobby
✗ OptionsMixin: @At target "OptionInstance$IntRange.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite

## bookshelf-lib
✗ MixinDecoratedPotPatterns: @Inject/@Redirect target "getPatternFromItem" existed on DecoratedPotPatterns before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ AccessorBlockEntityRenderers: @Inject/@Redirect target "register" existed on BlockEntityType before and does not now
    fix: needs a new injection point — a decision, not a rewrite

## bridging-mod
✗ CrosshairRenderingMixin: @Inject/@Redirect target "hideGui" existed on Options before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ OutlineRendererMixin: target net.minecraft.client.renderer.MultiBufferSource$BufferSource does not exist in the target version

## c2me-fabric
✗ IDensityFunctionsCaveScaler: @Inject/@Redirect target "getSphaghettiRarity2D" existed on NoiseRouterData$QuantizedSpaghettiRarity before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ IDensityFunctionsCaveScaler: @Inject/@Redirect target "getSpaghettiRarity3D" existed on NoiseRouterData$QuantizedSpaghettiRarity before and does not now
    fix: needs a new injection point — a decision, not a rewrite
✗ IDensityFunctionTypesWeirdScaledSamplerRarityValueMapper: target net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler$RarityValueMapper does not exist in the target version
✗ MixinNbtCompound: @At target "CompoundTag.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ MixinNbtList: @At target "ListTag.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite
✗ MixinStorageIoWorker: @At target "PriorityConsecutiveExecutor.<init>" is gone from the target version
    fix: needs a new anchor — a decision, not a rewrite

## ambient-environment — the silent one, investigated to the runtime boundary
Static analysis exonerates everything it can see, in order:
- its one mixin injects `AmbientEnvironmentCommon.init()` at `Minecraft.<init>` TAIL — ctor signature identical in 26.1 and 26.2
- its technique swaps `BiomeColors.GRASS/WATER_COLOR_RESOLVER` statics via an accessor mixin — both fields exist in 26.2
- the vanilla consumer chain is byte-identical across versions: BlockTintSources$2-9 → BiomeColors.getAverage* → ClientLevel tintCaches, resolver statics read in exactly the same two classes
- every link resolves, every mixin target resolves, 0 verify errors

A swapped resolver would work in 26.2 exactly as in 26.1 — yet the noise never appears. The remaining
suspects live only at runtime: whether the injection actually applies (its refmap is unreadable, and a
missed inject with require=0 is silent), or whether init() throws and something swallows it. The probe:
launch porttest with `-Dmixin.debug.verbose=true` and read whether MixinMinecraft and
BiomeColorsAccessor apply. One launch settles it.
