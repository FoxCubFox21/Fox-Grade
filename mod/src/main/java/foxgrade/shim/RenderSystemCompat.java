package foxgrade.shim;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The 1.21.x {@code RenderSystem} global-state calls. 26.2 pipelines carry blend, depth and cull
 * state themselves, so the old toggles have nothing to act on and are accepted as no-ops. The
 * few that carried information the new path still needs are kept: the shader colour becomes the
 * GUI tint, the shader texture is remembered for immediate-mode replay, scissor is forwarded to
 * the current frame. Raw GL calls (glUniform, buffers, stencil) are deliberately NOT here — a
 * mod issuing those is doing its own GL and must stay visibly unresolved.
 */
public final class RenderSystemCompat {
  private RenderSystemCompat() { }
  private static Identifier shaderTexture;
  private static final Matrix4f IDENTITY = new Matrix4f();

  public static Identifier shaderTexture() { return shaderTexture; }

  // blend / depth / cull / colour state
  public static void enableBlend() { }
  public static void disableBlend() { }
  public static void defaultBlendFunc() { }
  public static void blendFunc(int src, int dst) { }
  public static void blendFuncSeparate(int a, int b, int c, int d) { }
  public static void blendFunc(SourceFactorShim s, DestFactorShim d) { }
  public static void blendFuncSeparate(SourceFactorShim a, DestFactorShim b, SourceFactorShim c, DestFactorShim d) { }
  public static void blendEquation(int mode) { }
  public static void enableDepthTest() { }
  public static void disableDepthTest() { }
  public static void depthFunc(int f) { }
  public static void depthMask(boolean m) { }
  public static void enableCull() { }
  public static void disableCull() { }
  public static void colorMask(boolean r, boolean g, boolean b, boolean a) { }
  public static void enablePolygonOffset() { }
  public static void disablePolygonOffset() { }
  public static void polygonOffset(float a, float b) { }
  public static void lineWidth(float w) { }
  public static float getShaderLineWidth() { return 1f; }

  // shader colour → GUI tint
  public static void setShaderColor(float r, float g, float b, float a) { GuiCompat.setTint(r, g, b, a); }
  public static float[] getShaderColor() { return GuiCompat.tint(); }

  // shaders and textures
  public static void setShader(Supplier<?> s) { }
  public static void setShaderTexture(int slot, Identifier id) { if (slot == 0) shaderTexture = id; }
  public static void setShaderTexture(int slot, int glId) { }
  public static int getShaderTexture(int slot) { return 0; }
  public static void bindTexture(int glId) { }
  public static void bindTextureForSetup(int glId) { }
  public static void activeTexture(int unit) { }
  public static void deleteTexture(int glId) { }
  public static void texParameter(int a, int b, int c) { }
  public static void pixelStore(int a, int b) { }

  // scissor: window pixels (bottom-left origin) → GUI coordinates on the current frame
  public static void enableScissor(int x, int y, int w, int h) {
    GuiGraphicsExtractor g = GuiCompat.current();
    if (g == null) return;
    Window win = Minecraft.getInstance().getWindow();
    double s = (double) win.getGuiScaledWidth() / Math.max(1, win.getScreenWidth());
    int gx = (int) (x * s), gy = (int) ((win.getScreenHeight() - y - h) * s);
    g.enableScissor(gx, gy, gx + (int) (w * s), gy + (int) (h * s));
  }
  public static void disableScissor() { GuiGraphicsExtractor g = GuiCompat.current(); if (g != null) g.disableScissor(); }

  // fog / lighting / time / glint: consumed by shaders that no longer exist
  public static void setShaderFogStart(float f) { }
  public static void setShaderFogEnd(float f) { }
  public static void setShaderFogColor(float r, float g, float b, float a) { }
  public static void setShaderFogColor(float r, float g, float b) { }
  public static float getShaderFogStart() { return Float.MAX_VALUE; }
  public static float getShaderFogEnd() { return Float.MAX_VALUE; }
  public static float[] getShaderFogColor() { return new float[]{0, 0, 0, 0}; }
  public static void setShaderGameTime(long ticks, float partial) { }
  public static float getShaderGameTime() { return 0f; }
  public static void setShaderGlintAlpha(float a) { }
  public static void setShaderGlintAlpha(double a) { }
  public static float getShaderGlintAlpha() { return 1f; }
  public static void setShaderLights(Vector3f a, Vector3f b) { }
  public static void setupGui3DDiffuseLighting(Vector3f a, Vector3f b) { }
  public static void setupGuiFlatDiffuseLighting(Vector3f a, Vector3f b) { }
  public static void setupLevelDiffuseLighting(Vector3f a, Vector3f b) { }
  public static void setupOverlayColor(int texId, int size) { }
  public static void teardownOverlayColor() { }
  public static void setupDefaultState(int x, int y, int w, int h) { }

  // matrices: the GUI transform lives on the extractor now; these answer with identity
  public static void applyModelViewMatrix() { }
  public static Matrix4f getModelViewMatrix() { return new Matrix4f(IDENTITY); }
  public static Matrix4f getProjectionMatrix() { return new Matrix4f(IDENTITY); }
  public static Matrix4f getTextureMatrix() { return new Matrix4f(IDENTITY); }
  public static void setTextureMatrix(Matrix4f m) { }
  public static void resetTextureMatrix() { }
  public static void _backupProjectionMatrix() { }
  public static void _restoreProjectionMatrix() { }

  // threading and misc
  public static void assertOnRenderThreadOrInit() { }
  public static boolean isOnRenderThreadOrInit() { return RenderSystem.isOnRenderThread(); }
  public static void runAsFancy(Runnable r) { r.run(); }
  public static void limitDisplayFPS(int fps) { }
  public static void renderCrosshair(int size) { }
  public static void viewport(int x, int y, int w, int h) { }
  public static void clear(int mask, boolean check) { }
  public static void clearColor(float r, float g, float b, float a) { }
  public static void clearDepth(double d) { }
  public static int maxSupportedTextureSize() { return 4096; }
  public static String getApiDescription() { return "Fox-Grade render compat"; }
  public static String getCapsString() { return ""; }
  public static void getString(int name, Consumer<String> out) { out.accept(""); }
  public static TesselatorShim renderThreadTesselator() { return TesselatorShim.getInstance(); }
}
