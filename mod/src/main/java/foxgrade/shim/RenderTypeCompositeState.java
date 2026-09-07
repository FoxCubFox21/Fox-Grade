package foxgrade.shim;

import net.minecraft.resources.Identifier;

/** 1.21's {@code RenderType.CompositeState}: the bag of shards a custom render type was assembled from. Emitted into the
 *  port under the old name; {@link RenderTypeCompat#create} reads it back into a 26.2 RenderSetup. */
public final class RenderTypeCompositeState {
  public RenderStateShardShim.EmptyTextureStateShard textureState = RenderStateShardShim.NO_TEXTURE;
  public RenderStateShardShim.ShaderStateShard shaderState = RenderStateShardShim.NO_SHADER;
  public RenderStateShardShim.TransparencyStateShard transparencyState = RenderStateShardShim.NO_TRANSPARENCY;
  public RenderStateShardShim.DepthTestStateShard depthTestState = RenderStateShardShim.LEQUAL_DEPTH_TEST;
  public RenderStateShardShim.CullStateShard cullState = RenderStateShardShim.CULL;
  public RenderStateShardShim.LightmapStateShard lightmapState = RenderStateShardShim.NO_LIGHTMAP;
  public RenderStateShardShim.OverlayStateShard overlayState = RenderStateShardShim.NO_OVERLAY;
  public RenderStateShardShim.LayeringStateShard layeringState = RenderStateShardShim.NO_LAYERING;
  public RenderStateShardShim.OutputStateShard outputState = RenderStateShardShim.MAIN_TARGET;
  public RenderStateShardShim.TexturingStateShard texturingState = RenderStateShardShim.DEFAULT_TEXTURING;
  public RenderStateShardShim.WriteMaskStateShard writeMaskState = RenderStateShardShim.COLOR_DEPTH_WRITE;
  public RenderStateShardShim.LineStateShard lineState = RenderStateShardShim.DEFAULT_LINE;
  public RenderStateShardShim.ColorLogicStateShard colorLogicState = RenderStateShardShim.NO_COLOR_LOGIC;
  public RenderTypeOutlineProperty outlineProperty = RenderTypeOutlineProperty.NONE;

  public static RenderTypeCompositeStateBuilder builder() { return new RenderTypeCompositeStateBuilder(); }

  public Identifier texture() { return textureState == null ? null : textureState.texture; }
  public String texturingName() { return texturingState == null ? "" : String.valueOf(texturingState.getName()); }
  public String transparencyName() { return transparencyState == null ? "" : String.valueOf(transparencyState.getName()); }
  public String outputName() { return outputState == null ? "" : String.valueOf(outputState.getName()); }
  public String layeringName() { return layeringState == null ? "" : String.valueOf(layeringState.getName()); }
  public boolean cull() { return cullState == null || cullState.cull; }
  public boolean lightmap() { return lightmapState != null && lightmapState.on; }
  public boolean overlay() { return overlayState != null && overlayState.on; }
  public boolean affectsOutline() { return outlineProperty != null && outlineProperty != RenderTypeOutlineProperty.NONE; }

  /** Everything RenderTypeCompat.create keys its cache on. */
  public String signature() {
    return texture() + "|" + texturingName() + "|" + transparencyName() + "|" + outputName() + "|" + layeringName() + "|"
        + cull() + lightmap() + overlay() + affectsOutline();
  }

  @Override public String toString() { return "CompositeState[" + signature() + "]"; }
}
