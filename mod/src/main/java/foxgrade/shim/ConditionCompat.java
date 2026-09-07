package foxgrade.shim;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/** Fabric resource conditions: 1.21 tested against a {@code HolderLookup.Provider}, 26.2 against a
 *  {@code RegistryOps.RegistryInfoLookup}. A provider view over the info lookup answers key and tag lookups (what
 *  conditions do); listing is unsupported. */
public final class ConditionCompat {
  private ConditionCompat() {}
  public static HolderLookup.Provider provider(RegistryOps.RegistryInfoLookup info) {
    if (info instanceof HolderLookup.Provider p) return p;
    return (HolderLookup.Provider) Proxy.newProxyInstance(ConditionCompat.class.getClassLoader(), new Class<?>[] {HolderLookup.Provider.class}, new Handler(info, null, null));
  }
  private record Handler(RegistryOps.RegistryInfoLookup info, ResourceKey<?> key, RegistryOps.RegistryInfo<?> reg) implements InvocationHandler {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
      String n = m.getName();
      if (reg == null) {   // the provider
        if (n.equals("lookup") && args != null && args.length == 1) {
          Optional<? extends RegistryOps.RegistryInfo<?>> ri = info.lookup((ResourceKey) args[0]);
          return ri.map(r -> Proxy.newProxyInstance(ConditionCompat.class.getClassLoader(), new Class<?>[] {HolderLookup.RegistryLookup.class}, new Handler(info, (ResourceKey<?>) args[0], r)));
        }
        if (n.equals("listRegistryKeys") || n.equals("listRegistries")) return java.util.stream.Stream.empty();
      } else {              // one registry lookup
        if (n.equals("get") && args != null && args.length == 1) return args[0] instanceof ResourceKey rk ? reg.getter().get(rk) : reg.getter().get((net.minecraft.tags.TagKey) args[0]);
        if (n.equals("getOrThrow") && args != null && args.length == 1) return args[0] instanceof ResourceKey rk ? reg.getter().getOrThrow(rk) : reg.getter().getOrThrow((net.minecraft.tags.TagKey) args[0]);
        if (n.equals("key")) return key;
        if (n.equals("listElements") || n.equals("listTags") || n.equals("listElementIds") || n.equals("listTagIds")) return java.util.stream.Stream.empty();
        if (n.equals("canSerializeIn")) return reg.owner().canSerializeIn((net.minecraft.core.HolderOwner) args[0]);
        if (n.equals("registryLifecycle")) return reg.elementsLifecycle();
      }
      if (n.equals("toString")) return "ConditionCompat" + (key == null ? "" : "[" + key + "]");
      if (n.equals("hashCode")) return System.identityHashCode(proxy);
      if (n.equals("equals")) return proxy == args[0];
      if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
      throw new UnsupportedOperationException(n + " on a resource-condition registry view");
    }
  }
}
