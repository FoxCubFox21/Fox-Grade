package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * Things 1.21.x code reached for globally during world rendering: the shared buffer source
 * (Minecraft.renderBuffers().bufferSource()) and world-space text (Font.drawInBatch). Both are
 * answered from whichever frame is being submitted right now.
 */
public final class FrameCompat {
  private FrameCompat() { }
  public static RenderBuffers renderBuffers(Minecraft mc) { return null; }
  public static BufferSourceShim bufferSource(RenderBuffers unused) {
    RecordingBufferSource cur = RecordingBufferSource.current();
    return cur != null ? cur : new RecordingBufferSource(null, null);
  }

  public static int drawInBatch(Font f, String s, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSourceShim b, Font.DisplayMode mode, int bg, int light) {
    return drawInBatch(f, FormattedCharSequence.forward(s, net.minecraft.network.chat.Style.EMPTY), x, y, color, shadow, m, b, mode, bg, light);
  }
  public static int drawInBatch(Font f, String s, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSourceShim b, Font.DisplayMode mode, int bg, int light, boolean inverse) {
    return drawInBatch(f, s, x, y, color, shadow, m, b, mode, bg, light);
  }
  public static int drawInBatch(Font f, Component c, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSourceShim b, Font.DisplayMode mode, int bg, int light) {
    return drawInBatch(f, c.getVisualOrderText(), x, y, color, shadow, m, b, mode, bg, light);
  }
  public static int drawInBatch(Font f, FormattedCharSequence seq, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSourceShim b, Font.DisplayMode mode, int bg, int light) {
    if (b instanceof RecordingBufferSource rec && rec.collector() != null) {
      PoseStack ps = new PoseStack();
      ps.last().pose().set(m);
      rec.collector().order(0).submitText(ps, x, y, seq, shadow, mode, light, (color & 0xFC000000) == 0 ? color | 0xFF000000 : color, bg, 0);
    }
    return (int) x + f.width(seq);
  }

  /** 1.21.x {@code Font.renderText(...)} (what cloth-config reaches through an accessor): the same batch-draw path. */
  public static int renderText(Font f, String s, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSourceShim b, Font.DisplayMode mode, int bg, int light) { return drawInBatch(f, s, x, y, color, shadow, m, b, mode, bg, light); }
}
