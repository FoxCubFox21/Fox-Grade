package foxgrade.shim;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

/** 1.21.x numeric permission levels over 26.2's permission sets. */
public final class PermissionCompat {
  private PermissionCompat() {}
  private static final String[] BY_LEVEL = {"COMMANDS_MODERATOR", "COMMANDS_GAMEMASTER", "COMMANDS_ADMIN", "COMMANDS_OWNER"};
  public static boolean hasPermission(CommandSourceStack source, int level) {
    if (level <= 0) return true;
    int idx = Math.min(level, 4) - 1;
    for (int i = idx; i >= 0; i--) {
      try {
        Object p = Permissions.class.getField(BY_LEVEL[i]).get(null);
        if (p instanceof Permission perm) return source.permissions().hasPermission(perm);
      } catch (ReflectiveOperationException ignore) { }
    }
    return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
  }
}
