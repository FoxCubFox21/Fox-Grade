package foxgrade.shim;

/** Type stand-ins for 1.21's {@code RenderStateShard} family, gone with the 26.2 render-pipeline rewrite. Mods that built
 *  custom render types from them (enchantment glint layers) load; the shards themselves do nothing, since nothing in 26.2
 *  drives them. */
public class RenderStateShardShim {
  protected final String name; protected final Runnable setupState, clearState;
  public RenderStateShardShim(String name, Runnable setup, Runnable clear) { this.name = name; this.setupState = setup; this.clearState = clear; }
  public void setupRenderState() { }
  public void clearRenderState() { }
  public String getName() { return name; }
  @Override public String toString() { return name; }

  public static class TransparencyStateShard extends RenderStateShardShim { public TransparencyStateShard(String n, Runnable s, Runnable c) { super(n, s, c); } }
  public static class TexturingStateShard extends RenderStateShardShim { public TexturingStateShard(String n, Runnable s, Runnable c) { super(n, s, c); } }
  public static class WriteMaskStateShard extends RenderStateShardShim { public WriteMaskStateShard(boolean color, boolean depth) { super("write_mask", () -> { }, () -> { }); } }
  public static class DepthTestStateShard extends RenderStateShardShim { public DepthTestStateShard(String n, int func) { super(n, () -> { }, () -> { }); } }
  public static class CullStateShard extends RenderStateShardShim { public final boolean cull; public CullStateShard(boolean cull) { super("cull", () -> { }, () -> { }); this.cull = cull; } }
  public static class LayeringStateShard extends RenderStateShardShim { public LayeringStateShard(String n, Runnable s, Runnable c) { super(n, s, c); } }
  public static class OutputStateShard extends RenderStateShardShim { public OutputStateShard(String n, Runnable s, Runnable c) { super(n, s, c); } }
  public static class LightmapStateShard extends RenderStateShardShim { public final boolean on; public LightmapStateShard(boolean on) { super("lightmap", () -> { }, () -> { }); this.on = on; } }
  public static class OverlayStateShard extends RenderStateShardShim { public final boolean on; public OverlayStateShard(boolean on) { super("overlay", () -> { }, () -> { }); this.on = on; } }
  public static class ShaderStateShard extends RenderStateShardShim {
    public ShaderStateShard() { super("shader", () -> { }, () -> { }); }
    public ShaderStateShard(java.util.function.Supplier<?> shader) { super("shader", () -> { }, () -> { }); }
  }
  public static class EmptyTextureStateShard extends RenderStateShardShim {
    /** The bound texture, when the shard names one; RenderTypeCompat.create rebuilds the 26.2 setup from it. */
    public net.minecraft.resources.Identifier texture;
    public EmptyTextureStateShard(Runnable s, Runnable c) { super("texture", s, c); }
    public EmptyTextureStateShard() { super("texture", () -> { }, () -> { }); }
    public java.util.Optional<net.minecraft.resources.Identifier> cutoutTexture() { return java.util.Optional.ofNullable(texture); }
  }
  public static class TextureStateShard extends EmptyTextureStateShard {
    public TextureStateShard(net.minecraft.resources.Identifier texture, boolean blur, boolean mipmap) { super(); this.texture = texture; }
    public TextureStateShard(net.minecraft.resources.Identifier texture, Object filter, boolean mipmap) { super(); this.texture = texture; }
  }
  public static class MultiTextureStateShard extends EmptyTextureStateShard { public MultiTextureStateShard(Object entries) { super(); } }
  public static class LineStateShard extends RenderStateShardShim { public LineStateShard(java.util.OptionalDouble width) { super("line_width", () -> { }, () -> { }); } }
  public static class ColorLogicStateShard extends RenderStateShardShim { public ColorLogicStateShard(String n, Runnable s, Runnable c) { super(n, s, c); } }

  // The 1.21 constant shards, so getstatic RenderStateShard.X resolves. Names carry the meaning RenderTypeCompat reads back.
  private static Runnable nop() { return () -> { }; }
  public static final TransparencyStateShard NO_TRANSPARENCY = new TransparencyStateShard("no_transparency", nop(), nop());
  public static final TransparencyStateShard ADDITIVE_TRANSPARENCY = new TransparencyStateShard("additive_transparency", nop(), nop());
  public static final TransparencyStateShard LIGHTNING_TRANSPARENCY = new TransparencyStateShard("lightning_transparency", nop(), nop());
  public static final TransparencyStateShard GLINT_TRANSPARENCY = new TransparencyStateShard("glint_transparency", nop(), nop());
  public static final TransparencyStateShard CRUMBLING_TRANSPARENCY = new TransparencyStateShard("crumbling_transparency", nop(), nop());
  public static final TransparencyStateShard TRANSLUCENT_TRANSPARENCY = new TransparencyStateShard("translucent_transparency", nop(), nop());
  public static final EmptyTextureStateShard NO_TEXTURE = new EmptyTextureStateShard();
  public static final TexturingStateShard DEFAULT_TEXTURING = new TexturingStateShard("default_texturing", nop(), nop());
  public static final TexturingStateShard GLINT_TEXTURING = new TexturingStateShard("glint_texturing", nop(), nop());
  public static final TexturingStateShard ENTITY_GLINT_TEXTURING = new TexturingStateShard("entity_glint_texturing", nop(), nop());
  public static final TexturingStateShard ARMOR_ENTITY_GLINT_TEXTURING = new TexturingStateShard("armor_entity_glint_texturing", nop(), nop());
  public static final LightmapStateShard LIGHTMAP = new LightmapStateShard(true);
  public static final LightmapStateShard NO_LIGHTMAP = new LightmapStateShard(false);
  public static final OverlayStateShard OVERLAY = new OverlayStateShard(true);
  public static final OverlayStateShard NO_OVERLAY = new OverlayStateShard(false);
  public static final CullStateShard CULL = new CullStateShard(true);
  public static final CullStateShard NO_CULL = new CullStateShard(false);
  public static final DepthTestStateShard NO_DEPTH_TEST = new DepthTestStateShard("always", 519);
  public static final DepthTestStateShard EQUAL_DEPTH_TEST = new DepthTestStateShard("==", 514);
  public static final DepthTestStateShard LEQUAL_DEPTH_TEST = new DepthTestStateShard("<=", 515);
  public static final DepthTestStateShard GREATER_DEPTH_TEST = new DepthTestStateShard(">", 516);
  public static final WriteMaskStateShard COLOR_DEPTH_WRITE = new WriteMaskStateShard(true, true);
  public static final WriteMaskStateShard COLOR_WRITE = new WriteMaskStateShard(true, false);
  public static final WriteMaskStateShard DEPTH_WRITE = new WriteMaskStateShard(false, true);
  public static final LayeringStateShard NO_LAYERING = new LayeringStateShard("no_layering", nop(), nop());
  public static final LayeringStateShard POLYGON_OFFSET_LAYERING = new LayeringStateShard("polygon_offset_layering", nop(), nop());
  public static final LayeringStateShard VIEW_OFFSET_Z_LAYERING = new LayeringStateShard("view_offset_z_layering", nop(), nop());
  public static final OutputStateShard MAIN_TARGET = new OutputStateShard("main_target", nop(), nop());
  public static final OutputStateShard OUTLINE_TARGET = new OutputStateShard("outline_target", nop(), nop());
  public static final OutputStateShard TRANSLUCENT_TARGET = new OutputStateShard("translucent_target", nop(), nop());
  public static final OutputStateShard PARTICLES_TARGET = new OutputStateShard("particles_target", nop(), nop());
  public static final OutputStateShard WEATHER_TARGET = new OutputStateShard("weather_target", nop(), nop());
  public static final OutputStateShard CLOUDS_TARGET = new OutputStateShard("clouds_target", nop(), nop());
  public static final OutputStateShard ITEM_ENTITY_TARGET = new OutputStateShard("item_entity_target", nop(), nop());
  public static final LineStateShard DEFAULT_LINE = new LineStateShard(java.util.OptionalDouble.of(1.0));
  public static final ColorLogicStateShard NO_COLOR_LOGIC = new ColorLogicStateShard("no_color_logic", nop(), nop());
  public static final ColorLogicStateShard OR_REVERSE_COLOR_LOGIC = new ColorLogicStateShard("or_reverse", nop(), nop());
  public static final ShaderStateShard NO_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_COLOR_LIGHTMAP_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_COLOR_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_TEX_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_TEX_COLOR_SHADER = new ShaderStateShard();
  public static final ShaderStateShard POSITION_COLOR_TEX_LIGHTMAP_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_SOLID_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_CUTOUT_MIPPED_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_CUTOUT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TRANSLUCENT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_SOLID_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_NO_CULL_Z_OFFSET_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ITEM_ENTITY_TRANSLUCENT_CULL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_SMOOTH_CUTOUT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_BEACON_BEAM_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_DECAL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_NO_OUTLINE_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_SHADOW_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_ALPHA_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_EYES_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENERGY_SWIRL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_LEASH_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_WATER_MASK_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_OUTLINE_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ARMOR_ENTITY_GLINT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GLINT_TRANSLUCENT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GLINT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_GLINT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_ENTITY_GLINT_DIRECT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_CRUMBLING_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_BACKGROUND_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_INTENSITY_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_SEE_THROUGH_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_BACKGROUND_SEE_THROUGH_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TEXT_INTENSITY_SEE_THROUGH_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_LIGHTNING_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_TRIPWIRE_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_END_PORTAL_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_END_GATEWAY_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_CLOUDS_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_LINES_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GUI_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GUI_OVERLAY_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GUI_TEXT_HIGHLIGHT_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_GUI_GHOST_RECIPE_OVERLAY_SHADER = new ShaderStateShard();
  public static final ShaderStateShard RENDERTYPE_BREEZE_WIND_SHADER = new ShaderStateShard();
}
