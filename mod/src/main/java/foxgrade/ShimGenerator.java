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
      Map.entry("foxgrade/shim/OptionsCompat", () -> fromResource("foxgrade/shim/OptionsCompat.class")),
      Map.entry("foxgrade/shim/PlayerCompat", () -> fromResource("foxgrade/shim/PlayerCompat.class")),
      // --- world rendering (26.2 submit API) ---
      Map.entry("foxgrade/shim/RecordingBufferSource", () -> fromResource("foxgrade/shim/RecordingBufferSource.class")),
      Map.entry("foxgrade/shim/RecordingConsumer", () -> fromResource("foxgrade/shim/RecordingConsumer.class")),
      Map.entry("foxgrade/shim/RenderTypeCompat", () -> fromResource("foxgrade/shim/RenderTypeCompat.class")),
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
      Map.entry("foxgrade/shim/PathCompat", () -> fromResource("foxgrade/shim/PathCompat.class")),
      Map.entry("foxgrade/shim/ReloadCompat", () -> fromResource("foxgrade/shim/ReloadCompat.class")),
      Map.entry("foxgrade/shim/RegistryCompat", () -> fromResource("foxgrade/shim/RegistryCompat.class")),
      Map.entry("foxgrade/shim/VillagerCompat", () -> fromResource("foxgrade/shim/VillagerCompat.class")),
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
      Map.entry("foxgrade/shim/GuiCompat", java.util.List.of("foxgrade/shim/GuiPoseStack")),
      Map.entry("foxgrade/shim/RenderSystemCompat", java.util.List.of("foxgrade/shim/GuiCompat", "com/mojang/blaze3d/vertex/Tesselator", "foxgrade/shim/SourceFactorShim", "foxgrade/shim/DestFactorShim")),
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

  static byte[] renameClasses(byte[] bytes, Map<String, String> map) {
    org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(bytes);
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    r.accept(new org.objectweb.asm.commons.ClassRemapper(w, new org.objectweb.asm.commons.SimpleRemapper(map)), 0);
    return w.toByteArray();
  }

  // Shims with real logic are written as normal Java inside Fox-Grade and copied into the ported
  // jar from Fox-Grade's own class resources — no hand-rolled ASM for anything non-trivial.
  private static byte[] fromResource(String path) {
    try (var in = ShimGenerator.class.getResourceAsStream("/" + path)) {
      if (in == null) throw new IllegalStateException("missing shim resource " + path);
      return renameClasses(in.readAllBytes(), SHIM_RENAMES);
    } catch (java.io.IOException e) { throw new RuntimeException(e); }
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
