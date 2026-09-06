package foxgrade.shim;

import com.mojang.blaze3d.platform.Window;

public final class WindowCompat {
  private WindowCompat() { }
  /** 1.21.x returned the GUI scale as a double; 26.2 as an int. */
  public static double getGuiScale(Window w) { return w.getGuiScale(); }
}
