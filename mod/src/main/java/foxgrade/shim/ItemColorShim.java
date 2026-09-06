package foxgrade.shim;

import net.minecraft.world.item.ItemStack;

/** 1.21.x {@code net.minecraft.client.color.item.ItemColor}; see {@link BlockColorShim}. */
public interface ItemColorShim {
  int getColor(ItemStack stack, int tintIndex);
}
