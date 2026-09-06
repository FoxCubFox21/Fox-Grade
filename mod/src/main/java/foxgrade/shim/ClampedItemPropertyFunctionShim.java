package foxgrade.shim;

/** 1.21.x {@code ClampedItemPropertyFunction} (item model predicates); 26.2 item models select by data-driven properties. */
public interface ClampedItemPropertyFunctionShim {
  float unclampedCall(net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.LivingEntity entity, int seed);
  default float call(net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.LivingEntity entity, int seed) { return Math.max(0f, Math.min(1f, unclampedCall(stack, level, entity, seed))); }
}
