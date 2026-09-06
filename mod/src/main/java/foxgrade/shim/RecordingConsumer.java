package foxgrade.shim;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Records vertices exactly as a 1.21.x mod emits them, to be replayed into a real consumer later. */
public final class RecordingConsumer implements VertexConsumer {
  static final int STRIDE = 16;   // x y z r g b a u v ou ov lu lv nx ny nz
  float[] data = new float[STRIDE * 64];
  int count;
  private int cur = -1;
  float lineWidth = 1f;

  private int grow() {
    if ((count + 1) * STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
    cur = count++ * STRIDE;
    float[] d = data;
    d[cur + 3] = 1; d[cur + 4] = 1; d[cur + 5] = 1; d[cur + 6] = 1;      // white
    d[cur + 9] = 0; d[cur + 10] = 10;                                        // OverlayTexture.NO_OVERLAY (u=0, v=10)
    d[cur + 11] = 240; d[cur + 12] = 240;                                    // full bright light coords
    d[cur + 13] = 0; d[cur + 14] = 1; d[cur + 15] = 0;
    return cur;
  }
  @Override public VertexConsumer addVertex(float x, float y, float z) { int c = grow(); data[c] = x; data[c + 1] = y; data[c + 2] = z; return this; }
  @Override public VertexConsumer setColor(int r, int g, int b, int a) { if (cur >= 0) { data[cur + 3] = r / 255f; data[cur + 4] = g / 255f; data[cur + 5] = b / 255f; data[cur + 6] = a / 255f; } return this; }
  @Override public VertexConsumer setColor(int argb) { return setColor((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255); }
  @Override public VertexConsumer setUv(float u, float v) { if (cur >= 0) { data[cur + 7] = u; data[cur + 8] = v; } return this; }
  @Override public VertexConsumer setUv1(int u, int v) { if (cur >= 0) { data[cur + 9] = u; data[cur + 10] = v; } return this; }
  @Override public VertexConsumer setUv2(int u, int v) { if (cur >= 0) { data[cur + 11] = u; data[cur + 12] = v; } return this; }
  @Override public VertexConsumer setNormal(float x, float y, float z) { if (cur >= 0) { data[cur + 13] = x; data[cur + 14] = y; data[cur + 15] = z; } return this; }
  @Override public VertexConsumer setLineWidth(float w) { lineWidth = w; return this; }

  void replay(VertexConsumer out) {
    float[] d = data;
    for (int i = 0; i < count; i++) {
      int c = i * STRIDE;
      out.addVertex(d[c], d[c + 1], d[c + 2]);
      out.setColor((int) (d[c + 3] * 255), (int) (d[c + 4] * 255), (int) (d[c + 5] * 255), (int) (d[c + 6] * 255));
      out.setUv(d[c + 7], d[c + 8]);
      out.setUv1((int) d[c + 9], (int) d[c + 10]);
      out.setUv2((int) d[c + 11], (int) d[c + 12]);
      out.setNormal(d[c + 13], d[c + 14], d[c + 15]);
      if (lineWidth != 1f) out.setLineWidth(lineWidth);
    }
  }
}
