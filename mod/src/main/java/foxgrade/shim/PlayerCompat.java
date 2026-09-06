package foxgrade.shim;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerCompat {
  private PlayerCompat() { }
  /** 1.21.x exposed ServerPlayer.server as a public field. */
  public static MinecraftServer server(ServerPlayer p) { return p.level().getServer(); }
}
