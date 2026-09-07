package foxgrade.shim;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 26.2 wants an item's or block's registry id in its Properties BEFORE construction; 1.21.x mods
 *  construct first and register after. Properties get a placeholder id, and registration rewrites
 *  the derived names (description id, ITEM_NAME/ITEM_MODEL components) to the real one. */
public final class RegistryCompat {
  private RegistryCompat() {}
  public static final String PENDING_NS = "foxgrade";
  private static final AtomicInteger SEQ = new AtomicInteger();
  public static ResourceKey<Item> pendingItemKey() { return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PENDING_NS, "pending_item_" + SEQ.incrementAndGet())); }
  public static ResourceKey<Block> pendingBlockKey() { return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(PENDING_NS, "pending_block_" + SEQ.incrementAndGet())); }
  private static boolean pending(ResourceKey<?> key) { return key != null && PENDING_NS.equals(key.identifier().getNamespace()) && key.identifier().getPath().startsWith("pending_"); }

  // ---- Registry.register / registerForHolder call sites (raw types: the descriptors are erased anyway)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object register(Registry registry, String id, Object value) { return register(registry, Identifier.parse(id), value); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object register(Registry registry, Identifier id, Object value) { value = toRegistryShape(registry, value); fixUp(registry, id, value); return registerOrDefer(registry, ResourceKey.create(registry.key(), id), value); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object register(Registry registry, ResourceKey key, Object value) { value = toRegistryShape(registry, value); fixUp(registry, key.identifier(), value); return registerOrDefer(registry, key, value); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Holder.Reference registerForHolder(Registry registry, Identifier id, Object value) { value = toRegistryShape(registry, value); fixUp(registry, id, value); return Registry.registerForHolder(registry, id, value); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Holder.Reference registerForHolder(Registry registry, ResourceKey key, Object value) { value = toRegistryShape(registry, value); fixUp(registry, key.identifier(), value); return Registry.registerForHolder(registry, key, value); }

  // ---- 26.2 builds every block state's cache INSIDE Registry.register (1.21.x rebuilt caches after
  // all registration). A block whose getShape reads its own registry holder therefore NPEs before
  // the holder is assigned. If the entry made it into the registry, the cache is built later instead.
  private static final java.util.List<Block> DEFERRED = new java.util.ArrayList<>();
  private static boolean hooked;
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object registerOrDefer(Registry registry, ResourceKey key, Object value) {
    try {
      return Registry.register(registry, key, value);
    } catch (RuntimeException e) {
      if (value instanceof Block block && registry.containsKey(key) && causedByCache(e)) {
        synchronized (DEFERRED) { DEFERRED.add(block); if (!hooked) { hooked = true; net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register((s) -> flushDeferredCaches()); } }
        System.err.println("[Fox-Grade] " + key.identifier() + ": block state cache deferred (its shape reads a registry entry that was not assigned yet)");
        return value;
      }
      throw e;
    }
  }
  private static boolean causedByCache(Throwable t) {
    for (Throwable x = t; x != null; x = x.getCause()) for (StackTraceElement f : x.getStackTrace()) if (f.getMethodName().equals("initCache")) return true;
    return false;
  }
  public static void flushDeferredCaches() {
    java.util.List<Block> todo; synchronized (DEFERRED) { todo = new java.util.ArrayList<>(DEFERRED); DEFERRED.clear(); }
    for (Block b : todo) for (net.minecraft.world.level.block.state.BlockState st : b.getStateDefinition().getPossibleStates()) {
      try { st.initCache(); } catch (RuntimeException e) { System.err.println("[Fox-Grade] " + b + ": state cache still failing: " + e); }
    }
  }
  /** 26.2 turned several "type" registries (structure processors, loot entries/functions/conditions/providers, rule
   *  tests) into registries of MapCodecs. A 1.21 mod registers a type object — a record holding the codec, or a lambda
   *  implementing the old functional interface — so when the registry's existing entries are MapCodecs and the value is
   *  not one, the value's own {@code codec()} is what 26.2 wants (a plain Codec is wrapped as a map codec). */
  static Object toRegistryShape(Registry<?> registry, Object value) {
    if (value instanceof com.mojang.serialization.MapCodec) return value;
    java.util.Iterator<?> it = ((Iterable<?>) registry).iterator();
    if (!it.hasNext() || !(it.next() instanceof com.mojang.serialization.MapCodec)) return value;
    // the holder's accessor is usually codec(); a lambda of the old functional interface may keep an intermediary name
    // (method_16822), so any zero-argument method that yields a codec counts
    java.util.List<java.lang.reflect.Method> cands = new java.util.ArrayList<>();
    for (java.lang.reflect.Method m : value.getClass().getMethods()) if (m.getName().equals("codec") && m.getParameterCount() == 0) cands.add(m);
    for (java.lang.reflect.Method m : value.getClass().getDeclaredMethods()) if (m.getParameterCount() == 0 && !java.lang.reflect.Modifier.isStatic(m.getModifiers()) && !cands.contains(m)) cands.add(m);
    for (java.lang.reflect.Method m : cands) {
      Class<?> rt = m.getReturnType();
      if (!com.mojang.serialization.Codec.class.isAssignableFrom(rt) && !com.mojang.serialization.MapCodec.class.isAssignableFrom(rt) && rt != Object.class) continue;
      try {
        m.setAccessible(true);
        Object c = m.invoke(value);
        if (c instanceof com.mojang.serialization.MapCodec) return c;
        if (c instanceof com.mojang.serialization.Codec<?> codec) return com.mojang.serialization.MapCodec.assumeMapUnsafe(codec);
      } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    return value;
  }

  private static void fixUp(Registry<?> registry, Identifier id, Object value) {
    try {
      if (value instanceof Item item && registry == BuiltInRegistries.ITEM) fixItem(item, id);
      else if (value instanceof Block block && registry == BuiltInRegistries.BLOCK) fixBlock(block, id);
      else if (value instanceof net.minecraft.world.entity.EntityType<?> et && registry == BuiltInRegistries.ENTITY_TYPE) fixEntityType(et, id);
    } catch (Throwable t) {
      System.err.println("[Fox-Grade] could not rewrite placeholder id of " + id + ": " + t);
    }
  }
  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    for (Class<?> k = c; k != null; k = k.getSuperclass()) { try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignore) { } }
    throw new NoSuchFieldException(name);
  }
  private static void fixItem(Item item, Identifier id) throws Exception {
    Field descF = field(Item.class, "descriptionId");
    String current = (String) descF.get(item);
    if (current == null || !current.contains("." + PENDING_NS + ".pending_")) return;
    String pendingPath = current.substring(current.indexOf(PENDING_NS + ".pending_") + PENDING_NS.length() + 1);
    String prefix = item instanceof BlockItem ? "block" : "item";
    String desc = prefix + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    descF.set(item, desc);
    // 26.2 keeps an item's components as a deferred initializer keyed by its registry key: relink it
    relinkInitializer(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PENDING_NS, pendingPath)), ResourceKey.create(Registries.ITEM, id), desc, id);
  }
  /** Move the component initializer registered under the placeholder key to the real key, fixing the
   *  name/model components that were derived from the placeholder. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void relinkInitializer(ResourceKey<?> placeholder, ResourceKey<?> real, String desc, Identifier id) throws Exception {
    Object inits = BuiltInRegistries.DATA_COMPONENT_INITIALIZERS;
    Field listF = field(inits.getClass(), "initializers");
    java.util.List list = (java.util.List) listF.get(inits);
    Object found = null; Object initializer = null;
    for (Object entry : list) {
      java.lang.reflect.RecordComponent[] rc = entry.getClass().getRecordComponents();
      if (rc == null) continue;
      Object key = null, init = null;
      for (java.lang.reflect.RecordComponent c : rc) { java.lang.reflect.Method acc = c.getAccessor(); acc.setAccessible(true); Object v = acc.invoke(entry); if (v instanceof ResourceKey) key = v; else init = v; }
      if (placeholder.equals(key)) { found = entry; initializer = init; break; }
    }
    if (found == null) return;
    list.remove(found);
    final net.minecraft.core.component.DataComponentInitializers.Initializer base = (net.minecraft.core.component.DataComponentInitializers.Initializer) initializer;
    ((net.minecraft.core.component.DataComponentInitializers) inits).add((ResourceKey) real, (builder, provider, key) -> {
      base.run(builder, provider, key);
      if (desc != null) builder.set(DataComponents.ITEM_NAME, Component.translatable(desc));
      if (id != null && real.registry().equals(Registries.ITEM.identifier())) builder.set(DataComponents.ITEM_MODEL, id);
    });
  }
  private static void fixBlock(Block block, Identifier id) throws Exception {
    Field descF = field(BlockBehaviour.class, "descriptionId");
    String current = (String) descF.get(block);
    if (current != null && current.contains("." + PENDING_NS + ".pending_")) descF.set(block, "block." + id.getNamespace() + "." + id.getPath().replace('/', '.'));
    Field propsF = field(BlockBehaviour.class, "properties");
    Object props = propsF.get(block);
    Field idF = field(props.getClass(), "id");
    Object key = idF.get(props);
    if (key instanceof ResourceKey<?> rk && pending(rk)) { idF.set(props, ResourceKey.create(Registries.BLOCK, id)); relinkInitializer(rk, ResourceKey.create(Registries.BLOCK, id), null, null); }
  }

  // ---- 1.21.x armor materials were a registry; 26.2 passes the record around directly
  private static MappedRegistry<ArmorMaterial> ARMOR;
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static synchronized Registry<ArmorMaterial> armorMaterialRegistry() {
    if (ARMOR == null) ARMOR = new MappedRegistry(ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(PENDING_NS, "armor_material")), com.mojang.serialization.Lifecycle.stable());
    return ARMOR;
  }
  /** 1.21.x {@code new ArmorMaterial(defense, enchantability, equipSound, repairIngredient, layers, toughness, knockbackResistance)}. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.world.item.equipment.ArmorMaterial armorMaterial(java.util.Map defense, int enchantability, net.minecraft.core.Holder equipSound, java.util.function.Supplier repairIngredient, java.util.List layers, float toughness, float knockbackResistance) {
    net.minecraft.resources.ResourceKey asset = net.minecraft.world.item.equipment.EquipmentAssets.IRON;
    if (layers != null && !layers.isEmpty() && layers.get(0) instanceof ArmorMaterialLayerShim layer)
      asset = net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, layer.assetName());
    return new net.minecraft.world.item.equipment.ArmorMaterial(15, defense, enchantability, equipSound, toughness, knockbackResistance, net.minecraft.tags.ItemTags.PLANKS, asset);
  }
  /** 1.21's {@code Registry.getOrCreateTag(TagKey)}: the named holder set for a tag, created empty when unknown. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.core.HolderSet.Named getOrCreateTag(net.minecraft.core.Registry registry, net.minecraft.tags.TagKey key) {
    for (String n : new String[] {"getOrThrow", "get", "getTag"}) {
      try {
        java.lang.reflect.Method m = registry.getClass().getMethod(n, net.minecraft.tags.TagKey.class);
        Object r = m.invoke(registry, key);
        if (r instanceof java.util.Optional<?> o) r = o.orElse(null);
        if (r instanceof net.minecraft.core.HolderSet.Named named) return named;
      } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    return net.minecraft.core.HolderSet.emptyNamed(registry, key);
  }
  /** 1.21's {@code Registry.get(id)} / {@code get(key)} / {@code getOrThrow(key)} returning the VALUE: renamed getValue* in 26.2. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object getValue(Registry registry, Identifier id) { return registry.getValue(id); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object getValue(Registry registry, ResourceKey key) { return registry.getValue(key); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object getValueOrThrow(Registry registry, ResourceKey key) { return registry.getValueOrThrow(key); }

  private static int pendingEntityTypes = 0;
  /** 1.21's {@code EntityType.Builder.build()} / {@code build(String)}: 26.2 needs the registry key up front. A placeholder
   *  key is used and the description id is repaired when the type is registered. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.world.entity.EntityType buildEntityType(net.minecraft.world.entity.EntityType.Builder builder, String id) {
    Identifier ident = id != null && id.contains(":") ? Identifier.parse(id) : Identifier.fromNamespaceAndPath(PENDING_NS, "pending_" + (pendingEntityTypes++) + (id == null ? "" : "_" + id.replaceAll("[^a-z0-9_./-]", "_")));
    return builder.build(ResourceKey.create(Registries.ENTITY_TYPE, ident));
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.world.entity.EntityType buildEntityType(net.minecraft.world.entity.EntityType.Builder builder) { return buildEntityType(builder, null); }
  private static void fixEntityType(net.minecraft.world.entity.EntityType<?> type, Identifier id) throws Exception {
    Field descF = field(net.minecraft.world.entity.EntityType.class, "descriptionId");
    String current = (String) descF.get(type);
    if (current != null && current.contains("." + PENDING_NS + ".pending_")) descF.set(type, "entity." + id.getNamespace() + "." + id.getPath().replace('/', '.'));
  }
}
