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
  /** Fabric API used to register an umbrella mod id {@code "fabric"} alongside {@code "fabric-api"}, and dropped it.
   *  A 1.21-era mod that asks the loader whether {@code "fabric"} is present therefore concludes Fabric API is not
   *  installed even when it is, and refuses to start (Roughly Enough Items does exactly this and shows a dialog saying
   *  Fabric API is missing). Answer the umbrella id from the real one; every other id is passed straight through, so a
   *  mod branching on some other mod's presence still gets the truth. */
  public static boolean isModLoaded(net.fabricmc.loader.api.FabricLoader loader, String id) {
    if (loader == null || id == null) return false;
    if (loader.isModLoaded(id)) return true;
    return id.equals("fabric") && loader.isModLoaded("fabric-api");
  }
}
