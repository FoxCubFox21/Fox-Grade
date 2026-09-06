package foxgrade.shim;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Input plumbing for 1.21.x code: packs the old (key, scancode, modifiers) and (x, y, button)
 * argument lists into 26.2's event objects, and re-creates the static modifier helpers that
 * moved off {@code Screen}.
 */
public final class InputCompat {
  private InputCompat() { }

  public static KeyEvent keyEvent(int key, int scancode, int modifiers) { return new KeyEvent(key, scancode, modifiers); }
  public static MouseButtonEvent mouseEvent(double x, double y, int button) { return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)); }
  public static MouseButtonInfo mouseInfo(int button) { return new MouseButtonInfo(button, 0); }
  public static CharacterEvent charEvent(char c, int modifiers) { return new CharacterEvent(c); }

  public static boolean hasShiftDown() { return Minecraft.getInstance().hasShiftDown(); }
  public static boolean hasControlDown() { return Minecraft.getInstance().hasControlDown(); }
  public static boolean hasAltDown() { return Minecraft.getInstance().hasAltDown(); }
  private static boolean chord(int key, int want) { return key == want && hasControlDown() && !hasShiftDown() && !hasAltDown(); }
  public static boolean isCopy(int key) { return chord(key, 67); }
  public static boolean isPaste(int key) { return chord(key, 86); }
  public static boolean isCut(int key) { return chord(key, 88); }
  public static boolean isSelectAll(int key) { return chord(key, 65); }

  public static boolean isKeyDown(long window, int key) { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key); }
  public static InputConstants.Key getKey(int key, int scancode) {
    return key == -1 ? InputConstants.Type.SCANCODE.getOrCreate(scancode) : InputConstants.Type.KEYSYM.getOrCreate(key);
  }
  public static boolean matches(KeyMapping km, int key, int scancode) { return km.matches(new KeyEvent(key, scancode, 0)); }
  public static boolean matchesMouse(KeyMapping km, int button) { return km.matchesMouse(new MouseButtonEvent(0, 0, new MouseButtonInfo(button, 0))); }
}
