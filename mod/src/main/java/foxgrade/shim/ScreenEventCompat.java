package foxgrade.shim;

import java.lang.reflect.Method;

/** NeoForge's container-screen render events, which 26.2 folded into the plain screen ones.
 *
 *  <p>21.1 had a separate {@code ContainerScreenEvent} whose {@code getContainerScreen()} handed back an
 *  {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen} directly. 26.2 deleted that class and
 *  merged its Render.Background and Render.Foreground into {@code ScreenEvent}, which offers only
 *  {@code getScreen()} returning the wider {@link net.minecraft.client.gui.screens.Screen}. The event is still only
 *  ever fired for a container screen, so the narrowing is sound — it just has to be written down somewhere.
 *
 *  <p>Reflective because Fox-Grade is not compiled against NeoForge, which is also why the parameter is Object.
 *  The lookup is cached in a field: these events fire every frame, and finding the method each time would be a
 *  visible cost rather than a correctness one. */
public final class ScreenEventCompat {
  private ScreenEventCompat() { }

  private static volatile Method getScreen;

  public static net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> getContainerScreen(Object event) {
    if (event == null) return null;
    try {
      Method m = getScreen;
      if (m == null || !m.getDeclaringClass().isInstance(event)) {
        m = event.getClass().getMethod("getScreen");
        m.setAccessible(true);
        getScreen = m;
      }
      Object screen = m.invoke(event);
      // Anything else means the event was fired for a screen that is not a container screen, which 26.2 can do and
      // 21.1 could not. Null is what the old call would effectively have meant; it beats a ClassCastException.
      return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs ? acs : null;
    } catch (Throwable noSuchShape) {
      return null;
    }
  }
}
