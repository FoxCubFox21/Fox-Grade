package foxgrade.shim;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemCompat {
  private ItemCompat() { }
  public static boolean is(ItemStack s, Item i) { return s.getItem() == i; }
  public static Component getDescription(Item i) { return Component.translatable(i.getDescriptionId()); }
}
