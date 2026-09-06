package foxgrade.shim;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * The buffer source handed to ported world renderers. 26.2 has no immediate buffers: geometry is
 * submitted per RenderType to the frame's collector, which draws it in the right pass. So every
 * getBuffer() records, and flush() hands each RenderType's vertices to
 * submitCustomGeometry, replayed as recorded — the mod already applied its pose to each vertex.
 */
public final class RecordingBufferSource extends BufferSourceShim {
  private final SubmitNodeCollector collector;
  private final PoseStack poseStack;
  private final Map<RenderType, RecordingConsumer> buffers = new LinkedHashMap<>();
  private static boolean warned;

  public RecordingBufferSource(SubmitNodeCollector collector, PoseStack poseStack) { this.collector = collector; this.poseStack = poseStack; }
  // The frame being submitted right now, for code that reaches for a global buffer source.
  private static RecordingBufferSource current;
  public static RecordingBufferSource current() { return current; }
  public RecordingBufferSource makeCurrent() { current = this; return this; }
  public SubmitNodeCollector collector() { return collector; }
  public PoseStack poseStack() { return poseStack; }

  @Override public VertexConsumer getBuffer(RenderType type) { return buffers.computeIfAbsent(type, k -> new RecordingConsumer()); }

  public void flush() {
    if (current == this) current = null;
    if (collector == null) {
      if (!buffers.isEmpty() && !warned) { warned = true; System.err.println("[Fox-Grade] world geometry recorded outside a frame was dropped"); }
      buffers.clear(); return;
    }
    for (Map.Entry<RenderType, RecordingConsumer> e : buffers.entrySet()) {
      RecordingConsumer rec = e.getValue();
      if (rec.count == 0) continue;
      collector.order(0).submitCustomGeometry(new PoseStack(), e.getKey(), (pose, vc) -> rec.replay(vc));
    }
    buffers.clear();
  }
  @Override public void endBatch() { flush(); }
  @Override public void endBatch(RenderType type) { flush(); }
  @Override public void endLastBatch() { flush(); }
}
