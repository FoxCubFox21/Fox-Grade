package foxgrade.shim;

/** 1.21.x {@code MultiBufferSource.BufferSource}: the flushable kind. Batches end when the frame's collector draws. */
public abstract class BufferSourceShim implements MultiBufferSourceShim {
  public void endBatch() { }
  public void endBatch(net.minecraft.client.renderer.rendertype.RenderType type) { }
  public void endLastBatch() { }
}
