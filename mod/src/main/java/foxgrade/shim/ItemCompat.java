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
}
