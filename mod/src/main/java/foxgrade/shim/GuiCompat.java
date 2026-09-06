package foxgrade.shim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.WeakHashMap;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The 1.21.x {@code GuiGraphics} drawing API on top of 26.2's {@code GuiGraphicsExtractor}.
 * Every method takes the extractor first (the old receiver) and maps one old call onto one new
 * call: renames (drawString → text, renderItem → item), dropped z arguments, and blits that now
 * name their pipeline. Each call also records the extractor as the current frame, which is how
 * the immediate-mode replay (BufferUploader) finds where to submit.
 */
public final class GuiCompat {
  private GuiCompat() { }

  private static GuiGraphicsExtractor current;
  private static float tintR = 1, tintG = 1, tintB = 1, tintA = 1;
  private static final WeakHashMap<GuiGraphicsExtractor, GuiPoseStack> POSES = new WeakHashMap<>();
  private static Field renderStateField, scissorField;
  private static Method peek;

  public static GuiGraphicsExtractor current() { return current; }
  public static void current(GuiGraphicsExtractor g) { current = g; }
  private static GuiGraphicsExtractor use(GuiGraphicsExtractor g) { current = g; return g; }

  // ---- colour tint (GuiGraphics.setColor / RenderSystem.setShaderColor) ----
  public static void setColor(GuiGraphicsExtractor g, float r, float gr, float b, float a) { use(g); setTint(r, gr, b, a); }
  public static void setTint(float r, float g, float b, float a) { tintR = r; tintG = g; tintB = b; tintA = a; }
  public static float[] tint() { return new float[]{tintR, tintG, tintB, tintA}; }
  static boolean tinted() { return tintR != 1f || tintG != 1f || tintB != 1f || tintA != 1f; }
  public static int tintArgb() { return argb(tintR, tintG, tintB, tintA); }
  public static int argb(float r, float g, float b, float a) {
    return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
  }
  private static int clamp(float v) { return Math.max(0, Math.min(255, (int) (v * 255f + 0.5f))); }
  public static int applyTint(int argb) {
    if (!tinted()) return argb;
    int a = (int) (((argb >>> 24) & 255) * tintA), r = (int) (((argb >>> 16) & 255) * tintR);
    int g = (int) (((argb >>> 8) & 255) * tintG), b = (int) ((argb & 255) * tintB);
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  // ---- text ----
  // 1.21.x Font forced an alpha-less colour (0xFFFFFF, the norm in old mods) opaque; 26.2 draws
  // alpha 0 as nothing. Same rule, same threshold, applied where the old Font applied it.
  private static int opaque(int color) { return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color; }
  public static int drawString(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) { use(g).text(f, s, x, y, opaque(color)); return x + f.width(s); }
  public static int drawString(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color, boolean shadow) { use(g).text(f, s, x, y, opaque(color), shadow); return x + f.width(s); }
  public static int drawString(GuiGraphicsExtractor g, Font f, Component c, int x, int y, int color) { use(g).text(f, c, x, y, opaque(color)); return x + f.width(c); }
  public static int drawString(GuiGraphicsExtractor g, Font f, Component c, int x, int y, int color, boolean shadow) { use(g).text(f, c, x, y, opaque(color), shadow); return x + f.width(c); }
  public static int drawString(GuiGraphicsExtractor g, Font f, FormattedCharSequence c, int x, int y, int color) { use(g).text(f, c, x, y, opaque(color)); return x + f.width(c); }
  public static int drawString(GuiGraphicsExtractor g, Font f, FormattedCharSequence c, int x, int y, int color, boolean shadow) { use(g).text(f, c, x, y, opaque(color), shadow); return x + f.width(c); }
  public static void drawCenteredString(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) { use(g).centeredText(f, s, x, y, opaque(color)); }
  public static void drawCenteredString(GuiGraphicsExtractor g, Font f, Component c, int x, int y, int color) { use(g).centeredText(f, c, x, y, opaque(color)); }
  public static void drawCenteredString(GuiGraphicsExtractor g, Font f, FormattedCharSequence c, int x, int y, int color) { use(g).centeredText(f, c, x, y, opaque(color)); }
  public static int drawStringWithBackdrop(GuiGraphicsExtractor g, Font f, Component c, int x, int y, int w, int color) { use(g).textWithBackdrop(f, c, x, y, w, opaque(color)); return x + f.width(c); }
  public static void drawWordWrap(GuiGraphicsExtractor g, Font f, FormattedText t, int x, int y, int w, int color) { use(g).textWithWordWrap(f, t, x, y, w, opaque(color)); }

  // ---- shapes (z dropped: the extractor orders elements itself) ----
  public static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int z, int color) { use(g).fill(x1, y1, x2, y2, color); }
  public static void fillGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int z, int c1, int c2) { use(g).fillGradient(x1, y1, x2, y2, c1, c2); }
  public static void hLine(GuiGraphicsExtractor g, int x1, int x2, int y, int color) { use(g).horizontalLine(x1, x2, y, color); }
  public static void vLine(GuiGraphicsExtractor g, int x, int y1, int y2, int color) { use(g).verticalLine(x, y1, y2, color); }
  public static void renderOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) { use(g).outline(x, y, w, h, color); }

  // ---- textures ----
  private static RenderPipeline tex() { return RenderPipelines.GUI_TEXTURED; }
  /** blit(tex, x, y, u, v, w, h) — implicit 256×256 atlas. */
  public static void blit(GuiGraphicsExtractor g, Identifier t, int x, int y, int u, int v, int w, int h) { blit(g, t, x, y, (float) u, (float) v, w, h, 256, 256); }
  /** blit(tex, x, y, u, v, w, h, texW, texH). */
  public static void blit(GuiGraphicsExtractor g, Identifier t, int x, int y, float u, float v, int w, int h, int tw, int th) {
    if (tinted()) use(g).blit(tex(), t, x, y, u, v, w, h, tw, th, tintArgb()); else use(g).blit(tex(), t, x, y, u, v, w, h, tw, th);
  }
  /** blit(tex, x, y, z, u, v, w, h, texW, texH). */
  public static void blit(GuiGraphicsExtractor g, Identifier t, int x, int y, int z, float u, float v, int w, int h, int tw, int th) { blit(g, t, x, y, u, v, w, h, tw, th); }
  /** blit(tex, x, y, w, h, u, v, uW, vH, texW, texH). */
  public static void blit(GuiGraphicsExtractor g, Identifier t, int x, int y, int w, int h, float u, float v, int uw, int vh, int tw, int th) {
    if (tinted()) use(g).blit(tex(), t, x, y, u, v, w, h, uw, vh, tw, th, tintArgb()); else use(g).blit(tex(), t, x, y, u, v, w, h, uw, vh, tw, th);
  }
  /** blit(tex, x1, x2, y1, y2, z, uW, vH, u, v, texW, texH). */
  public static void blit(GuiGraphicsExtractor g, Identifier t, int x1, int x2, int y1, int y2, int z, int uw, int vh, float u, float v, int tw, int th) {
    blit(g, t, x1, y1, x2 - x1, y2 - y1, u, v, uw, vh, tw, th);
  }
  /** blit(x, y, z, w, h, sprite). */
  public static void blit(GuiGraphicsExtractor g, int x, int y, int z, int w, int h, TextureAtlasSprite s) {
    if (tinted()) use(g).blitSprite(tex(), s, x, y, w, h, tintArgb()); else use(g).blitSprite(tex(), s, x, y, w, h);
  }
  /** blit(x, y, z, w, h, sprite, r, g, b, a). */
  public static void blit(GuiGraphicsExtractor g, int x, int y, int z, int w, int h, TextureAtlasSprite s, float r, float gr, float b, float a) { use(g).blitSprite(tex(), s, x, y, w, h, argb(r, gr, b, a)); }
  public static void blitSprite(GuiGraphicsExtractor g, Identifier s, int x, int y, int w, int h) {
    if (tinted()) use(g).blitSprite(tex(), s, x, y, w, h, tintArgb()); else use(g).blitSprite(tex(), s, x, y, w, h);
  }
  /** blitSprite(sprite, x, y, z, w, h). */
  public static void blitSprite(GuiGraphicsExtractor g, Identifier s, int x, int y, int z, int w, int h) { blitSprite(g, s, x, y, w, h); }
  /** blitSprite(sprite, texW, texH, u, v, x, y, w, h). */
  public static void blitSprite(GuiGraphicsExtractor g, Identifier s, int tw, int th, int u, int v, int x, int y, int w, int h) { use(g).blitSprite(tex(), s, tw, th, u, v, x, y, w, h); }
  /** blitSprite(sprite, texW, texH, u, v, x, y, z, w, h). */
  public static void blitSprite(GuiGraphicsExtractor g, Identifier s, int tw, int th, int u, int v, int x, int y, int z, int w, int h) { use(g).blitSprite(tex(), s, tw, th, u, v, x, y, w, h); }
  /** blitSprite(sprite, x, y, z, w, h). */
  public static void blitSprite(GuiGraphicsExtractor g, TextureAtlasSprite s, int x, int y, int z, int w, int h) { use(g).blitSprite(tex(), s, x, y, w, h); }

  // ---- items ----
  public static void renderItem(GuiGraphicsExtractor g, ItemStack s, int x, int y) { use(g).item(s, x, y); }
  public static void renderItem(GuiGraphicsExtractor g, ItemStack s, int x, int y, int seed) { use(g).item(s, x, y, seed); }
  public static void renderItem(GuiGraphicsExtractor g, ItemStack s, int x, int y, int seed, int z) { use(g).item(s, x, y, seed); }
  public static void renderItem(GuiGraphicsExtractor g, LivingEntity e, ItemStack s, int x, int y, int seed) { use(g).item(e, s, x, y, seed); }
  public static void renderItem(GuiGraphicsExtractor g, LivingEntity e, Level l, ItemStack s, int x, int y, int seed) { use(g).item(e, s, x, y, seed); }
  public static void renderItem(GuiGraphicsExtractor g, LivingEntity e, Level l, ItemStack s, int x, int y, int seed, int z) { use(g).item(e, s, x, y, seed); }
  public static void renderFakeItem(GuiGraphicsExtractor g, ItemStack s, int x, int y) { use(g).fakeItem(s, x, y); }
  public static void renderFakeItem(GuiGraphicsExtractor g, ItemStack s, int x, int y, int seed) { use(g).fakeItem(s, x, y, seed); }
  public static void renderItemDecorations(GuiGraphicsExtractor g, Font f, ItemStack s, int x, int y) { use(g).itemDecorations(f, s, x, y); }
  public static void renderItemDecorations(GuiGraphicsExtractor g, Font f, ItemStack s, int x, int y, String t) { use(g).itemDecorations(f, s, x, y, t); }

  // ---- tooltips (deferred to end of frame in 26.2, which is what the old calls did too) ----
  public static void renderTooltip(GuiGraphicsExtractor g, Font f, Component c, int x, int y) { use(g).setTooltipForNextFrame(f, c, x, y); }
  public static void renderTooltip(GuiGraphicsExtractor g, Font f, ItemStack s, int x, int y) { use(g).setTooltipForNextFrame(f, s, x, y); }
  public static void renderTooltip(GuiGraphicsExtractor g, Font f, List<FormattedCharSequence> l, int x, int y) { use(g).setTooltipForNextFrame(f, l, x, y); }
  public static void renderTooltip(GuiGraphicsExtractor g, Font f, List<Component> l, Optional<TooltipComponent> o, int x, int y) { use(g).setTooltipForNextFrame(f, l, o, x, y); }
  public static void renderTooltip(GuiGraphicsExtractor g, Font f, List<FormattedCharSequence> l, ClientTooltipPositioner p, int x, int y) { use(g).setTooltipForNextFrame(f, l, p, x, y, false); }
  public static void renderComponentTooltip(GuiGraphicsExtractor g, Font f, List<Component> l, int x, int y) { use(g).setComponentTooltipForNextFrame(f, l, x, y); }

  // ---- pose & frame plumbing ----
  /** The old 3D PoseStack, forwarding to the extractor's 2D stack (see GuiPoseStack). */
  public static PoseStack pose(GuiGraphicsExtractor g) { use(g); return POSES.computeIfAbsent(g, k -> new GuiPoseStack(k.pose())); }
  public static void flush(GuiGraphicsExtractor g) { use(g); }
  public static void flushIfManaged(GuiGraphicsExtractor g) { use(g); }
  public static void flushIfUnmanaged(GuiGraphicsExtractor g) { use(g); }
  public static void drawManaged(GuiGraphicsExtractor g, Runnable r) { use(g); r.run(); }
  public static void applyScissor(GuiGraphicsExtractor g, ScreenRectangle r) {
    if (r == null) use(g).disableScissor(); else use(g).enableScissor(r.left(), r.top(), r.right(), r.bottom());
  }

  // Screen.setTooltipForNextRenderPass: the frame's extractor takes tooltips now, at the mouse.
  private static Field mouseXField, mouseYField;
  private static int mouse(GuiGraphicsExtractor g, boolean x) {
    try {
      if (mouseXField == null) { mouseXField = GuiGraphicsExtractor.class.getDeclaredField("mouseX"); mouseXField.setAccessible(true); mouseYField = GuiGraphicsExtractor.class.getDeclaredField("mouseY"); mouseYField.setAccessible(true); }
      return (x ? mouseXField : mouseYField).getInt(g);
    } catch (Throwable t) { return 0; }
  }
  public static void deferTooltip(Component c) {
    GuiGraphicsExtractor g = current; if (g == null) return;
    g.setTooltipForNextFrame(net.minecraft.client.Minecraft.getInstance().font, c, mouse(g, true), mouse(g, false));
  }
  public static void deferTooltip(List<FormattedCharSequence> lines) {
    GuiGraphicsExtractor g = current; if (g == null) return;
    g.setTooltipForNextFrame(net.minecraft.client.Minecraft.getInstance().font, lines, mouse(g, true), mouse(g, false));
  }

  /** The extractor's element list, for replaying immediate-mode geometry. */
  public static GuiRenderState renderState(GuiGraphicsExtractor g) {
    try {
      if (renderStateField == null) { renderStateField = GuiGraphicsExtractor.class.getDeclaredField("guiRenderState"); renderStateField.setAccessible(true); }
      return (GuiRenderState) renderStateField.get(g);
    } catch (Throwable t) { return null; }
  }
  public static ScreenRectangle scissor(GuiGraphicsExtractor g) {
    try {
      if (scissorField == null) { scissorField = GuiGraphicsExtractor.class.getDeclaredField("scissorStack"); scissorField.setAccessible(true); }
      Object stack = scissorField.get(g);
      if (peek == null) { peek = stack.getClass().getDeclaredMethod("peek"); peek.setAccessible(true); }
      return (ScreenRectangle) peek.invoke(stack);
    } catch (Throwable t) { return null; }
  }

  /** 1.21.x {@code GuiGraphics.bufferSource()}: the frame's recording buffer source (replayed through the extractor). */
  public static BufferSourceShim bufferSource(net.minecraft.client.gui.GuiGraphicsExtractor g) { return FrameCompat.bufferSource(null); }
}
