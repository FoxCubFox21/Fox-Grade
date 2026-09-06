package foxgrade.shim;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerCompat {
  private PlayerCompat() { }
  /** 1.21.x exposed ServerPlayer.server as a public field. */
  public static MinecraftServer server(ServerPlayer p) { return p.level().getServer(); }

  // 1.21.x cape/bob fields: 26.2 keeps them in the player render state; a ported cape layer reads zeros.
  public static float bob(net.minecraft.world.entity.player.Player p) { return 0f; }
  public static float oBob(net.minecraft.world.entity.player.Player p) { return 0f; }
  public static void setBob(net.minecraft.world.entity.player.Player p, float v) {}
  public static void setOBob(net.minecraft.world.entity.player.Player p, float v) {}
  public static double cloak(net.minecraft.world.entity.player.Player p) { return 0d; }
  public static void setCloak(net.minecraft.world.entity.player.Player p, double v) {}
}
