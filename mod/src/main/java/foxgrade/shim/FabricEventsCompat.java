package foxgrade.shim;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.Event;

/** Fabric API event constants renamed WORLD → LEVEL in 26.2. */
public final class FabricEventsCompat {
  private FabricEventsCompat() { }
  public static Event<ServerEntityLevelChangeEvents.AfterEntityChange> afterEntityChangeWorld() { return ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL; }
  public static Event<ServerEntityLevelChangeEvents.AfterPlayerChange> afterPlayerChangeWorld() { return ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL; }
}
