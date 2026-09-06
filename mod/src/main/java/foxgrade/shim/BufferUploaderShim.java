package foxgrade.shim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * {@code BufferUploader} (removed). 26.2 has no immediate draw: GUI geometry is collected as
 * elements and rendered later. So the mesh a mod just built is decoded (position, colour, uv)
 * and submitted as one element into the current frame's list — the frame being whichever
 * extractor a GuiCompat call or an entry hook saw last. Only QUADS are replayed, through the GUI
 * pipelines; anything else is dropped with a single log line rather than guessed at.
 */
public final class BufferUploaderShim {
  private static boolean warned;

  public static void drawWithShader(MeshData mesh) { draw(mesh); }

  public static void draw(MeshData mesh) {
    try { replay(mesh); }
    catch (Throwable t) { warn("replay failed: " + t); }
    finally { mesh.close(); }
  }

  private static void warn(String why) {
    if (warned) return;
    warned = true;
    System.err.println("[Fox-Grade] immediate-mode draw not replayed (" + why + "); further cases silent");
  }

  private static void replay(MeshData mesh) {
    GuiGraphicsExtractor g = GuiCompat.current();
    if (g == null) { warn("no GUI frame in progress"); return; }
    GuiRenderState state = GuiCompat.renderState(g);
    if (state == null) { warn("render state unreachable"); return; }
    if (TesselatorShim.lastMode != VertexFormatModeShim.QUADS) { warn("mode " + TesselatorShim.lastMode + " unsupported"); return; }
    MeshData.DrawState ds = mesh.drawState();
    VertexFormat fmt = ds.format();
    int n = ds.vertexCount(), stride = fmt.getVertexSize();
    int posOff = -1, colOff = -1, uvOff = -1;
    for (VertexFormatElement e : fmt.getElements()) {
      if (e.name().equals(DefaultVertexFormat.POSITION_SEMANTIC_NAME)) posOff = e.offset();
      else if (e.name().equals(DefaultVertexFormat.COLOR_SEMANTIC_NAME)) colOff = e.offset();
      else if (e.name().equals(DefaultVertexFormat.UV0_SEMANTIC_NAME)) uvOff = e.offset();
    }
    if (posOff < 0 || n == 0) return;
    ByteBuffer vb = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
    Identifier texId = RenderSystemCompat.shaderTexture();
    AbstractTexture tex = uvOff >= 0 && texId != null ? Minecraft.getInstance().getTextureManager().getTexture(texId) : null;
    boolean textured = tex != null;
    float[] xs = new float[n], ys = new float[n], us = textured ? new float[n] : null, vs = textured ? new float[n] : null;
    int[] colors = new int[n];
    for (int i = 0; i < n; i++) {
      int b = i * stride;
      xs[i] = vb.getFloat(b + posOff); ys[i] = vb.getFloat(b + posOff + 4);
      int c = colOff >= 0
          ? ((vb.get(b + colOff + 3) & 255) << 24) | ((vb.get(b + colOff) & 255) << 16) | ((vb.get(b + colOff + 1) & 255) << 8) | (vb.get(b + colOff + 2) & 255)
          : 0xFFFFFFFF;
      colors[i] = GuiCompat.applyTint(c);
      if (textured) { us[i] = vb.getFloat(b + uvOff); vs[i] = vb.getFloat(b + uvOff + 4); }
    }
    RenderPipeline pipeline = textured ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI;
    TextureSetup setup = textured ? TextureSetup.singleTexture(tex.getTextureView(), tex.getSampler()) : TextureSetup.noTexture();
    state.addGuiElement(new GuiQuadElement(pipeline, setup, GuiCompat.scissor(g), xs, ys, us, vs, colors));
  }
}
