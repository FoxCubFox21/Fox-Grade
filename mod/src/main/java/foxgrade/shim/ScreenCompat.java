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
}
