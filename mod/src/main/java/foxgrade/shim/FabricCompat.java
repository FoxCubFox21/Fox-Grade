package foxgrade.shim;

/** Small Fabric API constants 26.2 dropped. */
public final class FabricCompat {
  private FabricCompat() {}
  /** {@code ResourceReloadListenerKeys.TAGS}: the vanilla tag-loader id, so ordering against it still resolves. */
  public static net.minecraft.resources.Identifier reloadKeyTags() { return net.minecraft.resources.Identifier.withDefaultNamespace("tags"); }
  /** Fabric's old 5-argument built-in resource pack registration (id, sub-path, container, display name, activation):
   *  26.2's API has no sub-path form; the 4-argument one covers it. */
  public static boolean registerBuiltinResourcePack(net.minecraft.resources.Identifier id, String subPath, net.fabricmc.loader.api.ModContainer container, net.minecraft.network.chat.Component name, Object activation) {
    try {
      Class<?> api = Class.forName("net.fabricmc.fabric.api.resource.ResourceManagerHelper", true, container.getClass().getClassLoader());
      for (java.lang.reflect.Method m : api.getMethods()) {
        if (!m.getName().equals("registerBuiltinResourcePack") || m.getParameterCount() != 4) continue;
        Class<?>[] p = m.getParameterTypes();
        if (p[2] == net.minecraft.network.chat.Component.class && p[3].isInstance(activation)) return (Boolean) m.invoke(null, id, container, name, activation);
      }
    } catch (ReflectiveOperationException | RuntimeException e) { System.err.println("[Fox-Grade] built-in resource pack " + id + " not registered: " + e); }
    return false;
  }
}
