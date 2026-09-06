package foxgrade.shim;

import java.util.HashMap;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;

public final class ModelCompat {
  private ModelCompat() { }
  /** A root for models 26.2 insists be constructed with one; children may be attached later. */
  public static ModelPart emptyRoot() { return new ModelPart(List.of(), new HashMap<>()); }

  public static java.util.stream.Stream<net.minecraft.client.model.geom.ModelPart> getAllParts(net.minecraft.client.model.geom.ModelPart part) { return part.getAllParts().stream(); }
  // 1.21.x Fabric ModelLoadingPlugin.Context.addModels(...): 26.2 registers extra models through typed keys; the old
  // "load these ids" form has no equivalent, so the ids are noted and the models stay unloaded (missing-model where used).
  private static boolean saidModels;
  private static void noteModels(int n) { if (!saidModels) { saidModels = true; System.err.println("[Fox-Grade] " + n + "+ extra models requested through the 1.21.x model-loading API are not loaded on 26.2 (typed extra-model keys now)"); } }
  public static void addModels(Object context, java.util.Collection<?> ids) { noteModels(ids == null ? 0 : ids.size()); }
  public static void addModels(Object context, net.minecraft.resources.Identifier[] ids) { noteModels(ids == null ? 0 : ids.length); }
  /** {@code PreparableModelLoadingPlugin.register(loader, plugin)} where {@code loader} was compiled against the 1.21
   *  {@code load(ResourceManager, Executor)} signature. 26.2 passes the reload's shared state instead; the old loader object
   *  still carries its {@code load(ResourceManager, Executor)} method (the lambda factory built it from that shape), so a
   *  proxy implementing the new interface calls it with the state's resource manager. The model-loading module is not on
   *  Fox-Grade's compile classpath, hence the reflection. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void registerPreparable(Object loader, Object plugin) {
    try {
      ClassLoader cl = loader.getClass().getClassLoader();
      Class<?> pluginItf = Class.forName("net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin", true, cl);
      Class<?> loaderItf = Class.forName("net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin$DataLoader", true, cl);
      java.lang.reflect.Method oldLoad = null;
      for (java.lang.reflect.Method m : loader.getClass().getMethods())
        if (m.getName().equals("load") && m.getParameterCount() == 2 && !m.getParameterTypes()[0].isAssignableFrom(net.minecraft.server.packs.resources.PreparableReloadListener.SharedState.class)) { oldLoad = m; break; }
      Object wrapped;
      if (oldLoad == null) wrapped = loader;   // already the 26.2 shape
      else {
        final java.lang.reflect.Method call = oldLoad; call.setAccessible(true);
        wrapped = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[] {loaderItf}, (proxy, m, args) -> {
          if (m.getName().equals("load") && args != null && args.length == 2)
            return call.invoke(loader, ((net.minecraft.server.packs.resources.PreparableReloadListener.SharedState) args[0]).resourceManager(), args[1]);
          if (m.getDeclaringClass() == Object.class) return m.invoke(loader, args);
          return null;
        });
      }
      pluginItf.getMethod("register", loaderItf, pluginItf).invoke(null, wrapped, plugin);
    } catch (ReflectiveOperationException e) { throw new RuntimeException("PreparableModelLoadingPlugin bridge", e); }
  }
}
