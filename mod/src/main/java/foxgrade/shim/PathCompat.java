package foxgrade.shim;

import net.minecraft.world.level.pathfinder.PathType;

/** 1.21.x path-type constants 26.2 renamed. */
public final class PathCompat {
  private PathCompat() {}
  public static PathType damageFire() { return PathType.FIRE; }
  public static PathType dangerFire() { return PathType.FIRE_IN_NEIGHBOR; }
  public static PathType damageOther() { return PathType.DAMAGING; }
  public static PathType dangerOther() { return PathType.DAMAGING_IN_NEIGHBOR; }
  public static PathType dangerPowderSnow() { return PathType.DAMAGE_CAUTIOUS; }
}
