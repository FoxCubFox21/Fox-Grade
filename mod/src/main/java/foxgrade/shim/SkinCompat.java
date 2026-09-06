package foxgrade.shim;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

public final class SkinCompat {
  private SkinCompat() { }
  /** 1.21.x texture() → the body texture's resource path. */
  public static Identifier texture(PlayerSkin s) { return s.body().texturePath(); }
}
