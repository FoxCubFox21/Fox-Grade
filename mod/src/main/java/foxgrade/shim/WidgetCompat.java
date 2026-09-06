package foxgrade.shim;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.client.gui.components.EditBox;

public final class WidgetCompat {
  private WidgetCompat() { }
  /** Input filtering and display formatting left EditBox in 26.2; accepted, not applied. */
  public static void setFilter(EditBox b, Predicate<String> f) { }
  public static void setFormatter(EditBox b, BiFunction<String, Integer, ?> f) { }
  /** A trailing boolean a constructor grew (OptionsScreen: "show as sub-screen"), defaulting off. */
  public static boolean falseValue() { return false; }
}
