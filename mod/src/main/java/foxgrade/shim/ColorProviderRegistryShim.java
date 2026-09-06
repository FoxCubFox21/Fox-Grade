package foxgrade.shim;

/** 1.21.x Fabric {@code ColorProviderRegistry<T, Provider>} (an interface with BLOCK/ITEM instances). 26.2 has no
 *  runtime tint providers: registrations are kept for {@code get} but never drive rendering. */
public interface ColorProviderRegistryShim<T, P> {
  ColorProviderRegistryShim<Object, Object> BLOCK = new Impl("block");
  ColorProviderRegistryShim<Object, Object> ITEM = new Impl("item");
  @SuppressWarnings("unchecked")
  void register(P provider, T... objects);
  P get(T object);

  final class Impl implements ColorProviderRegistryShim<Object, Object> {
    private final String kind; private final java.util.Map<Object, Object> providers = new java.util.HashMap<>();
    Impl(String kind) { this.kind = kind; }
    @Override public void register(Object provider, Object... objects) {
      for (Object o : objects) providers.put(o, provider);
      System.err.println("[Fox-Grade] " + kind + " tint provider registered for " + objects.length + " entries — inactive on 26.2 (tints are data-driven now)");
    }
    @Override public Object get(Object object) { return providers.get(object); }
  }
}
