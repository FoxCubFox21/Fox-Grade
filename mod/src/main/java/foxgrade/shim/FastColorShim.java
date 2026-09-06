package foxgrade.shim;

import net.minecraft.util.ARGB;

/** 1.21.x {@code net.minecraft.util.FastColor} (ARGB32 / ABGR32 helpers); 26.2 calls it {@code ARGB}. */
public final class FastColorShim {
  private FastColorShim() {}
  public static final class ARGB32 {
    private ARGB32() {}
    public static int alpha(int c) { return ARGB.alpha(c); }
    public static int red(int c) { return ARGB.red(c); }
    public static int green(int c) { return ARGB.green(c); }
    public static int blue(int c) { return ARGB.blue(c); }
    public static int color(int a, int r, int g, int b) { return ARGB.color(a, r, g, b); }
    public static int color(int r, int g, int b) { return ARGB.color(r, g, b); }
    public static int color(int a, int rgb) { return ARGB.color(a, rgb); }
    public static int multiply(int a, int b) { return ARGB.multiply(a, b); }
    public static int lerp(float t, int a, int b) { return ARGB.color(lerpChannel(t, ARGB.alpha(a), ARGB.alpha(b)), lerpChannel(t, ARGB.red(a), ARGB.red(b)), lerpChannel(t, ARGB.green(a), ARGB.green(b)), lerpChannel(t, ARGB.blue(a), ARGB.blue(b))); }
    public static int opaque(int c) { return ARGB.opaque(c); }
    public static int colorFromFloat(float a, float r, float g, float b) { return ARGB.colorFromFloat(a, r, g, b); }
    public static int average(int a, int b) { return ARGB.average(a, b); }
    private static int lerpChannel(float t, int a, int b) { return Math.round(a + (b - a) * t); }
  }
  public static final class ABGR32 {
    private ABGR32() {}
    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return c & 255; }
    public static int green(int c) { return (c >> 8) & 255; }
    public static int blue(int c) { return (c >> 16) & 255; }
    public static int transparent(int c) { return c & 16777215; }
    public static int opaque(int c) { return c | -16777216; }
    public static int color(int a, int b, int g, int r) { return a << 24 | b << 16 | g << 8 | r; }
    public static int color(int a, int bgr) { return a << 24 | bgr & 16777215; }
    public static int fromArgb32(int argb) { return ARGB.toABGR(argb); }
  }
  public static int as8BitChannel(float f) { return ARGB.as8BitChannel(f); }
}
