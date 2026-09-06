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
}
