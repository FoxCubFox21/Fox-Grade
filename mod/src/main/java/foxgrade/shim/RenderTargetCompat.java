package foxgrade.shim;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;

/** 1.21 RenderTarget members that 26.2's GPU abstraction dropped: the viewport size (now width/height) and the raw
 *  OpenGL ids. Texture ids come from the OpenGL backend's texture objects; there is no framebuffer id to hand out any
 *  more (framebuffers are cached per texture pair inside the backend), so callers get the default framebuffer. */
public final class RenderTargetCompat {
  private RenderTargetCompat() { }
  public static int viewWidth(RenderTarget t) { return t.width; }
  public static int viewHeight(RenderTarget t) { return t.height; }
  public static int frameBufferId(RenderTarget t) { return 0; }
  public static int getColorTextureId(RenderTarget t) { return glId(t.getColorTexture()); }
  public static int getDepthTextureId(RenderTarget t) { return glId(t.getDepthTexture()); }
  private static int glId(GpuTexture tex) { return tex instanceof GlTexture gl ? gl.glId() : -1; }
}
