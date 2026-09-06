package foxgrade.shim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

/** 1.21.x Minecraft members that moved (screen/toasts live on Gui in 26.2) or vanished. */
public final class MinecraftCompat {
  private MinecraftCompat() {}
  public static Entity cameraEntity(Minecraft mc) { return mc.getCameraEntity(); }
  public static void setCameraEntity(Minecraft mc, Entity e) { mc.setCameraEntity(e); }
  public static Screen screen(Minecraft mc) { return mc.gui.screen(); }
  public static void setScreenField(Minecraft mc, Screen s) { mc.gui.setScreen(s); }
  public static void setScreen(Minecraft mc, Screen s) { mc.setScreenAndShow(s); }
  public static ToastManager getToasts(Minecraft mc) { return mc.gui.toastManager(); }
  public static boolean onOsx() { return Util.getPlatform() == Util.OS.OSX; }
  public static Quaternionf cameraOrientation(EntityRenderDispatcher dispatcher) { return dispatcher.camera.rotation(); }

  // ---- 1.20.1 shapes
  public static void bindForSetup(net.minecraft.client.renderer.texture.TextureManager tm, net.minecraft.resources.Identifier id) { /* textures bind themselves in 26.2 */ }
  public static int getGuiTicks(net.minecraft.client.gui.Gui gui) { var level = Minecraft.getInstance().level; return level == null ? 0 : (int) level.getGameTime(); }
  public static boolean renderDebug(net.minecraft.client.Options options) { return Minecraft.getInstance().getDebugOverlay().showDebugScreen(); }
  public static boolean renderDebugCharts(net.minecraft.client.Options options) { return Minecraft.getInstance().getDebugOverlay().showProfilerChart(); }
  public static boolean renderFpsChart(net.minecraft.client.Options options) { return Minecraft.getInstance().getDebugOverlay().showFpsCharts(); }
  public static void setRenderDebug(net.minecraft.client.Options options, boolean v) { /* the F3 overlay toggles itself in 26.2 */ }

  public static net.minecraft.client.multiplayer.ClientLevel clientLevel(net.minecraft.client.player.LocalPlayer p) { return (net.minecraft.client.multiplayer.ClientLevel) p.level(); }
}
