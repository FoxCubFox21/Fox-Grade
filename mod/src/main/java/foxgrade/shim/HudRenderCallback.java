package foxgrade.shim;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Fabric API's {@code HudRenderCallback}, which every 1.21.1-era HUD mod registers with and which
 * Fabric removed after 1.21.5 in favour of {@code HudElementRegistry}. Re-created as a real
 * bridge, not a stub: one HUD element is registered with the new registry the first time this
 * class is touched, and it fans out to every old-style listener. The old callback took
 * {@code (GuiGraphics, DeltaTracker)}; with GuiGraphics mapped to GuiGraphicsExtractor by the
 * translation rules, a mod's existing lambda already has the new element's exact shape.
 */
@FunctionalInterface
public interface HudRenderCallback {
  Event<HudRenderCallback> EVENT = create();

  void onHudRender(GuiGraphicsExtractor graphics, DeltaTracker tickCounter);

  private static Event<HudRenderCallback> create() {
    Event<HudRenderCallback> ev = EventFactory.createArrayBacked(HudRenderCallback.class,
        listeners -> (g, t) -> { GuiCompat.current(g); for (HudRenderCallback l : listeners) l.onHudRender(g, t); });
    HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("foxgrade", "hud_render_callback"),
        (g, t) -> ev.invoker().onHudRender(g, t));
    return ev;
  }
}
