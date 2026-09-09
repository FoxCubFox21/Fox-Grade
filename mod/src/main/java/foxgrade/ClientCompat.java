package foxgrade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/** The handful of client calls Fox-Grade's own UI makes that Minecraft moved between versions.
 *
 *  <p>Fox-Grade ports mods across versions, so it has to be able to run on more than one itself. Everywhere else that
 *  is free — the engine works on bytecode and mapping tables, neither of which cares what the running game looks
 *  like. The in-game panel is the exception: it calls the client directly, and two of those calls changed shape.
 *
 *  <p>Reflection rather than a per-version source set, for the same reason {@link NeoForgeHost} uses it: one compiled
 *  jar has to run on either version, and these are two calls on a panel, not a hot path. Each probe tries the current
 *  shape first and the older one second, and returns a harmless answer if neither is there — a panel that cannot read
 *  the open screen should degrade, never take the game down. */
final class ClientCompat {
  private ClientCompat() { }

  private static Method guiScreen;        // 26.2: Gui.screen()
  private static java.lang.reflect.Field mcScreen;   // 26.1.x and earlier: Minecraft.screen
  private static Method grabDirect;       // 26.2: Screenshot.grab(Minecraft, boolean)
  private static boolean probed;

  private static void probe() {
    if (probed) return;
    probed = true;
    try { guiScreen = Class.forName("net.minecraft.client.gui.Gui").getMethod("screen"); } catch (Throwable older) { }
    try { mcScreen = Minecraft.class.getField("screen"); } catch (Throwable newer) { }
    try {
      grabDirect = Class.forName("net.minecraft.client.Screenshot").getMethod("grab", Minecraft.class, boolean.class);
    } catch (Throwable older) { }
  }

  /** The screen the client currently has open, or null — including when neither shape is present. */
  static Screen screen(Minecraft mc) {
    probe();
    if (mc == null) return null;
    if (guiScreen != null && mc.gui != null) {
      try { return (Screen) guiScreen.invoke(mc.gui); } catch (Throwable notThisShape) { }
    }
    if (mcScreen != null) {
      try { return (Screen) mcScreen.get(mc); } catch (Throwable notThisShape) { }
    }
    return null;
  }

  /** Take a screenshot the way the running version wants to. False when this version offers no one-call form. */
  static boolean grabScreenshot(Minecraft mc) {
    probe();
    if (grabDirect == null) return false;
    try { grabDirect.invoke(null, mc, false); return true; } catch (Throwable failed) { return false; }
  }
}
