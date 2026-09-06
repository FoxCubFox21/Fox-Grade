package foxgrade.shim;

import net.minecraft.util.Mth;

/** 1.21.x float overloads of {@link Mth} trig; 26.2 takes doubles. */
public final class MathCompat {
  private MathCompat() {}
  public static float cos(float f) { return Mth.cos((double) f); }
  public static float sin(float f) { return Mth.sin((double) f); }
}
