package foxgrade.shim;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 1.21.x NativeImage pixel access. The old getPixelRGBA/setPixelRGBA carried ABGR-packed ints
 * despite the name; 26.2's public accessors are ARGB, so each call swaps the red and blue bytes.
 * blendPixel is 1.21.x's own formula over those.
 */
public final class ImageCompat {
  private ImageCompat() { }
  private static int swap(int c) { return (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF); }
  public static int getPixelRGBA(NativeImage img, int x, int y) { return swap(img.getPixel(x, y)); }
  public static void setPixelRGBA(NativeImage img, int x, int y, int abgr) { img.setPixel(x, y, swap(abgr)); }
  public static void blendPixel(NativeImage img, int x, int y, int col) {
    int old = getPixelRGBA(img, x, y);
    float a = ((col >> 24) & 255) / 255f, b = ((col >> 16) & 255) / 255f, g = ((col >> 8) & 255) / 255f, r = (col & 255) / 255f;
    float oa = ((old >> 24) & 255) / 255f, ob = ((old >> 16) & 255) / 255f, og = ((old >> 8) & 255) / 255f, or = (old & 255) / 255f;
    float ia = 1f - a;
    float na = Math.min(1f, a * a + oa * ia), nb = Math.min(1f, b * a + ob * ia), ng = Math.min(1f, g * a + og * ia), nr = Math.min(1f, r * a + or * ia);
    setPixelRGBA(img, x, y, ((int) (na * 255) << 24) | ((int) (nb * 255) << 16) | ((int) (ng * 255) << 8) | (int) (nr * 255));
  }
}
