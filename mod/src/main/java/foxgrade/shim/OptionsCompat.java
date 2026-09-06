package foxgrade.shim;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/** Options entries 26.2 removed. Touchscreen mode is gone from the game; the answer is a fixed "off". */
public final class OptionsCompat {
  private OptionsCompat() { }
  private static OptionInstance<Boolean> touchscreen;
  public static OptionInstance<Boolean> touchscreen(Options o) {
    if (touchscreen == null) touchscreen = OptionInstance.createBoolean("options.touchscreen", false);
    return touchscreen;
  }
  public static boolean hideGui(Options o) { return false; }
  public static void setHideGui(Options o, boolean v) { }
}
