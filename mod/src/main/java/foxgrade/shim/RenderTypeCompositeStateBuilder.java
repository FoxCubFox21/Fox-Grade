package foxgrade.shim;

/** 1.21's {@code RenderType.CompositeState.CompositeStateBuilder}: the fluent setters mods chained before
 *  {@code createCompositeState}. Emitted into the port under the old nested name. */
public final class RenderTypeCompositeStateBuilder {
  private final RenderTypeCompositeState st = new RenderTypeCompositeState();

  public RenderTypeCompositeStateBuilder() { }

  public RenderTypeCompositeStateBuilder setTextureState(RenderStateShardShim.EmptyTextureStateShard s) { st.textureState = s; return this; }
  public RenderTypeCompositeStateBuilder setShaderState(RenderStateShardShim.ShaderStateShard s) { st.shaderState = s; return this; }
  public RenderTypeCompositeStateBuilder setTransparencyState(RenderStateShardShim.TransparencyStateShard s) { st.transparencyState = s; return this; }
  public RenderTypeCompositeStateBuilder setDepthTestState(RenderStateShardShim.DepthTestStateShard s) { st.depthTestState = s; return this; }
  public RenderTypeCompositeStateBuilder setCullState(RenderStateShardShim.CullStateShard s) { st.cullState = s; return this; }
  public RenderTypeCompositeStateBuilder setLightmapState(RenderStateShardShim.LightmapStateShard s) { st.lightmapState = s; return this; }
  public RenderTypeCompositeStateBuilder setOverlayState(RenderStateShardShim.OverlayStateShard s) { st.overlayState = s; return this; }
  public RenderTypeCompositeStateBuilder setLayeringState(RenderStateShardShim.LayeringStateShard s) { st.layeringState = s; return this; }
  public RenderTypeCompositeStateBuilder setOutputState(RenderStateShardShim.OutputStateShard s) { st.outputState = s; return this; }
  public RenderTypeCompositeStateBuilder setTexturingState(RenderStateShardShim.TexturingStateShard s) { st.texturingState = s; return this; }
  public RenderTypeCompositeStateBuilder setWriteMaskState(RenderStateShardShim.WriteMaskStateShard s) { st.writeMaskState = s; return this; }
  public RenderTypeCompositeStateBuilder setLineState(RenderStateShardShim.LineStateShard s) { st.lineState = s; return this; }
  public RenderTypeCompositeStateBuilder setColorLogicState(RenderStateShardShim.ColorLogicStateShard s) { st.colorLogicState = s; return this; }

  public RenderTypeCompositeState createCompositeState(boolean affectsOutline) {
    return createCompositeState(affectsOutline ? RenderTypeOutlineProperty.AFFECTS_OUTLINE : RenderTypeOutlineProperty.NONE);
  }
  public RenderTypeCompositeState createCompositeState(RenderTypeOutlineProperty outline) {
    st.outlineProperty = outline == null ? RenderTypeOutlineProperty.NONE : outline;
    return st;
  }
}
