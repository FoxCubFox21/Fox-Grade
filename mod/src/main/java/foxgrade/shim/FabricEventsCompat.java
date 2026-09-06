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
}
