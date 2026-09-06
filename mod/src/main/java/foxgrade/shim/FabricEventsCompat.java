package foxgrade.shim;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.Event;

/** Fabric API event constants renamed WORLD → LEVEL in 26.2. */
public final class FabricEventsCompat {
  private FabricEventsCompat() { }
  public static Event<ServerEntityLevelChangeEvents.AfterEntityChange> afterEntityChangeWorld() { return ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL; }
  public static Event<ServerEntityLevelChangeEvents.AfterPlayerChange> afterPlayerChangeWorld() { return ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL; }
  // Tick events: WORLD → LEVEL.
  public static Event<net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.StartLevelTick> clientStartWorldTick() { return net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_LEVEL_TICK; }
  public static Event<net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndLevelTick> clientEndWorldTick() { return net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_LEVEL_TICK; }
  public static Event<net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.StartLevelTick> serverStartWorldTick() { return net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_LEVEL_TICK; }
  public static Event<net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndLevelTick> serverEndWorldTick() { return net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK; }
  /** An event 26.2's Fabric API no longer has: listeners register fine and never fire. Used by the interfaces Fox-Grade
   *  synthesises for removed callback types (ClientPickBlockApplyCallback and friends). */
  public static <T> Event<T> dead() { return new DeadEvent<>(); }
  /** Named (not anonymous) so the shim injector can carry it into the port next to its outer class. */
  public static final class DeadEvent<T> extends Event<T> {
    @Override public void register(T listener) { }
    @Override public void register(net.minecraft.resources.Identifier phase, T listener) { }
    @Override public void addPhaseOrdering(net.minecraft.resources.Identifier first, net.minecraft.resources.Identifier second) { }
  }
}
