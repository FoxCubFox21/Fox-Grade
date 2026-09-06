package foxgrade.shim;

/** Stands in for 1.21's {@code net.minecraft.client.renderer.texture.Tickable}, dropped in 26.2. Mods that implemented it
 *  (freecam's texture helpers) keep compiling against the interface; nothing in 26.2 calls {@code tick()} on it. */
public interface TickableShim {
  void tick();
}
