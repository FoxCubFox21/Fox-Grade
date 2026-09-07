package foxgrade.shim;

import com.mojang.serialization.MapCodec;

/** {@code getType()} overrides of 1.21 loot entries/functions/conditions become 26.2's {@code codec()}: unwrap the type
 *  holder (a record, a shim, or a lambda of the old functional interface) to the MapCodec 26.2 expects. */
public final class LootTypeCompat {
  private LootTypeCompat() {}
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static MapCodec unwrap(Object type) {
    if (type instanceof MapCodec m) return m;
    if (type == null) return null;
    java.util.List<java.lang.reflect.Method> cands = new java.util.ArrayList<>();
    for (java.lang.reflect.Method m : type.getClass().getMethods()) if (m.getName().equals("codec") && m.getParameterCount() == 0) cands.add(m);
    for (java.lang.reflect.Method m : type.getClass().getDeclaredMethods()) if (m.getParameterCount() == 0 && !java.lang.reflect.Modifier.isStatic(m.getModifiers()) && !cands.contains(m)) cands.add(m);
    for (java.lang.reflect.Method m : cands) {
      Class<?> rt = m.getReturnType();
      if (!com.mojang.serialization.Codec.class.isAssignableFrom(rt) && !MapCodec.class.isAssignableFrom(rt) && rt != Object.class) continue;
      try {
        m.setAccessible(true);
        Object c = m.invoke(type);
        if (c instanceof MapCodec mc) return mc;
        if (c instanceof com.mojang.serialization.Codec codec) return MapCodec.assumeMapUnsafe(codec);
      } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    throw new IllegalStateException("no codec on " + type.getClass());
  }
}
