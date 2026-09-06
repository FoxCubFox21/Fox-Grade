package foxgrade.shim;

import net.minecraft.world.level.Level;

public final class LevelCompat {
  private LevelCompat() { }
  public static long getDayTime(Level l) { return l.getDefaultClockTime(); }
}
