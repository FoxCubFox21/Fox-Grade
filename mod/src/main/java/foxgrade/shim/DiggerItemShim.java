package foxgrade.shim;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;

/** 1.21.x {@code DiggerItem} (pickaxes, axes, shovels, hoes): 26.2 makes tools plain items with tool properties. */
public class DiggerItemShim extends Item {
  public DiggerItemShim(TierShim tier, TagKey<Block> mineable, Properties properties) { super(properties.tool(TierShim.material(tier), mineable, 1f, -3f, 0f)); }
  public DiggerItemShim(TierShim tier, TagKey<Block> mineable, float damage, float speed, Properties properties) { super(properties.tool(TierShim.material(tier), mineable, damage, speed, 0f)); }
  public static ItemAttributeModifiers createAttributes(TierShim tier, float damage, float speed) { return TierShim.attributes(TierShim.material(tier), "createToolAttributes", damage, speed); }
}
