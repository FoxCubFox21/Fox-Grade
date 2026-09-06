package foxgrade.shim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;

/**
 * Fields on {@code Minecraft} that 1.21.x mods read directly and 26.2 turned into methods.
 * Each pair mirrors one former public field; the field redirect table routes GETFIELD/PUTFIELD
 * here (see fabric-api-bridges.json).
 */
public final class MinecraftCompat {
  private MinecraftCompat() { }

  public static Entity cameraEntity(Minecraft mc) { return mc.getCameraEntity(); }
  public static void setCameraEntity(Minecraft mc, Entity e) { mc.setCameraEntity(e); }

  // Minecraft.screen moved behind Gui; the old setScreen(Screen) became setScreenAndShow.
  public static Screen screen(Minecraft mc) { return mc.gui.screen(); }
  public static void setScreenField(Minecraft mc, Screen s) { mc.gui.setScreen(s); }
  public static void setScreen(Minecraft mc, Screen s) { mc.setScreenAndShow(s); }

  // Toasts moved behind Gui as well.
  public static net.minecraft.client.gui.components.toasts.ToastManager getToasts(Minecraft mc) { return mc.gui.toastManager(); }
}
