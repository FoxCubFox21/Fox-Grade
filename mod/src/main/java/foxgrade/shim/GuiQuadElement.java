package foxgrade.shim;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/** Captured immediate-mode quads, re-emitted into the 26.2 GUI element list. */
public final class GuiQuadElement implements GuiElementRenderState {
  private static final Matrix3x2f IDENTITY = new Matrix3x2f();
  private final RenderPipeline pipeline;
  private final TextureSetup texture;
  private final ScreenRectangle scissor;
  private final float[] xs, ys, us, vs;
  private final int[] colors;
  private final ScreenRectangle bounds;

  GuiQuadElement(RenderPipeline pipeline, TextureSetup texture, ScreenRectangle scissor, float[] xs, float[] ys, float[] us, float[] vs, int[] colors) {
    this.pipeline = pipeline; this.texture = texture; this.scissor = scissor;
    this.xs = xs; this.ys = ys; this.us = us; this.vs = vs; this.colors = colors;
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (int i = 0; i < xs.length; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]); }
    this.bounds = xs.length == 0 ? new ScreenRectangle(0, 0, 0, 0)
        : new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY));
  }

  @Override public RenderPipeline pipeline() { return pipeline; }
  @Override public TextureSetup textureSetup() { return texture; }
  @Override public ScreenRectangle scissorArea() { return scissor; }
  @Override public ScreenRectangle bounds() { return bounds; }
  @Override public void buildVertices(VertexConsumer vc) {
    // Vertices were transformed by the mod through its (forwarded) PoseStack already: identity pose.
    for (int i = 0; i < xs.length; i++) {
      VertexConsumer v = vc.addVertexWith2DPose(IDENTITY, xs[i], ys[i]);
      if (us != null) v.setUv(us[i], vs[i]);
      v.setColor(colors[i]);
    }
  }
}
