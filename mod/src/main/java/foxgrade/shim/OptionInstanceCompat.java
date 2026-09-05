// Bridges the pre-26.x OptionInstance.createBoolean(String, boolean, Consumer) overload to the
// 26.2 form, whose callback arg became the dedicated OptionInstance.ValueUpdateListener
// interface. Same semantics — the listener fires with the new value — so the adapter is a pure
// method-reference wrap, reviewed per ShimGenerator's scope rules.
package foxgrade.shim;

import net.minecraft.client.OptionInstance;

import java.util.function.Consumer;

public final class OptionInstanceCompat {
  private OptionInstanceCompat() { }

  public static OptionInstance<Boolean> createBoolean(String key, boolean initial, Consumer<Boolean> onChange) {
    return OptionInstance.createBoolean(key, initial, onChange::accept);
  }
}
