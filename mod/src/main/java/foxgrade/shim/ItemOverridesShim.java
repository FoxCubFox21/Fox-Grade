package foxgrade.shim;

/** 1.21.x {@code ItemOverrides}: per-item model predicates; 26.2 selects item models from data. */
public class ItemOverridesShim {
  public static final ItemOverridesShim EMPTY = new ItemOverridesShim();
  public ItemOverridesShim() {}
  public Object resolve(Object model, net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.LivingEntity entity, int seed) { return model; }
}
