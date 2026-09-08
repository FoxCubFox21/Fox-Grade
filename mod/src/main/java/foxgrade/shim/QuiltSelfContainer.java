package foxgrade.shim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/** A ported mod is registered as {@code <id>_fgport} (so a later official build can never collide with it), but the mod
 *  knows itself by its original id: config folders, resource lookups, registry namespaces. The containers a Quilt mod
 *  fetches for itself hand back the original id on either host — Fox-Grade's own wrapper on Fabric, a delegating proxy
 *  over Quilt Loader's real container on Quilt. Everything else is passed through untouched. */
public final class QuiltSelfContainer {
  private QuiltSelfContainer() { }
  private static final String SUFFIX = "_fgport";

  public static Optional<QuiltModContainerShim> byClass(Class<?> cls) { return QuiltLoaderShim.getModContainer(cls).map(QuiltSelfContainer::wrap); }
  public static Optional<QuiltModContainerShim> byId(String id) {
    Optional<QuiltModContainerShim> c = QuiltLoaderShim.getModContainer(id);
    if (c.isEmpty() && !id.endsWith(SUFFIX)) c = QuiltLoaderShim.getModContainer(id + SUFFIX);
    return c.map(QuiltSelfContainer::wrap);
  }

  @SuppressWarnings("unchecked")
  public static QuiltModContainerShim wrap(QuiltModContainerShim container) {
    if (container == null || container instanceof QuiltModContainerImpl) return container;   // Fabric host: already strips the suffix
    try {
      ClassLoader cl = container.getClass().getClassLoader();
      Class<?> ctr = Class.forName("org.quiltmc.loader.api.ModContainer", false, cl);
      Class<?> meta = Class.forName("org.quiltmc.loader.api.ModMetadata", false, cl);
      return (QuiltModContainerShim) Proxy.newProxyInstance(cl, new Class<?>[] {ctr}, new Delegate(container, meta));
    } catch (Throwable t) {
      return container;
    }
  }

  /** Delegates every call; metadata() comes back wrapped so id() drops the port suffix. */
  private static final class Delegate implements InvocationHandler {
    private final Object target; private final Class<?> metaType;
    Delegate(Object target, Class<?> metaType) { this.target = target; this.metaType = metaType; }
    @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
      if (m.getDeclaringClass() == Object.class) return m.getName().equals("toString") ? target.toString() : m.invoke(target, args);
      Object r = m.invoke(target, args);
      if (m.getName().equals("metadata") && r != null && metaType.isInstance(r)) {
        Object md = r;
        return Proxy.newProxyInstance(metaType.getClassLoader(), new Class<?>[] {metaType}, (p, mm, a) -> {
          if (mm.getDeclaringClass() == Object.class) return mm.invoke(md, a);
          Object v = mm.invoke(md, a);
          if (mm.getName().equals("id") && v instanceof String s && s.endsWith(SUFFIX)) return s.substring(0, s.length() - SUFFIX.length());
          return v;
        });
      }
      return r;
    }
  }
}
