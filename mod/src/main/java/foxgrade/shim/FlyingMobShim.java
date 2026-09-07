package foxgrade.shim;

/** Stands in for 1.21's {@code FlyingMob}, removed in 26.2 (its subclasses extend Mob directly now). Nothing in 26.2 is
 *  one, so {@code instanceof FlyingMob} is simply false; a mod's own subclass still has a base class. */
public abstract class FlyingMobShim extends net.minecraft.world.entity.Mob {
  protected FlyingMobShim(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> type, net.minecraft.world.level.Level level) { super(type, level); }
}
