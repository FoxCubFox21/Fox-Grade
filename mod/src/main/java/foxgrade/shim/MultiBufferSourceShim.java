package foxgrade.shim;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** 1.21.x {@code MultiBufferSource} (removed): a buffer per RenderType. Injected at the old name. */
public interface MultiBufferSourceShim {
  VertexConsumer getBuffer(RenderType type);
  static BufferSourceShim immediate(ByteBufferBuilder buffer) { return new RecordingBufferSource(null, null); }
}
