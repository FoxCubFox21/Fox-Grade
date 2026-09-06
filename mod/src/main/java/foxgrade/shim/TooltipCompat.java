package foxgrade.shim;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;

public final class TooltipCompat {
  private TooltipCompat() { }
  /** (x, y, w, h, z) → the default tooltip sprite; z is ordering the extractor now owns. */
  public static void renderTooltipBackground(GuiGraphicsExtractor g, int x, int y, int w, int h, int z) { GuiCompat.current(g); TooltipRenderUtil.extractTooltipBackground(g, x, y, w, h, null); }
}
