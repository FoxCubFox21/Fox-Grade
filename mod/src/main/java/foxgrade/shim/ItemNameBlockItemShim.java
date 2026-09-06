package foxgrade.shim;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

/** 1.21.x {@code ItemNameBlockItem} (seeds, etc.): a BlockItem named after the item. */
public class ItemNameBlockItemShim extends BlockItem {
  public ItemNameBlockItemShim(Block block, Properties properties) { super(block, properties); }
}
