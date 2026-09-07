package foxgrade.shim;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** 1.21.x RenderType factories with no same-named 26.2 twin, mapped to the nearest pipeline. */
public final class RenderTypeCompat {
  private RenderTypeCompat() { }
  public static RenderType solid() { return RenderTypes.solidMovingBlock(); }
  public static RenderType cutout() { return RenderTypes.cutoutMovingBlock(); }
  public static RenderType cutoutMipped() { return RenderTypes.cutoutMovingBlock(); }
  public static RenderType translucent() { return RenderTypes.translucentMovingBlock(); }
  public static RenderType lineStrip() { return RenderTypes.lines(); }
  public static RenderType debugLineStrip(double width) { return RenderTypes.lines(); }
  public static RenderType entityCutoutNoCull(Identifier t) { return RenderTypes.entityCutout(t); }
  public static RenderType entityCutoutNoCull(Identifier t, boolean outline) { return RenderTypes.entityCutout(t, outline); }
  public static RenderType entityTranslucentCull(Identifier t) { return RenderTypes.entityTranslucent(t); }
  public static RenderType entityNoOutline(Identifier t) { return RenderTypes.entityTranslucent(t); }
  public static RenderType entitySmoothCutout(Identifier t) { return RenderTypes.entityCutout(t); }
  public static RenderType eyes(Identifier t) { return RenderTypes.entityTranslucentEmissive(t); }
  public static RenderType beaconBeam(Identifier t, boolean translucent) { return RenderTypes.entityTranslucent(t); }
  public static RenderType entityGlint() { return RenderTypes.entityTranslucent(Identifier.withDefaultNamespace("textures/misc/enchanted_glint_entity.png")); }
  public static RenderType glint() { return entityGlint(); }
  public static RenderType textIntensity(Identifier t) { return RenderTypes.text(t); }
  public static RenderType textIntensitySeeThrough(Identifier t) { return RenderTypes.textSeeThrough(t); }

  // 1.21's RenderType.create(name, format, mode, size, [crumbling, sort,] CompositeState): the shard bag is read back
  // into a 26.2 RenderSetup on the nearest vanilla pipeline, so a mod-built glint or entity layer really draws.
  private static final java.util.Map<String, RenderType> CUSTOM = new java.util.concurrent.ConcurrentHashMap<>();
  public static RenderType create(String name, com.mojang.blaze3d.vertex.VertexFormat fmt, VertexFormatModeShim mode, int size, RenderTypeCompositeState st) {
    return create(name, fmt, mode, size, false, false, st);
  }
  public static RenderType create(String name, com.mojang.blaze3d.vertex.VertexFormat fmt, VertexFormatModeShim mode, int size, boolean crumbling, boolean sort, RenderTypeCompositeState st) {
    String key = name + "|" + crumbling + sort + "|" + st.signature();
    return CUSTOM.computeIfAbsent(key, k -> build(name, crumbling, sort, st));
  }
  private static RenderType build(String name, boolean crumbling, boolean sort, RenderTypeCompositeState st) {
    Identifier tex = st.texture();
    String hint = (name + " " + st.texturingName() + " " + st.transparencyName()).toLowerCase(java.util.Locale.ROOT);
    boolean glint = hint.contains("glint");
    String tr = st.transparencyName();
    boolean translucent = tr.contains("translucent") || tr.contains("additive") || tr.contains("lightning");
    if (tex == null && !glint) return translucent ? RenderTypes.debugFilledBox() : RenderTypes.debugQuads();
    com.mojang.blaze3d.pipeline.RenderPipeline pipeline = glint ? net.minecraft.client.renderer.RenderPipelines.GLINT
        : translucent ? (st.cull() ? net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT_CULL : net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT)
        : (st.cull() ? net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_CULL : net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT);
    var b = net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline);
    if (glint) {
      b.withTexture("Sampler0", tex != null ? tex : net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
      b.setTextureTransform(hint.contains("armor") ? net.minecraft.client.renderer.rendertype.TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING
          : hint.contains("entity") ? net.minecraft.client.renderer.rendertype.TextureTransform.ENTITY_GLINT_TEXTURING
          : net.minecraft.client.renderer.rendertype.TextureTransform.GLINT_TEXTURING);
    } else {
      b.withTexture("Sampler0", tex);
      b.useLightmap(); b.useOverlay();   // the entity pipelines bind both samplers; vanilla's own entity types always do
    }
    if (crumbling) b.affectsCrumbling();
    if (sort) b.sortOnUpload();
    if (st.affectsOutline()) b.setOutline(net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty.AFFECTS_OUTLINE);
    String out = st.outputName();
    if (out.contains("item_entity")) b.setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget.ITEM_ENTITY_TARGET);
    else if (out.contains("weather")) b.setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget.WEATHER_TARGET);
    else if (out.contains("outline")) b.setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget.OUTLINE_TARGET);
    if (st.layeringName().contains("view_offset")) b.setLayeringTransform(net.minecraft.client.renderer.rendertype.LayeringTransform.VIEW_OFFSET_Z_LAYERING);
    return make(name, b.createRenderSetup());
  }
  // RenderType.create(String, RenderSetup) is package-private in 26.2 (RenderTypes is its only vanilla caller).
  private static RenderType make(String name, net.minecraft.client.renderer.rendertype.RenderSetup setup) {
    try {
      var m = RenderType.class.getDeclaredMethod("create", String.class, net.minecraft.client.renderer.rendertype.RenderSetup.class);
      m.setAccessible(true);
      return (RenderType) m.invoke(null, name, setup);
    } catch (ReflectiveOperationException e) { throw new IllegalStateException("RenderType.create(String, RenderSetup)", e); }
  }
}
