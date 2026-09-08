package foxgrade.shim;

/** 1.21's {@code com.mojang.blaze3d.pipeline.RenderCall}: a piece of work handed to the render thread.
 *  26.2 removed both the interface and {@code RenderSystem.recordRenderCall}; see
 *  {@link RenderSystemCompat#recordRenderCall} for how the work is run instead. */
public interface RenderCallShim {
  void execute();
}
