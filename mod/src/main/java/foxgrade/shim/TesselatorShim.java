package foxgrade.shim;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * {@code Tesselator} (removed): hands out a real 26.2 BufferBuilder over a reusable byte buffer,
 * so a mod's vertex calls are the genuine article; only the draw step (BufferUploader) changes.
 */
public final class TesselatorShim {
  private static final TesselatorShim INSTANCE = new TesselatorShim(786432);
  static VertexFormatModeShim lastMode = VertexFormatModeShim.QUADS;
  private final ByteBufferBuilder buffer;

  public TesselatorShim(int capacity) { this.buffer = new ByteBufferBuilder(capacity); }
  public static TesselatorShim getInstance() { return INSTANCE; }
  public BufferBuilder begin(VertexFormatModeShim mode, VertexFormat format) { lastMode = mode; return new BufferBuilder(buffer, mode.topology, format); }
  public void clear() { }
}
