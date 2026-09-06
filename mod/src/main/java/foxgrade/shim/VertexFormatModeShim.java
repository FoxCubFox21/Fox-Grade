package foxgrade.shim;

import com.mojang.blaze3d.PrimitiveTopology;

/** {@code VertexFormat.Mode} (removed): the draw-mode enum, carrying its 26.2 topology. */
public final class VertexFormatModeShim {
  public static final VertexFormatModeShim LINES = new VertexFormatModeShim("LINES", 0, PrimitiveTopology.LINES);
  public static final VertexFormatModeShim LINE_STRIP = new VertexFormatModeShim("LINE_STRIP", 1, PrimitiveTopology.DEBUG_LINE_STRIP);
  public static final VertexFormatModeShim DEBUG_LINES = new VertexFormatModeShim("DEBUG_LINES", 2, PrimitiveTopology.DEBUG_LINES);
  public static final VertexFormatModeShim DEBUG_LINE_STRIP = new VertexFormatModeShim("DEBUG_LINE_STRIP", 3, PrimitiveTopology.DEBUG_LINE_STRIP);
  public static final VertexFormatModeShim TRIANGLES = new VertexFormatModeShim("TRIANGLES", 4, PrimitiveTopology.TRIANGLES);
  public static final VertexFormatModeShim TRIANGLE_STRIP = new VertexFormatModeShim("TRIANGLE_STRIP", 5, PrimitiveTopology.TRIANGLES);
  public static final VertexFormatModeShim TRIANGLE_FAN = new VertexFormatModeShim("TRIANGLE_FAN", 6, PrimitiveTopology.TRIANGLE_FAN);
  public static final VertexFormatModeShim QUADS = new VertexFormatModeShim("QUADS", 7, PrimitiveTopology.QUADS);

  public final PrimitiveTopology topology;
  private final String name;
  private final int ordinal;

  private VertexFormatModeShim(String name, int ordinal, PrimitiveTopology topology) { this.name = name; this.ordinal = ordinal; this.topology = topology; }
  public String name() { return name; }
  public int ordinal() { return ordinal; }
  @Override public String toString() { return name; }
  public static VertexFormatModeShim[] values() { return new VertexFormatModeShim[]{LINES, LINE_STRIP, DEBUG_LINES, DEBUG_LINE_STRIP, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS}; }
}
