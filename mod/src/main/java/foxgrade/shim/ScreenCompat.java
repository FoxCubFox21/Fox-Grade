package foxgrade.shim;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Screen-level deferred tooltips, which 26.2 moved onto the frame's extractor. */
public final class ScreenCompat {
  private ScreenCompat() { }
  public static void setTooltipForNextRenderPass(Screen s, Component c) { GuiCompat.deferTooltip(c); }
  public static void setTooltipForNextRenderPass(Screen s, List<FormattedCharSequence> lines) { GuiCompat.deferTooltip(lines); }
  public static void clearTooltipForNextRenderPass(Screen s) { }

  /** 1.21.x {@code Screen.handleComponentClicked(Style)}: run the style's click event through 26.2's default handler. */
  public static boolean handleComponentClicked(net.minecraft.client.gui.screens.Screen screen, net.minecraft.network.chat.Style style) {
    if (style == null || style.getClickEvent() == null) return false;
    try {   // protected static in 26.2
      java.lang.reflect.Method m = net.minecraft.client.gui.screens.Screen.class.getDeclaredMethod("defaultHandleGameClickEvent", net.minecraft.network.chat.ClickEvent.class, net.minecraft.client.Minecraft.class, net.minecraft.client.gui.screens.Screen.class);
      m.setAccessible(true); m.invoke(null, style.getClickEvent(), net.minecraft.client.Minecraft.getInstance(), screen);
      return true;
    } catch (ReflectiveOperationException e) { return false; }
  }
}
