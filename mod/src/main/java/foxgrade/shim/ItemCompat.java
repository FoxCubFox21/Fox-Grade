package foxgrade.shim;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Small 1.21.x Item/ItemStack conveniences over 26.2. */
public final class ItemCompat {
  private ItemCompat() {}
  public static boolean is(ItemStack stack, Item item) { return stack.getItem() == item; }
  public static boolean is(ItemStack stack, TagKey<Item> tag) { return stack.typeHolder().is(tag); }
  public static Component getDescription(Item item) { return Component.translatable(item.getDescriptionId()); }
  public static Item itemOf(ItemStack stack) { return stack.getItem(); }

  // ---- batch 3
  /** 1.21.x {@code Ingredient.of(tag)} was lazy; 26.2 wants a bound HolderSet. Use the registry's live tag set (bound when tags load). */
  @SuppressWarnings("unchecked")
  public static net.minecraft.world.item.crafting.Ingredient ingredientOf(TagKey<Item> tag) {
    net.minecraft.core.HolderSet<Item> set;
    try {
      java.lang.reflect.Method m = net.minecraft.core.MappedRegistry.class.getDeclaredMethod("getOrCreateTagForRegistration", TagKey.class); m.setAccessible(true);
      set = (net.minecraft.core.HolderSet<Item>) m.invoke(net.minecraft.core.registries.BuiltInRegistries.ITEM, tag);
    } catch (ReflectiveOperationException | RuntimeException e) {
      set = net.minecraft.core.HolderSet.emptyNamed(net.minecraft.core.registries.BuiltInRegistries.ITEM, tag);
    }
    return net.minecraft.world.item.crafting.Ingredient.of(set);
  }
  public static void addCooldown(net.minecraft.world.item.ItemCooldowns c, Item item, int ticks) { c.addCooldown(new ItemStack(item), ticks); }
  public static boolean isOnCooldown(net.minecraft.world.item.ItemCooldowns c, Item item) { return c.isOnCooldown(new ItemStack(item)); }
  public static net.minecraft.world.item.DyeColor dyeColor(net.minecraft.world.item.DyeItem item) {
    String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    return net.minecraft.world.item.DyeColor.byName(path.endsWith("_dye") ? path.substring(0, path.length() - 4) : path, net.minecraft.world.item.DyeColor.WHITE);
  }
  public static java.util.List<net.minecraft.network.chat.Component> tooltipList(java.util.function.Consumer<net.minecraft.network.chat.Component> sink) { return new TooltipListShim(sink); }
  public static java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipConsumer(java.util.List<net.minecraft.network.chat.Component> list) { return list::add; }
  public static net.minecraft.world.item.component.TooltipDisplay tooltipDisplay() { return net.minecraft.world.item.component.TooltipDisplay.DEFAULT; }
  public static net.minecraft.world.item.SpawnEggItem spawnEgg(net.minecraft.world.entity.EntityType<?> type, int background, int highlight, Item.Properties properties) { return new net.minecraft.world.item.SpawnEggItem(properties.spawnEgg(type)); }
  public static String potionName() { return ""; }
  public static net.minecraft.nbt.Tag stackSave(ItemStack stack, net.minecraft.core.HolderLookup.Provider provider) {
    return ItemStack.CODEC.encodeStart(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack).getOrThrow();
  }
  public static java.util.Optional<ItemStack> stackParse(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.Tag tag) {
    return ItemStack.CODEC.parse(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).result();
  }

  // ---- 1.20.1 shapes: food lived on the Item
  public static net.minecraft.world.food.FoodProperties getFoodProperties(Item item) { return item.components().get(net.minecraft.core.component.DataComponents.FOOD); }
  public static boolean isEdible(Item item) { return getFoodProperties(item) != null; }
  public static java.util.List<?> foodEffects(net.minecraft.world.food.FoodProperties food) { return java.util.List.of(); }

  /** {@code new Item.Properties()}: 26.2 needs the registry id first; a placeholder is rewritten at registration. */
  public static Item.Properties properties() { return new Item.Properties().setId(RegistryCompat.pendingItemKey()); }
  // ---- crafting remainders (1.21.x Item.getCraftingRemainingItem / FabricItemStack.getRecipeRemainder)
  public static Item craftingRemainingItem(Item item) { net.minecraft.world.item.ItemStackTemplate t = item.getCraftingRemainder(); return t == null ? null : t.item().value(); }
  public static ItemStack getRecipeRemainder(ItemStack stack) { net.minecraft.world.item.ItemStackTemplate t = stack.getItem().getCraftingRemainder(); return t == null ? ItemStack.EMPTY : t.create(); }
  /** The call site holds Fabric's injected interface type; at runtime it is the ItemStack itself. */
  public static ItemStack getRecipeRemainderOf(Object stack) { return stack instanceof ItemStack s ? getRecipeRemainder(s) : ItemStack.EMPTY; }

  /** 1.21.x code read {@code item.components()} during registration; 26.2 binds components later. Empty until bound. */
  public static net.minecraft.core.component.DataComponentMap components(Item item) {
    try { return item.components(); } catch (RuntimeException notBoundYet) { return net.minecraft.core.component.DataComponentMap.EMPTY; }
  }
  /** Fabric convention tags a 1.21.x mod still names; the tag key is created from the same id so data packs can fill it. */
  public static net.minecraft.tags.TagKey<Item> conventionTag(String path) { return net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", path)); }
  public static net.minecraft.tags.TagKey<Item> spearTools() { return conventionTag("tools/spear"); }
  public static net.minecraft.tags.TagKey<Item> shearsTools() { return conventionTag("tools/shear"); }
  /** {@code new ItemStack(item[, count])}. 26.2 binds an item's components only after the registries are frozen, and an
   *  ItemStack built before that (a mixin's static initialiser merged into a vanilla class that loads early) throws
   *  "Components not bound yet". Such a stack is built through the private component-map constructor instead, with empty
   *  components; once binding has happened the ordinary constructor is used. */
  public static net.minecraft.world.item.ItemStack stack(net.minecraft.world.level.ItemLike like, int count) {
    net.minecraft.world.item.Item item = like.asItem();
    net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item> holder = item.builtInRegistryHolder();
    if (holder.areComponentsBound()) return new net.minecraft.world.item.ItemStack(like, count);
    try {
      java.lang.reflect.Constructor<net.minecraft.world.item.ItemStack> c = net.minecraft.world.item.ItemStack.class.getDeclaredConstructor(
          net.minecraft.core.Holder.class, int.class, net.minecraft.core.component.PatchedDataComponentMap.class);
      c.setAccessible(true);
      return c.newInstance(holder, count, new net.minecraft.core.component.PatchedDataComponentMap(net.minecraft.core.component.DataComponentMap.EMPTY));
    } catch (ReflectiveOperationException e) { throw new IllegalStateException("early ItemStack of " + item, e); }
  }
  public static net.minecraft.world.item.ItemStack stack(net.minecraft.world.level.ItemLike like) { return stack(like, 1); }
  private static net.minecraft.core.component.DataComponentType<net.minecraft.util.Unit> HIDE_ADDITIONAL_TOOLTIP;
  /** 1.21's {@code DataComponents.HIDE_ADDITIONAL_TOOLTIP}: 26.2 folded it into TOOLTIP_DISPLAY (a different value type). A
   *  registered unit component keeps stacks that set it valid; 26.2 just does not read it. */
  public static synchronized net.minecraft.core.component.DataComponentType<net.minecraft.util.Unit> hideAdditionalTooltip() {
    if (HIDE_ADDITIONAL_TOOLTIP != null) return HIDE_ADDITIONAL_TOOLTIP;
    net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath("foxgrade", "hide_additional_tooltip");
    var reg = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE;
    @SuppressWarnings("unchecked") net.minecraft.core.component.DataComponentType<net.minecraft.util.Unit> existing = (net.minecraft.core.component.DataComponentType<net.minecraft.util.Unit>) reg.getValue(id);
    if (existing != null) return HIDE_ADDITIONAL_TOOLTIP = existing;
    net.minecraft.core.component.DataComponentType<net.minecraft.util.Unit> type = net.minecraft.core.component.DataComponentType.<net.minecraft.util.Unit>builder().persistent(net.minecraft.util.Unit.CODEC).build();
    return HIDE_ADDITIONAL_TOOLTIP = net.minecraft.core.Registry.register(reg, id, type);
  }
}
