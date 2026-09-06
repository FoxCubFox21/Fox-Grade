package foxgrade.shim;

import java.lang.reflect.Field;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

/**
 * 1.21.x exposed {@code Entity.noCulling} as a public field that mods read to skip their own
 * frustum culling. 26.2 keeps the flag only where it was ever set — on {@code Display}, and
 * private — so reads answer from there and are false for every other entity, which is the
 * value the old field held for them.
 */
public final class EntityCompat {
  private static final Field NO_CULLING;
  static {
    Field f = null;
    try { f = Display.class.getDeclaredField("noCulling"); f.setAccessible(true); } catch (Throwable ignored) { }
    NO_CULLING = f;
  }
  private EntityCompat() { }

  public static boolean noCulling(Entity e) {
    if (NO_CULLING == null || !(e instanceof Display)) return false;
    try { return NO_CULLING.getBoolean(e); } catch (Throwable t) { return false; }
  }

  public static void setNoCulling(Entity e, boolean value) {
    if (NO_CULLING == null || !(e instanceof Display)) return;
    try { NO_CULLING.setBoolean(e, value); } catch (Throwable ignored) { }
  }
}
