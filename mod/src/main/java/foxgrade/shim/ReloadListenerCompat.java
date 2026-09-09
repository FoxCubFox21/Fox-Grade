package foxgrade.shim;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** NeoForge's reload-listener registration, which gained a name.
 *
 *  <p>The old call was {@code event.registerReloadListener(listener)}. 26.2 sorts reload listeners into a dependency
 *  graph, so the event moved to {@code addListener(Identifier, listener)} and every listener needs an id to be
 *  ordered by. There is no id in the old call to carry across, so one is made: unique per registration, in Fox-Grade's
 *  own namespace, which is what the parameter is for — a handle other listeners could declare a dependency on. A
 *  ported mod declares no such dependencies, so any unique id orders it exactly as the old API did, at the end.
 *
 *  <p>Reached by reflection because this class is compiled against Minecraft and Fabric, never NeoForge, and takes
 *  the event as {@link Object} for the same reason. That is also why it is safe: a redirect that named the NeoForge
 *  type would not compile here, and one that guessed at it would fail at verification instead of at a log line. */
public final class ReloadListenerCompat {
  private ReloadListenerCompat() { }

  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static volatile Method addListener;

  public static void registerReloadListener(Object event, PreparableReloadListener listener) {
    if (event == null || listener == null) return;
    try {
      Method m = addListener;
      if (m == null || !m.getDeclaringClass().isInstance(event)) {
        m = find(event.getClass());
        addListener = m;
      }
      if (m == null) return;                       // nothing to register with; the mod loads without this listener
      m.invoke(event, Identifier.fromNamespaceAndPath("foxgrade", "ported_listener_" + COUNTER.incrementAndGet()), listener);
    } catch (Throwable notThisShape) {
      // A listener that cannot be registered is a feature that does not run. It is not a reason to stop the game.
    }
  }

  /** {@code addListener(Identifier, PreparableReloadListener)} on the event or anything it inherits from. */
  private static Method find(Class<?> type) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Method m : c.getMethods()) {
        if (!m.getName().equals("addListener")) continue;
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 2 && p[0] == Identifier.class && p[1].isAssignableFrom(PreparableReloadListener.class)) return m;
      }
    }
    return null;
  }
}
