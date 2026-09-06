package foxgrade.shim;

/**
 * The static shader getters of 1.21.x {@code GameRenderer} (getPositionTexShader and kin),
 * reachable only through method references — {@code RenderSystem.setShader(GameRenderer::x)}.
 * ShaderInstance no longer exists; the lambda's types are erased to Object by the handle
 * rewrite, and setShader itself is a no-op, so null is never dereferenced.
 */
public final class ShaderCompat {
  private ShaderCompat() { }
  public static Object getParticleShader() { return null; }
  public static Object getPositionColorLightmapShader() { return null; }
  public static Object getPositionColorShader() { return null; }
  public static Object getPositionColorTexLightmapShader() { return null; }
  public static Object getPositionShader() { return null; }
  public static Object getPositionTexColorShader() { return null; }
  public static Object getPositionTexShader() { return null; }
  public static Object getRendertypeArmorCutoutNoCullShader() { return null; }
  public static Object getRendertypeArmorEntityGlintShader() { return null; }
  public static Object getRendertypeArmorGlintShader() { return null; }
  public static Object getRendertypeBeaconBeamShader() { return null; }
  public static Object getRendertypeBreezeWindShader() { return null; }
  public static Object getRendertypeCloudsShader() { return null; }
  public static Object getRendertypeCrumblingShader() { return null; }
  public static Object getRendertypeCutoutMippedShader() { return null; }
  public static Object getRendertypeCutoutShader() { return null; }
  public static Object getRendertypeEndGatewayShader() { return null; }
  public static Object getRendertypeEndPortalShader() { return null; }
  public static Object getRendertypeEnergySwirlShader() { return null; }
  public static Object getRendertypeEntityAlphaShader() { return null; }
  public static Object getRendertypeEntityCutoutNoCullShader() { return null; }
  public static Object getRendertypeEntityCutoutNoCullZOffsetShader() { return null; }
  public static Object getRendertypeEntityCutoutShader() { return null; }
  public static Object getRendertypeEntityDecalShader() { return null; }
  public static Object getRendertypeEntityGlintDirectShader() { return null; }
  public static Object getRendertypeEntityGlintShader() { return null; }
  public static Object getRendertypeEntityNoOutlineShader() { return null; }
  public static Object getRendertypeEntityShadowShader() { return null; }
  public static Object getRendertypeEntitySmoothCutoutShader() { return null; }
  public static Object getRendertypeEntitySolidShader() { return null; }
  public static Object getRendertypeEntityTranslucentCullShader() { return null; }
  public static Object getRendertypeEntityTranslucentEmissiveShader() { return null; }
  public static Object getRendertypeEntityTranslucentShader() { return null; }
  public static Object getRendertypeEyesShader() { return null; }
  public static Object getRendertypeGlintDirectShader() { return null; }
  public static Object getRendertypeGlintShader() { return null; }
  public static Object getRendertypeGlintTranslucentShader() { return null; }
  public static Object getRendertypeGuiGhostRecipeOverlayShader() { return null; }
  public static Object getRendertypeGuiOverlayShader() { return null; }
  public static Object getRendertypeGuiShader() { return null; }
  public static Object getRendertypeGuiTextHighlightShader() { return null; }
  public static Object getRendertypeItemEntityTranslucentCullShader() { return null; }
  public static Object getRendertypeLeashShader() { return null; }
  public static Object getRendertypeLightningShader() { return null; }
  public static Object getRendertypeLinesShader() { return null; }
  public static Object getRendertypeOutlineShader() { return null; }
  public static Object getRendertypeSolidShader() { return null; }
  public static Object getRendertypeTextBackgroundSeeThroughShader() { return null; }
  public static Object getRendertypeTextBackgroundShader() { return null; }
  public static Object getRendertypeTextIntensitySeeThroughShader() { return null; }
  public static Object getRendertypeTextIntensityShader() { return null; }
  public static Object getRendertypeTextSeeThroughShader() { return null; }
  public static Object getRendertypeTextShader() { return null; }
  public static Object getRendertypeTranslucentMovingBlockShader() { return null; }
  public static Object getRendertypeTranslucentShader() { return null; }
  public static Object getRendertypeTripwireShader() { return null; }
  public static Object getRendertypeWaterMaskShader() { return null; }
  public static Object getShader() { return null; }
}
