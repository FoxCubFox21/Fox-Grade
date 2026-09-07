package foxgrade.shim;

/** Type stand-in for 1.21's {@code ItemOverrides}: no overrides ever resolve; the base model is what is asked for. */
public class ItemOverridesShim {
  public static final ItemOverridesShim EMPTY = new ItemOverridesShim();
  public ItemOverridesShim() { }
  public ItemOverridesShim(Object baker, Object model, java.util.List<?> overrides) { }
  public Object resolve(Object model, net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.LivingEntity entity, int seed) { return model; }
  public java.util.List<ItemOverrideShim> getOverrides() { return java.util.List.of(); }
}
