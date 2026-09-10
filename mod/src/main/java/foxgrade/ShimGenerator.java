// Synthesize replacement classes for SIMPLE Minecraft classes that were removed in the target
// version. When the verifier finds a ported mod referencing one of these, the shim is injected
// into the ported jar itself — the mod's classloader then resolves the missing name locally and
// the code path works instead of dying with NoClassDefFoundError.
//
// Scope discipline: only self-contained data holders whose behaviour is fully reproducible get
// a shim (Tuple is a plain pair). Anything wired into MC's systems (rendering, registries,
// networking) is NOT shimmable — a stub that pretends would "load and quietly misbehave," which
// Fox-Grade's rules forbid. Grow the table entry by entry, each one reviewed by a person.
package foxgrade;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.function.Supplier;

public final class ShimGenerator implements Opcodes {

  public static final Map<String, Supplier<byte[]>> SHIMS = Map.ofEntries(
      Map.entry("net/minecraft/util/Tuple", ShimGenerator::tuple),
      Map.entry("foxgrade/shim/AutoConfigCompat", ShimGenerator::autoConfigCompat),
      Map.entry("foxgrade/shim/FoodDataCompat", () -> fromResource("foxgrade/shim/FoodDataCompat.class")),
      Map.entry("foxgrade/shim/ReloadListenerCompat", () -> fromResource("foxgrade/shim/ReloadListenerCompat.class")),
      Map.entry("foxgrade/shim/ScreenEventCompat", () -> fromResource("foxgrade/shim/ScreenEventCompat.class")),
      Map.entry("foxgrade/shim/FmlCompat", () -> fromResource("foxgrade/shim/FmlCompat.class")),
      Map.entry("foxgrade/shim/OptionInstanceCompat", () -> fromResource("foxgrade/shim/OptionInstanceCompat.class")),
      Map.entry("foxgrade/shim/CtorShims", () -> fromResource("foxgrade/shim/CtorShims.class")),
      Map.entry("foxgrade/shim/HudRenderCallback", () -> fromResource("foxgrade/shim/HudRenderCallback.class")),
      Map.entry("foxgrade/shim/EntityCompat", () -> fromResource("foxgrade/shim/EntityCompat.class")),
      Map.entry("net/minecraft/util/OptionEnum", ShimGenerator::optionEnum),
      Map.entry("net/minecraft/util/LazyLoadedValue", ShimGenerator::lazyLoadedValue),
      Map.entry("foxgrade/shim/MinecraftCompat", () -> fromResource("foxgrade/shim/MinecraftCompat.class")),
      Map.entry("foxgrade/shim/UtilCompat", ShimGenerator::utilCompat),
      // --- GUI rendering bridges (26.2 GuiGraphicsExtractor / render pipelines) ---
      Map.entry("foxgrade/shim/GuiCompat", () -> fromResource("foxgrade/shim/GuiCompat.class")),
      Map.entry("foxgrade/shim/GuiPoseStack", () -> fromResource("foxgrade/shim/GuiPoseStack.class")),
      Map.entry("foxgrade/shim/GuiQuadElement", () -> fromResource("foxgrade/shim/GuiQuadElement.class")),
      Map.entry("foxgrade/shim/RenderSystemCompat", () -> fromResource("foxgrade/shim/RenderSystemCompat.class")),
      Map.entry("com/mojang/blaze3d/shaders/Uniform", () -> fromResource("foxgrade/shim/UniformShim.class")),
      Map.entry("com/mojang/blaze3d/pipeline/RenderCall", () -> fromResource("foxgrade/shim/RenderCallShim.class")),
      Map.entry("foxgrade/shim/ShaderCompat", () -> fromResource("foxgrade/shim/ShaderCompat.class")),
      Map.entry("foxgrade/shim/InputCompat", () -> fromResource("foxgrade/shim/InputCompat.class")),
      Map.entry("foxgrade/shim/FontCompat", () -> fromResource("foxgrade/shim/FontCompat.class")),
      Map.entry("foxgrade/shim/I18nCompat", () -> fromResource("foxgrade/shim/I18nCompat.class")),
      Map.entry("foxgrade/shim/ChatFormattingCompat", () -> fromResource("foxgrade/shim/ChatFormattingCompat.class")),
      Map.entry("foxgrade/shim/WindowCompat", () -> fromResource("foxgrade/shim/WindowCompat.class")),
      Map.entry("foxgrade/shim/SourceFactorShim", () -> fromResource("foxgrade/shim/SourceFactorShim.class")),
      Map.entry("foxgrade/shim/DestFactorShim", () -> fromResource("foxgrade/shim/DestFactorShim.class")),
      Map.entry("foxgrade/shim/CameraCompat", () -> fromResource("foxgrade/shim/CameraCompat.class")),
      Map.entry("foxgrade/shim/ListFieldCompat", () -> fromResource("foxgrade/shim/ListFieldCompat.class")),
      Map.entry("foxgrade/shim/ScreenCompat", () -> fromResource("foxgrade/shim/ScreenCompat.class")),
      Map.entry("foxgrade/shim/SkinCompat", () -> fromResource("foxgrade/shim/SkinCompat.class")),
      Map.entry("foxgrade/shim/FabricEventsCompat", () -> fromResource("foxgrade/shim/FabricEventsCompat.class")),
      Map.entry("foxgrade/shim/FabricEventsCompat$DeadEvent", () -> fromResource("foxgrade/shim/FabricEventsCompat$DeadEvent.class")),
      Map.entry("foxgrade/shim/OptionsCompat", () -> fromResource("foxgrade/shim/OptionsCompat.class")),
      Map.entry("foxgrade/shim/PlayerCompat", () -> fromResource("foxgrade/shim/PlayerCompat.class")),
      // --- world rendering (26.2 submit API) ---
      Map.entry("foxgrade/shim/RecordingBufferSource", () -> fromResource("foxgrade/shim/RecordingBufferSource.class")),
      Map.entry("foxgrade/shim/RecordingConsumer", () -> fromResource("foxgrade/shim/RecordingConsumer.class")),
      Map.entry("foxgrade/shim/RenderTypeCompat", () -> fromResource("foxgrade/shim/RenderTypeCompat.class")),
      Map.entry("net/minecraft/client/renderer/RenderType$CompositeState", () -> fromResource("foxgrade/shim/RenderTypeCompositeState.class")),
      Map.entry("net/minecraft/client/renderer/RenderType$CompositeState$CompositeStateBuilder", () -> fromResource("foxgrade/shim/RenderTypeCompositeStateBuilder.class")),
      Map.entry("net/minecraft/client/renderer/RenderType$OutlineProperty", () -> fromResource("foxgrade/shim/RenderTypeOutlineProperty.class")),
      Map.entry("foxgrade/shim/LevelRendererCompat", () -> fromResource("foxgrade/shim/LevelRendererCompat.class")),
      Map.entry("foxgrade/shim/LightTextureCompat", () -> fromResource("foxgrade/shim/LightTextureCompat.class")),
      Map.entry("foxgrade/shim/ModelCompat", () -> fromResource("foxgrade/shim/ModelCompat.class")),
      Map.entry("foxgrade/shim/EntityRenderCompat", () -> fromResource("foxgrade/shim/EntityRenderCompat.class")),
      Map.entry("foxgrade/shim/BlockEntityRenderCompat", () -> fromResource("foxgrade/shim/BlockEntityRenderCompat.class")),
      Map.entry("foxgrade/shim/WorldRenderContextImpl", () -> fromResource("foxgrade/shim/WorldRenderContextImpl.class")),
      Map.entry("foxgrade/shim/WorldRenderContextImpl$1", () -> fromResource("foxgrade/shim/WorldRenderContextImpl$1.class")),
      Map.entry("foxgrade/shim/EntityTypeCompat", () -> fromResource("foxgrade/shim/EntityTypeCompat.class")),
      Map.entry("foxgrade/shim/FrameCompat", () -> fromResource("foxgrade/shim/FrameCompat.class")),
      Map.entry("foxgrade/shim/EffectsCompat", () -> fromResource("foxgrade/shim/EffectsCompat.class")),
      // --- entity / item API (1.21.2 – 26.x rewrite) ---
      Map.entry("foxgrade/shim/NbtBridge", () -> fromResource("foxgrade/shim/NbtBridge.class")),
      Map.entry("foxgrade/shim/EntityApiCompat", () -> fromResource("foxgrade/shim/EntityApiCompat.class")),
      Map.entry("foxgrade/shim/EntityLegacyCompat", () -> fromResource("foxgrade/shim/EntityLegacyCompat.class")),
      Map.entry("foxgrade/shim/HolderCompat", () -> fromResource("foxgrade/shim/HolderCompat.class")),
      Map.entry("foxgrade/shim/PackCompat", () -> fromResource("foxgrade/shim/PackCompat.class")),
      Map.entry("foxgrade/shim/QuiltSelfContainer", () -> fromResource("foxgrade/shim/QuiltSelfContainer.class")),
      Map.entry("foxgrade/shim/QuiltSelfContainer$Delegate", () -> fromResource("foxgrade/shim/QuiltSelfContainer$Delegate.class")),
      Map.entry("org/quiltmc/loader/api/Version", () -> fromResource("foxgrade/shim/QuiltVersionShim.class")),
      Map.entry("org/quiltmc/loader/api/ModMetadata", () -> fromResource("foxgrade/shim/QuiltModMetadataShim.class")),
      Map.entry("org/quiltmc/loader/api/ModContainer", () -> fromResource("foxgrade/shim/QuiltModContainerShim.class")),
      Map.entry("org/quiltmc/loader/api/QuiltLoader", () -> fromResource("foxgrade/shim/QuiltLoaderShim.class")),
      Map.entry("org/quiltmc/loader/api/minecraft/MinecraftQuiltLoader", () -> fromResource("foxgrade/shim/MinecraftQuiltLoaderShim.class")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/ModInitializer", () -> fromResource("foxgrade/shim/QuiltModInitializerShim.class")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/client/ClientModInitializer", () -> fromResource("foxgrade/shim/QuiltClientModInitializerShim.class")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/server/DedicatedServerModInitializer", () -> fromResource("foxgrade/shim/QuiltServerModInitializerShim.class")),
      Map.entry("foxgrade/shim/QuiltVersionImpl", () -> fromResource("foxgrade/shim/QuiltVersionImpl.class")),
      Map.entry("foxgrade/shim/QuiltModMetadataImpl", () -> fromResource("foxgrade/shim/QuiltModMetadataImpl.class")),
      Map.entry("foxgrade/shim/QuiltModContainerImpl", () -> fromResource("foxgrade/shim/QuiltModContainerImpl.class")),
      Map.entry("foxgrade/shim/RenderTargetCompat", () -> fromResource("foxgrade/shim/RenderTargetCompat.class")),
      Map.entry("foxgrade/shim/NetworkingCompat", () -> fromResource("foxgrade/shim/NetworkingCompat.class")),
      Map.entry("foxgrade/shim/ChunkCompat", () -> fromResource("foxgrade/shim/ChunkCompat.class")),
      Map.entry("foxgrade/shim/ProcessorTypeCodec", () -> fromResource("foxgrade/shim/ProcessorTypeCodec.class")),
      Map.entry("foxgrade/shim/IoCompat", () -> fromResource("foxgrade/shim/IoCompat.class")),
      Map.entry("foxgrade/shim/RecipeSerializerCompat", () -> fromResource("foxgrade/shim/RecipeSerializerCompat.class")),
      Map.entry("foxgrade/shim/ConditionCompat$Handler", () -> fromResource("foxgrade/shim/ConditionCompat$Handler.class")),
      Map.entry("foxgrade/shim/ConditionCompat", () -> fromResource("foxgrade/shim/ConditionCompat.class")),
      Map.entry("foxgrade/shim/ExecCompat$Wrapped", () -> fromResource("foxgrade/shim/ExecCompat$Wrapped.class")),
      Map.entry("foxgrade/shim/LootTypeCompat", () -> fromResource("foxgrade/shim/LootTypeCompat.class")),
      Map.entry("foxgrade/shim/FabricCompat", () -> fromResource("foxgrade/shim/FabricCompat.class")),
      Map.entry("foxgrade/shim/ExecCompat", () -> fromResource("foxgrade/shim/ExecCompat.class")),
      Map.entry("foxgrade/shim/VersionCompat", () -> fromResource("foxgrade/shim/VersionCompat.class")),
      Map.entry("foxgrade/shim/BufCompat", () -> fromResource("foxgrade/shim/BufCompat.class")),
      Map.entry("foxgrade/shim/CodecCompat", () -> fromResource("foxgrade/shim/CodecCompat.class")),
      Map.entry("foxgrade/shim/PathCompat", () -> fromResource("foxgrade/shim/PathCompat.class")),
      Map.entry("foxgrade/shim/ReloadCompat", () -> fromResource("foxgrade/shim/ReloadCompat.class")),
      Map.entry("foxgrade/shim/RegistryCompat", () -> fromResource("foxgrade/shim/RegistryCompat.class")),
      Map.entry("foxgrade/shim/VillagerCompat", () -> fromResource("foxgrade/shim/VillagerCompat.class")),
      Map.entry("foxgrade/shim/ChatCompat", () -> fromResource("foxgrade/shim/ChatCompat.class")),
      Map.entry("net/minecraft/client/color/block/BlockColor", () -> fromResource("foxgrade/shim/BlockColorShim.class")),
      Map.entry("net/minecraft/client/color/item/ItemColor", () -> fromResource("foxgrade/shim/ItemColorShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry", () -> fromResource("foxgrade/shim/ColorProviderRegistryShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry$Impl", () -> fromResource("foxgrade/shim/ColorProviderRegistryShim$Impl.class")),
      Map.entry("net/fabricmc/fabric/api/blockrenderlayer/v1/BlockRenderLayerMap", () -> fromResource("foxgrade/shim/BlockRenderLayerMapShim.class")),
      Map.entry("net/fabricmc/fabric/api/blockrenderlayer/v1/BlockRenderLayerMap$Impl", () -> fromResource("foxgrade/shim/BlockRenderLayerMapShim$Impl.class")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler", () -> fromResource("foxgrade/shim/FluidRenderHandlerShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/SimpleFluidRenderHandler", () -> fromResource("foxgrade/shim/SimpleFluidRenderHandlerShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry", () -> fromResource("foxgrade/shim/FluidRenderHandlerRegistryShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry$Impl", () -> fromResource("foxgrade/shim/FluidRenderHandlerRegistryShim$Impl.class")),
      Map.entry("net/fabricmc/fabric/api/registry/FuelRegistry", () -> fromResource("foxgrade/shim/FuelRegistryShim.class")),
      Map.entry("net/fabricmc/fabric/api/registry/FuelRegistry$Impl", () -> fromResource("foxgrade/shim/FuelRegistryShim$Impl.class")),
      Map.entry("net/fabricmc/fabric/api/object/builder/v1/trade/TradeOfferHelper", () -> fromResource("foxgrade/shim/TradeOfferHelperShim.class")),
      Map.entry("net/minecraft/client/renderer/item/ClampedItemPropertyFunction", () -> fromResource("foxgrade/shim/ClampedItemPropertyFunctionShim.class")),
      Map.entry("net/minecraft/client/resources/model/ModelResourceLocation", () -> fromResource("foxgrade/shim/ModelResourceLocationShim.class")),
      Map.entry("net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder", () -> fromResource("foxgrade/shim/FabricBrewingRecipeRegistryBuilderShim.class")),
      Map.entry("net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder$BuildCallback", () -> fromResource("foxgrade/shim/FabricBrewingRecipeRegistryBuilderShim$BuildCallback.class")),
      Map.entry("net/minecraft/server/packs/metadata/MetadataSectionSerializer", () -> fromResource("foxgrade/shim/MetadataSectionSerializerShim.class")),
      Map.entry("net/minecraft/resources/ResourceLocation$Serializer", () -> fromResource("foxgrade/shim/IdentifierSerializerShim.class")),
      Map.entry("net/minecraft/world/level/saveddata/SavedData$Factory", () -> fromResource("foxgrade/shim/SavedDataFactoryShim.class")),
      Map.entry("foxgrade/shim/SavedDataCompat", () -> fromResource("foxgrade/shim/SavedDataCompat.class")),
      Map.entry("net/minecraft/util/FastColor", () -> fromResource("foxgrade/shim/FastColorShim.class")),
      Map.entry("net/minecraft/util/FastColor$ARGB32", () -> fromResource("foxgrade/shim/FastColorShim$ARGB32.class")),
      Map.entry("net/minecraft/util/FastColor$ABGR32", () -> fromResource("foxgrade/shim/FastColorShim$ABGR32.class")),
      Map.entry("net/minecraft/client/resources/model/BakedModel", () -> fromResource("foxgrade/shim/BakedModelShim.class")),
      Map.entry("foxgrade/shim/StringSplitterCompat", () -> fromResource("foxgrade/shim/StringSplitterCompat.class")),
      Map.entry("net/minecraft/client/renderer/item/ItemProperties", () -> fromResource("foxgrade/shim/ItemPropertiesShim.class")),
      Map.entry("net/minecraft/client/gui/GuiSpriteManager", () -> fromResource("foxgrade/shim/GuiSpriteManagerShim.class")),
      Map.entry("net/minecraft/client/renderer/texture/Tickable", () -> fromResource("foxgrade/shim/TickableShim.class")),
      Map.entry("net/minecraft/server/packs/BuiltInMetadata", () -> fromResource("foxgrade/shim/BuiltInMetadataShim.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$TransparencyStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$TransparencyStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$TexturingStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$TexturingStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$WriteMaskStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$WriteMaskStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$DepthTestStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$DepthTestStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$CullStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$CullStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$LayeringStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$LayeringStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$OutputStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$OutputStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$LightmapStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$LightmapStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$OverlayStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$OverlayStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$ShaderStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$ShaderStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$EmptyTextureStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$EmptyTextureStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$TextureStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$TextureStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$MultiTextureStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$MultiTextureStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$LineStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$LineStateShard.class")),
      Map.entry("net/minecraft/client/renderer/RenderStateShard$ColorLogicStateShard", () -> fromResource("foxgrade/shim/RenderStateShardShim$ColorLogicStateShard.class")),
      Map.entry("net/minecraft/client/renderer/block/model/ItemOverride", () -> fromResource("foxgrade/shim/ItemOverrideShim.class")),
      Map.entry("net/minecraft/client/renderer/block/model/ItemOverride$Predicate", () -> fromResource("foxgrade/shim/ItemOverrideShim$Predicate.class")),
      Map.entry("net/minecraft/client/renderer/block/model/ItemOverrides", () -> fromResource("foxgrade/shim/ItemOverridesShim.class")),
      Map.entry("net/minecraft/client/renderer/texture/atlas/SpriteSourceType", () -> fromResource("foxgrade/shim/SpriteSourceTypeShim.class")),
      Map.entry("net/minecraft/client/resources/model/AtlasSet", () -> fromResource("foxgrade/shim/AtlasSetShim.class")),
      Map.entry("net/fabricmc/fabric/api/networking/v1/PacketByteBufs", () -> fromResource("foxgrade/shim/PacketByteBufsShim.class")),
      Map.entry("net/minecraft/util/random/Weight", () -> fromResource("foxgrade/shim/WeightShim.class")),
      Map.entry("net/minecraft/util/random/WeightedEntry", () -> fromResource("foxgrade/shim/WeightedEntryShim.class")),
      Map.entry("net/minecraft/util/random/WeightedEntry$Wrapper", () -> fromResource("foxgrade/shim/WeightedEntryShim$Wrapper.class")),
      Map.entry("net/minecraft/util/random/WeightedEntry$IntrusiveBase", () -> fromResource("foxgrade/shim/WeightedEntryShim$IntrusiveBase.class")),
      Map.entry("net/minecraft/util/random/WeightedRandomList", () -> fromResource("foxgrade/shim/WeightedRandomListShim.class")),
      Map.entry("net/minecraft/world/level/storage/loot/entries/LootPoolEntryType", () -> fromResource("foxgrade/shim/LootPoolEntryTypeShim.class")),
      Map.entry("net/minecraft/world/level/storage/loot/functions/LootItemFunctionType", () -> fromResource("foxgrade/shim/LootItemFunctionTypeShim.class")),
      Map.entry("net/minecraft/world/level/storage/loot/predicates/LootItemConditionType", () -> fromResource("foxgrade/shim/LootItemConditionTypeShim.class")),
      Map.entry("net/minecraft/world/entity/FlyingMob", () -> fromResource("foxgrade/shim/FlyingMobShim.class")),
      Map.entry("net/minecraft/client/renderer/ShaderInstance", () -> fromResource("foxgrade/shim/ShaderInstanceShim.class")),
      Map.entry("net/minecraft/advancements/critereon/ItemSubPredicate", () -> fromResource("foxgrade/shim/ItemSubPredicateShim.class")),
      Map.entry("net/minecraft/advancements/critereon/ItemSubPredicate$Type", () -> fromResource("foxgrade/shim/ItemSubPredicateTypeShim.class")),
      Map.entry("foxgrade/shim/ResourceMetadataCompat", () -> fromResource("foxgrade/shim/ResourceMetadataCompat.class")),
      Map.entry("foxgrade/shim/MetadataSectionTypeSerializer", () -> fromResource("foxgrade/shim/MetadataSectionTypeSerializer.class")),
      Map.entry("foxgrade/shim/RegistriesCompat", () -> fromResource("foxgrade/shim/RegistriesCompat.class")),
      Map.entry("net/fabricmc/fabric/api/object/builder/v1/client/model/FabricModelPredicateProviderRegistry", () -> fromResource("foxgrade/shim/FabricModelPredicateProviderRegistryShim.class")),
      Map.entry("foxgrade/shim/PermissionCompat", () -> fromResource("foxgrade/shim/PermissionCompat.class")),
      Map.entry("net/minecraft/world/item/ArmorMaterial$Layer", () -> fromResource("foxgrade/shim/ArmorMaterialLayerShim.class")),
      Map.entry("foxgrade/shim/GameRulesCompat", () -> fromResource("foxgrade/shim/GameRulesCompat.class")),
      Map.entry("foxgrade/shim/ParticleCompat", () -> fromResource("foxgrade/shim/ParticleCompat.class")),
      Map.entry("foxgrade/shim/AnimationCompat", () -> fromResource("foxgrade/shim/AnimationCompat.class")),
      Map.entry("foxgrade/shim/RecipeCompat", () -> fromResource("foxgrade/shim/RecipeCompat.class")),
      Map.entry("foxgrade/shim/BlockApiCompat", () -> fromResource("foxgrade/shim/BlockApiCompat.class")),
      Map.entry("foxgrade/shim/TooltipListShim", () -> fromResource("foxgrade/shim/TooltipListShim.class")),
      Map.entry("net/minecraft/world/level/GameRules$Key", () -> fromResource("foxgrade/shim/GameRulesKeyShim.class")),
      Map.entry("net/minecraft/world/item/Tier", () -> fromResource("foxgrade/shim/TierShim.class")),
      Map.entry("net/minecraft/world/item/SwordItem", () -> fromResource("foxgrade/shim/SwordItemShim.class")),
      Map.entry("net/minecraft/world/item/DiggerItem", () -> fromResource("foxgrade/shim/DiggerItemShim.class")),
      Map.entry("net/minecraft/world/item/ItemNameBlockItem", () -> fromResource("foxgrade/shim/ItemNameBlockItemShim.class")),
      Map.entry("foxgrade/shim/MathCompat", () -> fromResource("foxgrade/shim/MathCompat.class")),
      Map.entry("foxgrade/shim/GoalCompat", () -> fromResource("foxgrade/shim/GoalCompat.class")),
      Map.entry("foxgrade/shim/ItemCompat", () -> fromResource("foxgrade/shim/ItemCompat.class")),
      Map.entry("net/minecraft/world/entity/Saddleable", () -> fromResource("foxgrade/shim/SaddleableShim.class")),
      Map.entry("net/minecraft/world/entity/npc/VillagerTrades$ItemListing", () -> fromResource("foxgrade/shim/ItemListingShim.class")),
      Map.entry("net/minecraft/world/effect/InstantenousMobEffect", () -> fromResource("foxgrade/shim/InstantenousMobEffectShim.class")),
      Map.entry("net/minecraft/world/ItemInteractionResult", () -> fromResource("foxgrade/shim/ItemInteractionResultShim.class")),
      Map.entry("net/minecraft/world/level/block/entity/BlockEntityType$Builder", () -> fromResource("foxgrade/shim/BlockEntityTypeBuilderShim.class")),
      Map.entry("net/minecraft/world/InteractionResultHolder", () -> fromResource("foxgrade/shim/InteractionResultHolderShim.class")),
      Map.entry("net/minecraft/world/item/ArmorItem", () -> fromResource("foxgrade/shim/ArmorItemShim.class")),
      Map.entry("net/minecraft/world/entity/animal/FlyingAnimal", () -> fromResource("foxgrade/shim/FlyingAnimalShim.class")),
      Map.entry("net/minecraft/client/renderer/MultiBufferSource", () -> fromResource("foxgrade/shim/MultiBufferSourceShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry", () -> fromResource("foxgrade/shim/BuiltinItemRendererRegistryShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry$DynamicItemRenderer", () -> fromResource("foxgrade/shim/BuiltinItemRendererRegistryShim$DynamicItemRenderer.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry$Impl", () -> fromResource("foxgrade/shim/BuiltinItemRendererRegistryShim$Impl.class")),
      Map.entry("net/minecraft/client/renderer/MultiBufferSource$BufferSource", () -> fromResource("foxgrade/shim/BufferSourceShim.class")),
      Map.entry("net/minecraft/client/model/HierarchicalModel", () -> fromResource("foxgrade/shim/HierarchicalModelShim.class")),
      Map.entry("net/minecraft/client/renderer/entity/ItemRenderer", () -> fromResource("foxgrade/shim/ItemRendererShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents", () -> fromResource("foxgrade/shim/WorldRenderEventsShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext", () -> fromResource("foxgrade/shim/WorldRenderContextShim.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$Start", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$Start.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$AfterSetup", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$AfterSetup.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$BeforeEntities", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$BeforeEntities.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$AfterEntities", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$AfterEntities.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$BeforeBlockOutline", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$BeforeBlockOutline.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$BlockOutline", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$BlockOutline.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$DebugRender", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$DebugRender.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$AfterTranslucent", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$AfterTranslucent.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$Last", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$Last.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents$End", () -> fromResource("foxgrade/shim/WorldRenderEventsShim$End.class")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext$BlockOutlineContext", () -> fromResource("foxgrade/shim/WorldRenderContextShim$BlockOutlineContextShim.class")),
      Map.entry("foxgrade/shim/NbtCompat", () -> fromResource("foxgrade/shim/NbtCompat.class")),
      Map.entry("foxgrade/shim/InteractionCompat", () -> fromResource("foxgrade/shim/InteractionCompat.class")),
      Map.entry("foxgrade/shim/SoundCompat", () -> fromResource("foxgrade/shim/SoundCompat.class")),
      Map.entry("foxgrade/shim/ImageCompat", () -> fromResource("foxgrade/shim/ImageCompat.class")),
      Map.entry("foxgrade/shim/TextureCompat", () -> fromResource("foxgrade/shim/TextureCompat.class")),
      Map.entry("foxgrade/shim/LevelCompat", () -> fromResource("foxgrade/shim/LevelCompat.class")),
      Map.entry("foxgrade/shim/ToastCompat", () -> fromResource("foxgrade/shim/ToastCompat.class")),
      Map.entry("foxgrade/shim/TooltipCompat", () -> fromResource("foxgrade/shim/TooltipCompat.class")),
      Map.entry("foxgrade/shim/WidgetCompat", () -> fromResource("foxgrade/shim/WidgetCompat.class")),
      Map.entry("foxgrade/shim/ListCompat", ShimGenerator::listCompat),
      Map.entry("foxgrade/shim/BlocksCompat", () -> fromResource("foxgrade/shim/BlocksCompat.class")),
      // Removed Minecraft classes re-created at their own names: written as ordinary Java under
      // foxgrade.shim and renamed on the way in, so Fox-Grade's own jar never carries a class in
      // a Mojang package.
      Map.entry("com/mojang/blaze3d/vertex/Tesselator", () -> fromResource("foxgrade/shim/TesselatorShim.class")),
      Map.entry("com/mojang/blaze3d/vertex/BufferUploader", () -> fromResource("foxgrade/shim/BufferUploaderShim.class")),
      Map.entry("com/mojang/blaze3d/vertex/VertexFormat$Mode", () -> fromResource("foxgrade/shim/VertexFormatModeShim.class"))
  );

  // Shims compiled under a foxgrade.shim name that must land under a Minecraft name.
  static final Map<String, String> SHIM_RENAMES = renames();
  private static Map<String, String> renames() {
    Map<String, String> m = new java.util.HashMap<>();
    m.put("foxgrade/shim/TesselatorShim", "com/mojang/blaze3d/vertex/Tesselator");
    m.put("foxgrade/shim/BufferUploaderShim", "com/mojang/blaze3d/vertex/BufferUploader");
    m.put("foxgrade/shim/VertexFormatModeShim", "com/mojang/blaze3d/vertex/VertexFormat$Mode");
    m.put("foxgrade/shim/MultiBufferSourceShim", "net/minecraft/client/renderer/MultiBufferSource");
    m.put("foxgrade/shim/BuiltinItemRendererRegistryShim", "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry");
    m.put("foxgrade/shim/BuiltinItemRendererRegistryShim$DynamicItemRenderer", "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry$DynamicItemRenderer");
    m.put("foxgrade/shim/BuiltinItemRendererRegistryShim$Impl", "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry$Impl");
    m.put("foxgrade/shim/BufferSourceShim", "net/minecraft/client/renderer/MultiBufferSource$BufferSource");
    m.put("foxgrade/shim/HierarchicalModelShim", "net/minecraft/client/model/HierarchicalModel");
    m.put("foxgrade/shim/ItemRendererShim", "net/minecraft/client/renderer/entity/ItemRenderer");
    m.put("foxgrade/shim/InteractionResultHolderShim", "net/minecraft/world/InteractionResultHolder");
    m.put("foxgrade/shim/ArmorItemShim", "net/minecraft/world/item/ArmorItem");
    m.put("foxgrade/shim/FlyingAnimalShim", "net/minecraft/world/entity/animal/FlyingAnimal");
    m.put("foxgrade/shim/SaddleableShim", "net/minecraft/world/entity/Saddleable");
    m.put("foxgrade/shim/ItemListingShim", "net/minecraft/world/entity/npc/VillagerTrades$ItemListing");
    m.put("foxgrade/shim/InstantenousMobEffectShim", "net/minecraft/world/effect/InstantenousMobEffect");
    m.put("foxgrade/shim/ItemInteractionResultShim", "net/minecraft/world/ItemInteractionResult");
    m.put("foxgrade/shim/BlockEntityTypeBuilderShim", "net/minecraft/world/level/block/entity/BlockEntityType$Builder");
    m.put("foxgrade/shim/ArmorMaterialLayerShim", "net/minecraft/world/item/ArmorMaterial$Layer");
    m.put("foxgrade/shim/BlockColorShim", "net/minecraft/client/color/block/BlockColor");
    m.put("foxgrade/shim/ItemColorShim", "net/minecraft/client/color/item/ItemColor");
    m.put("foxgrade/shim/ColorProviderRegistryShim", "net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry");
    m.put("foxgrade/shim/ColorProviderRegistryShim$Impl", "net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry$Impl");
    m.put("foxgrade/shim/BlockRenderLayerMapShim", "net/fabricmc/fabric/api/blockrenderlayer/v1/BlockRenderLayerMap");
    m.put("foxgrade/shim/BlockRenderLayerMapShim$Impl", "net/fabricmc/fabric/api/blockrenderlayer/v1/BlockRenderLayerMap$Impl");
    m.put("foxgrade/shim/FluidRenderHandlerShim", "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler");
    m.put("foxgrade/shim/SimpleFluidRenderHandlerShim", "net/fabricmc/fabric/api/client/render/fluid/v1/SimpleFluidRenderHandler");
    m.put("foxgrade/shim/FluidRenderHandlerRegistryShim", "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry");
    m.put("foxgrade/shim/FluidRenderHandlerRegistryShim$Impl", "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry$Impl");
    m.put("foxgrade/shim/FuelRegistryShim", "net/fabricmc/fabric/api/registry/FuelRegistry");
    m.put("foxgrade/shim/FuelRegistryShim$Impl", "net/fabricmc/fabric/api/registry/FuelRegistry$Impl");
    m.put("foxgrade/shim/TradeOfferHelperShim", "net/fabricmc/fabric/api/object/builder/v1/trade/TradeOfferHelper");
    m.put("foxgrade/shim/ClampedItemPropertyFunctionShim", "net/minecraft/client/renderer/item/ClampedItemPropertyFunction");
    m.put("foxgrade/shim/ModelResourceLocationShim", "net/minecraft/client/resources/model/ModelResourceLocation");
    m.put("foxgrade/shim/FabricBrewingRecipeRegistryBuilderShim", "net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder");
    m.put("foxgrade/shim/FabricBrewingRecipeRegistryBuilderShim$BuildCallback", "net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder$BuildCallback");
    m.put("foxgrade/shim/MetadataSectionSerializerShim", "net/minecraft/server/packs/metadata/MetadataSectionSerializer");
    m.put("foxgrade/shim/IdentifierSerializerShim", "net/minecraft/resources/ResourceLocation$Serializer");
    m.put("foxgrade/shim/SavedDataFactoryShim", "net/minecraft/world/level/saveddata/SavedData$Factory");
    m.put("foxgrade/shim/FastColorShim", "net/minecraft/util/FastColor");
    m.put("foxgrade/shim/FastColorShim$ARGB32", "net/minecraft/util/FastColor$ARGB32");
    m.put("foxgrade/shim/FastColorShim$ABGR32", "net/minecraft/util/FastColor$ABGR32");
    m.put("foxgrade/shim/BakedModelShim", "net/minecraft/client/resources/model/BakedModel");
    m.put("foxgrade/shim/ItemPropertiesShim", "net/minecraft/client/renderer/item/ItemProperties");
    m.put("foxgrade/shim/GuiSpriteManagerShim", "net/minecraft/client/gui/GuiSpriteManager");
    m.put("foxgrade/shim/TickableShim", "net/minecraft/client/renderer/texture/Tickable");
    m.put("foxgrade/shim/BuiltInMetadataShim", "net/minecraft/server/packs/BuiltInMetadata");
    m.put("foxgrade/shim/RenderStateShardShim", "net/minecraft/client/renderer/RenderStateShard");
    m.put("foxgrade/shim/RenderCallShim", "com/mojang/blaze3d/pipeline/RenderCall");
    m.put("foxgrade/shim/UniformShim", "com/mojang/blaze3d/shaders/Uniform");
    m.put("foxgrade/shim/QuiltVersionShim", "org/quiltmc/loader/api/Version");
    m.put("foxgrade/shim/QuiltModMetadataShim", "org/quiltmc/loader/api/ModMetadata");
    m.put("foxgrade/shim/QuiltModContainerShim", "org/quiltmc/loader/api/ModContainer");
    m.put("foxgrade/shim/QuiltLoaderShim", "org/quiltmc/loader/api/QuiltLoader");
    m.put("foxgrade/shim/MinecraftQuiltLoaderShim", "org/quiltmc/loader/api/minecraft/MinecraftQuiltLoader");
    m.put("foxgrade/shim/QuiltModInitializerShim", "org/quiltmc/qsl/base/api/entrypoint/ModInitializer");
    m.put("foxgrade/shim/QuiltClientModInitializerShim", "org/quiltmc/qsl/base/api/entrypoint/client/ClientModInitializer");
    m.put("foxgrade/shim/QuiltServerModInitializerShim", "org/quiltmc/qsl/base/api/entrypoint/server/DedicatedServerModInitializer");
    m.put("foxgrade/shim/RenderTypeCompositeState", "net/minecraft/client/renderer/RenderType$CompositeState");
    m.put("foxgrade/shim/RenderTypeCompositeStateBuilder", "net/minecraft/client/renderer/RenderType$CompositeState$CompositeStateBuilder");
    m.put("foxgrade/shim/RenderTypeOutlineProperty", "net/minecraft/client/renderer/RenderType$OutlineProperty");
    m.put("foxgrade/shim/RenderStateShardShim$TransparencyStateShard", "net/minecraft/client/renderer/RenderStateShard$TransparencyStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$TexturingStateShard", "net/minecraft/client/renderer/RenderStateShard$TexturingStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$WriteMaskStateShard", "net/minecraft/client/renderer/RenderStateShard$WriteMaskStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$DepthTestStateShard", "net/minecraft/client/renderer/RenderStateShard$DepthTestStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$CullStateShard", "net/minecraft/client/renderer/RenderStateShard$CullStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$LayeringStateShard", "net/minecraft/client/renderer/RenderStateShard$LayeringStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$OutputStateShard", "net/minecraft/client/renderer/RenderStateShard$OutputStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$LightmapStateShard", "net/minecraft/client/renderer/RenderStateShard$LightmapStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$OverlayStateShard", "net/minecraft/client/renderer/RenderStateShard$OverlayStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$ShaderStateShard", "net/minecraft/client/renderer/RenderStateShard$ShaderStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$EmptyTextureStateShard", "net/minecraft/client/renderer/RenderStateShard$EmptyTextureStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$TextureStateShard", "net/minecraft/client/renderer/RenderStateShard$TextureStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$MultiTextureStateShard", "net/minecraft/client/renderer/RenderStateShard$MultiTextureStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$LineStateShard", "net/minecraft/client/renderer/RenderStateShard$LineStateShard");
    m.put("foxgrade/shim/RenderStateShardShim$ColorLogicStateShard", "net/minecraft/client/renderer/RenderStateShard$ColorLogicStateShard");
    m.put("foxgrade/shim/ItemOverrideShim", "net/minecraft/client/renderer/block/model/ItemOverride");
    m.put("foxgrade/shim/ItemOverrideShim$Predicate", "net/minecraft/client/renderer/block/model/ItemOverride$Predicate");
    m.put("foxgrade/shim/ItemOverridesShim", "net/minecraft/client/renderer/block/model/ItemOverrides");
    m.put("foxgrade/shim/SpriteSourceTypeShim", "net/minecraft/client/renderer/texture/atlas/SpriteSourceType");
    m.put("foxgrade/shim/AtlasSetShim", "net/minecraft/client/resources/model/AtlasSet");
    m.put("foxgrade/shim/PacketByteBufsShim", "net/fabricmc/fabric/api/networking/v1/PacketByteBufs");
    m.put("foxgrade/shim/WeightShim", "net/minecraft/util/random/Weight");
    m.put("foxgrade/shim/WeightedEntryShim", "net/minecraft/util/random/WeightedEntry");
    m.put("foxgrade/shim/WeightedEntryShim$Wrapper", "net/minecraft/util/random/WeightedEntry$Wrapper");
    m.put("foxgrade/shim/WeightedEntryShim$IntrusiveBase", "net/minecraft/util/random/WeightedEntry$IntrusiveBase");
    m.put("foxgrade/shim/WeightedRandomListShim", "net/minecraft/util/random/WeightedRandomList");
    m.put("foxgrade/shim/LootPoolEntryTypeShim", "net/minecraft/world/level/storage/loot/entries/LootPoolEntryType");
    m.put("foxgrade/shim/LootItemFunctionTypeShim", "net/minecraft/world/level/storage/loot/functions/LootItemFunctionType");
    m.put("foxgrade/shim/LootItemConditionTypeShim", "net/minecraft/world/level/storage/loot/predicates/LootItemConditionType");
    m.put("foxgrade/shim/FlyingMobShim", "net/minecraft/world/entity/FlyingMob");
    m.put("foxgrade/shim/ShaderInstanceShim", "net/minecraft/client/renderer/ShaderInstance");
    m.put("foxgrade/shim/ItemSubPredicateShim", "net/minecraft/advancements/critereon/ItemSubPredicate");
    m.put("foxgrade/shim/ItemSubPredicateTypeShim", "net/minecraft/advancements/critereon/ItemSubPredicate$Type");
    m.put("foxgrade/shim/FabricModelPredicateProviderRegistryShim", "net/fabricmc/fabric/api/object/builder/v1/client/model/FabricModelPredicateProviderRegistry");
    m.put("foxgrade/shim/GameRulesKeyShim", "net/minecraft/world/level/GameRules$Key");
    m.put("foxgrade/shim/TierShim", "net/minecraft/world/item/Tier");
    m.put("foxgrade/shim/SwordItemShim", "net/minecraft/world/item/SwordItem");
    m.put("foxgrade/shim/DiggerItemShim", "net/minecraft/world/item/DiggerItem");
    m.put("foxgrade/shim/ItemNameBlockItemShim", "net/minecraft/world/item/ItemNameBlockItem");
    String fr = "net/fabricmc/fabric/api/client/rendering/v1/";
    m.put("foxgrade/shim/WorldRenderEventsShim", fr + "WorldRenderEvents");
    for (String n : new String[]{"Start", "AfterSetup", "BeforeEntities", "AfterEntities", "BeforeBlockOutline", "BlockOutline", "DebugRender", "AfterTranslucent", "Last", "End"})
      m.put("foxgrade/shim/WorldRenderEventsShim$" + n, fr + "WorldRenderEvents$" + n);
    m.put("foxgrade/shim/WorldRenderContextShim", fr + "WorldRenderContext");
    m.put("foxgrade/shim/WorldRenderContextShim$BlockOutlineContextShim", fr + "WorldRenderContext$BlockOutlineContext");
    return Map.copyOf(m);
  }

  // Shims that reference other shims; the pipeline injects the closure.
  static final Map<String, java.util.List<String>> SHIM_DEPS = Map.ofEntries(
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry", java.util.List.of("net/minecraft/client/renderer/MultiBufferSource")),
      Map.entry("net/minecraft/client/renderer/ShaderInstance", java.util.List.of("com/mojang/blaze3d/shaders/Uniform")),
      Map.entry("foxgrade/shim/QuiltSelfContainer", java.util.List.of("org/quiltmc/loader/api/ModContainer", "org/quiltmc/loader/api/QuiltLoader", "foxgrade/shim/QuiltModContainerImpl", "foxgrade/shim/QuiltSelfContainer$Delegate")),
      Map.entry("org/quiltmc/loader/api/Version", java.util.List.of("foxgrade/shim/QuiltVersionImpl")),
      Map.entry("foxgrade/shim/QuiltVersionImpl", java.util.List.of("org/quiltmc/loader/api/Version")),
      Map.entry("org/quiltmc/loader/api/ModMetadata", java.util.List.of("org/quiltmc/loader/api/Version")),
      Map.entry("foxgrade/shim/QuiltModMetadataImpl", java.util.List.of("org/quiltmc/loader/api/ModMetadata", "org/quiltmc/loader/api/Version", "foxgrade/shim/QuiltVersionImpl")),
      Map.entry("org/quiltmc/loader/api/ModContainer", java.util.List.of("org/quiltmc/loader/api/ModMetadata")),
      Map.entry("foxgrade/shim/QuiltModContainerImpl", java.util.List.of("org/quiltmc/loader/api/ModContainer", "org/quiltmc/loader/api/ModMetadata", "foxgrade/shim/QuiltModMetadataImpl")),
      Map.entry("org/quiltmc/loader/api/QuiltLoader", java.util.List.of("org/quiltmc/loader/api/ModContainer", "foxgrade/shim/QuiltModContainerImpl")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/ModInitializer", java.util.List.of("foxgrade/shim/QuiltSelfContainer", "org/quiltmc/loader/api/QuiltLoader", "org/quiltmc/loader/api/ModContainer")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/client/ClientModInitializer", java.util.List.of("foxgrade/shim/QuiltSelfContainer", "org/quiltmc/loader/api/QuiltLoader", "org/quiltmc/loader/api/ModContainer")),
      Map.entry("org/quiltmc/qsl/base/api/entrypoint/server/DedicatedServerModInitializer", java.util.List.of("foxgrade/shim/QuiltSelfContainer", "org/quiltmc/loader/api/QuiltLoader", "org/quiltmc/loader/api/ModContainer")),
      Map.entry("net/minecraft/server/packs/BuiltInMetadata", java.util.List.of("net/minecraft/server/packs/metadata/MetadataSectionSerializer")),
      Map.entry("net/minecraft/client/renderer/RenderType$CompositeState", java.util.List.of("net/minecraft/client/renderer/RenderType$CompositeState$CompositeStateBuilder", "net/minecraft/client/renderer/RenderType$OutlineProperty", "net/minecraft/client/renderer/RenderStateShard")),
      Map.entry("net/minecraft/client/renderer/RenderType$CompositeState$CompositeStateBuilder", java.util.List.of("net/minecraft/client/renderer/RenderType$CompositeState", "net/minecraft/client/renderer/RenderType$OutlineProperty", "net/minecraft/client/renderer/RenderStateShard")),
      Map.entry("foxgrade/shim/RenderTypeCompat", java.util.List.of("net/minecraft/client/renderer/RenderType$CompositeState", "com/mojang/blaze3d/vertex/VertexFormat$Mode", "net/minecraft/client/renderer/RenderStateShard")),
      Map.entry("foxgrade/shim/GuiCompat", java.util.List.of("foxgrade/shim/GuiPoseStack")),
      Map.entry("foxgrade/shim/RenderSystemCompat", java.util.List.of("com/mojang/blaze3d/pipeline/RenderCall", "foxgrade/shim/GuiCompat", "com/mojang/blaze3d/vertex/Tesselator", "foxgrade/shim/SourceFactorShim", "foxgrade/shim/DestFactorShim")),
      Map.entry("foxgrade/shim/HudRenderCallback", java.util.List.of("foxgrade/shim/GuiCompat")),
      Map.entry("foxgrade/shim/TooltipCompat", java.util.List.of("foxgrade/shim/GuiCompat")),
      Map.entry("foxgrade/shim/ScreenCompat", java.util.List.of("foxgrade/shim/GuiCompat")),
      Map.entry("foxgrade/shim/ListCompat", java.util.List.of("foxgrade/shim/ListFieldCompat")),
      Map.entry("com/mojang/blaze3d/vertex/Tesselator", java.util.List.of("com/mojang/blaze3d/vertex/VertexFormat$Mode")),
      Map.entry("com/mojang/blaze3d/vertex/BufferUploader", java.util.List.of("foxgrade/shim/GuiCompat", "foxgrade/shim/RenderSystemCompat",
          "foxgrade/shim/GuiQuadElement", "com/mojang/blaze3d/vertex/Tesselator", "com/mojang/blaze3d/vertex/VertexFormat$Mode")),
      Map.entry("foxgrade/shim/RecordingBufferSource", java.util.List.of("foxgrade/shim/RecordingConsumer", "net/minecraft/client/renderer/MultiBufferSource")),
      Map.entry("net/minecraft/client/renderer/MultiBufferSource", java.util.List.of("foxgrade/shim/RecordingBufferSource")),
      Map.entry("foxgrade/shim/EntityRenderCompat", java.util.List.of("foxgrade/shim/RecordingBufferSource")),
      Map.entry("foxgrade/shim/FrameCompat", java.util.List.of("foxgrade/shim/RecordingBufferSource")),
      Map.entry("foxgrade/shim/EntityApiCompat", java.util.List.of("foxgrade/shim/NbtBridge")),
      Map.entry("foxgrade/shim/GameRulesCompat", java.util.List.of("net/minecraft/world/level/GameRules$Key")),
      Map.entry("foxgrade/shim/ItemCompat", java.util.List.of("foxgrade/shim/TooltipListShim", "foxgrade/shim/RegistryCompat")),
      Map.entry("foxgrade/shim/BlockApiCompat", java.util.List.of("foxgrade/shim/RegistryCompat")),
      Map.entry("foxgrade/shim/RegistryCompat", java.util.List.of("net/minecraft/world/item/ArmorMaterial$Layer")),
      Map.entry("foxgrade/shim/ResourceMetadataCompat", java.util.List.of("net/minecraft/server/packs/metadata/MetadataSectionSerializer", "foxgrade/shim/MetadataSectionTypeSerializer")),
      Map.entry("foxgrade/shim/MetadataSectionTypeSerializer", java.util.List.of("net/minecraft/server/packs/metadata/MetadataSectionSerializer")),
      Map.entry("foxgrade/shim/SavedDataCompat", java.util.List.of("net/minecraft/world/level/saveddata/SavedData$Factory")),
      Map.entry("net/minecraft/client/resources/model/BakedModel", java.util.List.of("net/minecraft/client/renderer/block/model/ItemOverrides")),
      Map.entry("net/minecraft/client/renderer/item/ItemProperties", java.util.List.of("net/minecraft/client/renderer/item/ClampedItemPropertyFunction")),
      Map.entry("net/minecraft/util/random/WeightedRandomList", java.util.List.of("net/minecraft/util/random/WeightedEntry", "net/minecraft/util/random/Weight")),
      Map.entry("net/minecraft/util/random/WeightedEntry", java.util.List.of("net/minecraft/util/random/Weight")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/SimpleFluidRenderHandler", java.util.List.of("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler")),
      Map.entry("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry", java.util.List.of("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler")),
      Map.entry("net/fabricmc/fabric/api/object/builder/v1/client/model/FabricModelPredicateProviderRegistry", java.util.List.of("net/minecraft/client/renderer/item/ClampedItemPropertyFunction")),
      Map.entry("net/minecraft/world/item/SwordItem", java.util.List.of("net/minecraft/world/item/Tier")),
      Map.entry("net/minecraft/world/item/DiggerItem", java.util.List.of("net/minecraft/world/item/Tier")),
      Map.entry("foxgrade/shim/AnimationCompat", java.util.List.of("net/minecraft/client/model/HierarchicalModel")),
      Map.entry("foxgrade/shim/NbtBridge", java.util.List.of("foxgrade/shim/PlayerCompat")),
      Map.entry("foxgrade/shim/InteractionCompat", java.util.List.of("net/minecraft/world/InteractionResultHolder")),
      Map.entry("foxgrade/shim/BlockEntityRenderCompat", java.util.List.of("foxgrade/shim/RecordingBufferSource")),
      Map.entry("net/minecraft/client/renderer/entity/ItemRenderer", java.util.List.of("foxgrade/shim/RecordingBufferSource")),
      Map.entry("net/minecraft/client/model/HierarchicalModel", java.util.List.of("foxgrade/shim/ModelCompat")),
      Map.entry("foxgrade/shim/WorldRenderContextImpl", java.util.List.of("foxgrade/shim/WorldRenderContextImpl$1", "foxgrade/shim/RecordingBufferSource", "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext")),
      Map.entry("net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents", java.util.List.of("foxgrade/shim/WorldRenderContextImpl", "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext")));

  /** An empty class standing in for a type the target deleted outright.
   *
   *  <p>Some deletions have no successor: 26.2 replaced NeoForge's "hand the handler two lists of strings" debug
   *  overlay event with "register debug entries", which is a different idea rather than a renamed class. A mod built
   *  against the old one then dies before it starts — not inside the feature that went away, but at class load,
   *  because the loader reflects over the mod looking for handlers and one parameter type will not resolve.
   *
   *  <p>This is the smallest thing that lets the rest of the mod run. The type resolves, the loader finishes reading
   *  the class, and the handler is never called, because nothing posts an event of a type the game no longer has.
   *  There is no constructor, so nothing can make one by accident, and the methods return nothing of their own —
   *  they exist so a call site verifies, not so it works. The port report names the feature that went inert.
   *
   *  <p>The supertype is real and checked by the generator. An event bus asks what you register to extend Event, and
   *  a stand-in that only claimed to would be the loads-and-misbehaves case this project refuses. */
  static byte[] standIn(String name, String superName, java.util.List<String> interfaces, java.util.List<String> methods) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, interfaces.toArray(new String[0]));
    for (String sig : methods) {
      int paren = sig.indexOf('(');
      if (paren <= 0) continue;
      String desc = sig.substring(paren);
      MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, sig.substring(0, paren), desc, null, null);
      mv.visitCode();
      org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(desc);
      switch (ret.getSort()) {
        case org.objectweb.asm.Type.VOID -> mv.visitInsn(RETURN);
        case org.objectweb.asm.Type.LONG -> { mv.visitInsn(LCONST_0); mv.visitInsn(LRETURN); }
        case org.objectweb.asm.Type.FLOAT -> { mv.visitInsn(FCONST_0); mv.visitInsn(FRETURN); }
        case org.objectweb.asm.Type.DOUBLE -> { mv.visitInsn(DCONST_0); mv.visitInsn(DRETURN); }
        case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> { mv.visitInsn(ACONST_NULL); mv.visitInsn(ARETURN); }
        default -> { mv.visitInsn(ICONST_0); mv.visitInsn(IRETURN); }
      }
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }
    cw.visitEnd();
    return cw.toByteArray();
  }

  static byte[] renameClasses(byte[] bytes, Map<String, String> map) {
    org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(bytes);
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    r.accept(new org.objectweb.asm.commons.ClassRemapper(w, new org.objectweb.asm.commons.SimpleRemapper(map)), 0);
    return w.toByteArray();
  }

  // Shims with real logic are written as normal Java inside Fox-Grade and copied into the ported
  // jar from Fox-Grade's own class resources — no hand-rolled ASM for anything non-trivial.
  /** The Minecraft version currently being ported for, so shims can be taken from that version's set.
   *
   *  <p>A shim is a compiled class, and a class compiled against one Minecraft is not valid on another — it names
   *  methods and types that version has. Fox-Grade therefore builds the shim package once per target it supports and
   *  ships each set under {@code foxgrade/shimset/<version>/}. The set for the version Fox-Grade was primarily built
   *  against is the unprefixed one, so this costs nothing on the common path. */
  private static volatile String targetMc = "";

  static void targetVersion(String mc) { targetMc = mc == null ? "" : mc; }

  private static final Map<String, Boolean> HAS_SET = new java.util.concurrent.ConcurrentHashMap<>();

  /** Whether this target has a shim set of its own, rather than sharing the one built alongside the engine. */
  /** A version turned into something that can be a Java package segment.
   *
   *  <p>"26.1.2" cannot: a package part may not start with a digit or contain a dot, and shipping classes under
   *  foxgrade/shimset/26.1.2/ makes the jar unreadable to anything that validates module packages. Forge does, and
   *  refused Fox-Grade outright — "Invalid package name: '26' is not a Java identifier" — before any mod loaded. */
  private static String setDir(String mc) {
    return "v" + Targets.tables(mc).replaceAll("[^A-Za-z0-9]", "_");
  }

  private static boolean hasOwnSet() {
    if (targetMc.isEmpty()) return false;
    return HAS_SET.computeIfAbsent(targetMc, (v) -> resource("/foxgrade/shimset/" + setDir(v) + "/UNAVAILABLE.txt") != null);
  }

  /** True when this shim has no build for the target version, so the reference must stay unresolved.
   *
   *  <p>Answered by trying, not by consulting a list. The build writes out the source files it could not compile for
   *  a version, but a shim's source file is not the name it is injected under — VertexFormatModeShim.java is emitted
   *  as com/mojang/blaze3d/vertex/VertexFormat$Mode — and matching those two by hand is how a shim built against 26.2,
   *  referencing a class 26.1.2 never had, ended up inside a 26.1.2 port and took the game down inside
   *  RenderSystem.initRenderer. {@link #fromResource} refuses to serve a version from another version's set, so the
   *  supplier throws, the caller records the reference as unresolved, and the port report names it. */
  /** Every {@code foxgrade/shim/...} class this shim's own bytecode names, plus its inner classes.
   *
   *  <p>SHIM_DEPS is written by hand and therefore incomplete by nature — it listed GuiPoseStack for GuiCompat and
   *  not FrameCompat, and had no way at all to mention ChunkCompat's anonymous inner class, which is not a shim in
   *  its own right. Both were referenced by injected shims and present in Fox-Grade's jar as resources, and neither
   *  reached a single port. Asking the class file what it refers to cannot forget. */
  /** Whether Fox-Grade ships this exact class as a resource, registered as a shim or not. */
  static boolean hasResource(String cls) {
    return cls.startsWith("foxgrade/shim/") && resource("/" + cls + ".class") != null;
  }

  /** Those bytes, with the standard shim renames applied, ready to inject. */
  static byte[] resourceBytes(String cls) {
    byte[] b = resource("/" + cls + ".class");
    if (b == null) throw new IllegalStateException("no resource for " + cls);
    return renameClasses(b, SHIM_RENAMES);
  }

  static java.util.Set<String> shimRefsOf(String shimCls) {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    byte[] bytes;
    try {
      bytes = SHIMS.containsKey(shimCls) ? SHIMS.get(shimCls).get() : resource("/" + shimCls + ".class");
    } catch (RuntimeException notAvailable) {
      return out;
    }
    if (bytes == null) return out;
    for (String s : ConstantPool.strings(bytes)) {
      if (!s.startsWith("foxgrade/shim/") || s.equals(shimCls) || s.endsWith(";")) continue;
      // Only names Fox-Grade can actually supply; anything else would add a reference rather than resolve one.
      if (SHIMS.containsKey(s) || resource("/" + s + ".class") != null) out.add(s);
    }
    // An inner class is not referenced by name from its outer in every case, so ask for it directly.
    for (int i = 1; i <= 8; i++) {
      String inner = shimCls + "$" + i;
      if (!SHIMS.containsKey(inner) && resource("/" + inner + ".class") != null) out.add(inner);
    }
    return out;
  }

  static boolean unavailableHere(String shimCls) {
    // Every shim, whether copied from a resource or generated here, is written in Mojang names, because that is what
    // 26.x runs in. A target that loads through intermediary resolves none of them: the shim itself would fail to
    // link, and it would take the mod that needed it down with it. Until there is a shim set built and remapped for
    // those versions, the reference stays unresolved and the port report names it — which someone can act on, and
    // which is what Fox-Grade promises to do with anything it cannot prove safe.
    return !Targets.namespace(targetMc).equals("official");
  }


  private static byte[] fromResource(String path) {
    byte[] bytes;
    if (hasOwnSet()) {
      // A version with its own set is served only from it. Falling back to the set built alongside the engine would
      // put a class compiled against a different Minecraft into the port, which is the failure this exists to avoid.
      bytes = resource("/foxgrade/shimset/" + setDir(targetMc) + "/" + path);
      if (bytes == null) throw new IllegalStateException("no " + path + " built for " + targetMc);
    } else {
      bytes = resource("/" + path);
      if (bytes == null) throw new IllegalStateException("missing shim resource " + path);
    }
    return renameClasses(bytes, SHIM_RENAMES);
  }

  private static byte[] resource(String path) {
    try (var in = ShimGenerator.class.getResourceAsStream(path)) {
      return in == null ? null : in.readAllBytes();
    } catch (java.io.IOException e) { return null; }
  }

  // Replacement for cloth-config's removed AutoConfig.getGuiRegistry(Class): returns a fresh
  // GuiRegistry. The mod's custom GUI providers land in an orphan registry instead of the shared
  // one — the config screen renders with default widgets, everything else works. Honest trade:
  // degraded config UI beats a NoSuchMethodError at init.
  private static byte[] autoConfigCompat() {
    String name = "foxgrade/shim/AutoConfigCompat";
    String reg = "me/shedaniel/autoconfig/gui/registry/GuiRegistry";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getGuiRegistry",
        "(Ljava/lang/Class;)L" + reg + ";", null, null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, reg);
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, reg, "<init>", "()V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // Util.backgroundExecutor() kept its name but now returns TracingExecutor instead of
  // ExecutorService; TracingExecutor.service() hands back exactly what pre-26.x callers expect.
  // Generated as bytecode rather than written in Java because javac 25 silently refuses to read
  // 26.2's Util.class (it reports "cannot find symbol" with no further diagnostic, while javap
  // and the running game are both fine with it). ASM has no such opinion.
  private static byte[] utilCompat() {
    String name = "foxgrade/shim/UtilCompat";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "backgroundExecutor",
        "()Ljava/util/concurrent/ExecutorService;", null, null);
    mv.visitCode();
    mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Util", "backgroundExecutor",
        "()Lnet/minecraft/TracingExecutor;", false);
    mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/TracingExecutor", "service",
        "()Ljava/util/concurrent/ExecutorService;", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.OptionEnum, removed in 26.x: a pure data contract (numeric id +
  // translation key) that mod enums implement for cycle-button options. Nothing in current MC
  // consumes it, so a faithful interface — including the original's default getCaption() —
  // restores every use the mod itself makes.
  // net.minecraft.util.LazyLoadedValue<T>: a memoising Supplier wrapper Mojang dropped from the
  // 26.x utilities. Semantics preserved exactly: the factory runs once, on first get(), then is
  // released.
  private static byte[] lazyLoadedValue() {
    String name = "net/minecraft/util/LazyLoadedValue";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, "<T:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
    cw.visitField(ACC_PRIVATE, "factory", "Ljava/util/function/Supplier;", "Ljava/util/function/Supplier<TT;>;", null).visitEnd();
    cw.visitField(ACC_PRIVATE, "value", "Ljava/lang/Object;", "TT;", null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;)V", "(Ljava/util/function/Supplier<TT;>;)V", null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
    mv.visitFieldInsn(PUTFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    // A vanilla field that used to be typed LazyLoadedValue is a Supplier on 26.2 — InputConstants.Key.displayName
    // is the one that matters, because Jade reads it. The field redirect reads it at its real type and calls this
    // to hand back something the mod's own code still understands, rather than rewriting the field to a shim type
    // the class does not declare and failing with NoSuchFieldError.
    MethodVisitor wrap = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wrap",
        "(Ljava/util/function/Supplier;)Lnet/minecraft/util/LazyLoadedValue;", null, null);
    wrap.visitCode();
    wrap.visitTypeInsn(NEW, name); wrap.visitInsn(DUP);
    wrap.visitVarInsn(ALOAD, 0);
    wrap.visitMethodInsn(INVOKESPECIAL, name, "<init>", "(Ljava/util/function/Supplier;)V", false);
    wrap.visitInsn(ARETURN); wrap.visitMaxs(0, 0); wrap.visitEnd();

    mv = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", "()TT;", null);
    mv.visitCode();
    org.objectweb.asm.Label done = new org.objectweb.asm.Label();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitVarInsn(ASTORE, 1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, done);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
    mv.visitFieldInsn(PUTFIELD, name, "value", "Ljava/lang/Object;");
    mv.visitVarInsn(ALOAD, 0); mv.visitInsn(ACONST_NULL);
    mv.visitFieldInsn(PUTFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitLabel(done);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, name, "value", "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // List helpers 1.21.x mods called on their own AbstractSelectionList subclasses. Written in
  // bytecode because Entry is a protected nested class javac will not let a shim name; the JVM
  // resolves it by its class file, which is public.
  private static byte[] listCompat() {
    String name = "foxgrade/shim/ListCompat", list = "net/minecraft/client/gui/components/AbstractSelectionList";
    String entry = "L" + list + "$Entry;", asl = "L" + list + ";";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getEntry", "(" + asl + "I)" + entry, null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ILOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
    mv.visitTypeInsn(CHECKCAST, list + "$Entry"); mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "remove", "(" + asl + "I)" + entry, null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ILOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "remove", "(I)Ljava/lang/Object;", true);
    mv.visitTypeInsn(CHECKCAST, list + "$Entry"); mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "removeEntry", "(" + asl + entry + ")Z", null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z", true);
    mv.visitInsn(IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "updateScrollingState", "(" + asl + "DDI)V", null, null);
    mv.visitCode(); mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static byte[] optionEnum() {
    String name = "net/minecraft/util/OptionEnum";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getId", "()I", null, null).visitEnd();
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getKey", "()Ljava/lang/String;", null, null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getCaption", "()Lnet/minecraft/network/chat/Component;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEINTERFACE, name, "getKey", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/network/chat/Component", "translatable",
        "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.Tuple<A, B>: the classic pair. Generic types erase to Object, so the
  // erased bytecode below satisfies every call the original accepted.
  private static byte[] tuple() {
    String name = "net/minecraft/util/Tuple";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name,
        "<A:Ljava/lang/Object;B:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
    cw.visitField(ACC_PRIVATE, "a", "Ljava/lang/Object;", "TA;", null).visitEnd();
    cw.visitField(ACC_PRIVATE, "b", "Ljava/lang/Object;", "TB;", null).visitEnd();

    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", "(TA;TB;)V", null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1); mv.visitFieldInsn(PUTFIELD, name, "a", "Ljava/lang/Object;");
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 2); mv.visitFieldInsn(PUTFIELD, name, "b", "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();

    getter(cw, name, "getA", "a"); setter(cw, name, "setA", "a");
    getter(cw, name, "getB", "b"); setter(cw, name, "setB", "b");
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static void getter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "()Ljava/lang/Object;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }

  private static void setter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "(Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitFieldInsn(PUTFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }
}
