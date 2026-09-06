package foxgrade.shim;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

public final class FontCompat {
  private FontCompat() { }
  public static int wordWrapHeight(Font f, String s, int width) { return f.wordWrapHeight(Component.literal(s), width); }
}
