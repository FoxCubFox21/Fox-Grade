// Shim for FoodData accessors removed in 26.2. The exhaustionLevel FIELD survives — only the
// getter went away — so a reflective read restores the old contract. Injected into ported jars
// by the call-redirect stage; never loaded inside Fox-Grade itself.
package foxgrade.shim;

import net.minecraft.world.food.FoodData;

import java.lang.reflect.Field;

public final class FoodDataCompat {
  private static final Field EXHAUSTION;
  static {
    Field f = null;
    try { f = FoodData.class.getDeclaredField("exhaustionLevel"); f.setAccessible(true); }
    catch (Exception ignored) { }
    EXHAUSTION = f;
  }

  public static float getExhaustionLevel(FoodData d) {
    try { return EXHAUSTION != null ? EXHAUSTION.getFloat(d) : 0f; } catch (Exception e) { return 0f; }
  }

  public static void setExhaustion(FoodData d, float v) {
    try { if (EXHAUSTION != null) EXHAUSTION.setFloat(d, v); } catch (Exception ignored) { }
  }

  // lastFoodLevel tracking was dropped; the current level is the closest honest stand-in.
  public static int getLastFoodLevel(FoodData d) { return d.getFoodLevel(); }
}
