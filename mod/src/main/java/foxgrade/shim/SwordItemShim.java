package foxgrade.shim;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** 1.21.x {@code SwordItem}: 26.2 makes swords plain items with sword properties. */
public class SwordItemShim extends Item {
  public SwordItemShim(TierShim tier, Properties properties) { super(properties.sword(TierShim.material(tier), 3f, -2.4f)); }
  public SwordItemShim(TierShim tier, int damage, float speed, Properties properties) { super(properties.sword(TierShim.material(tier), damage, speed)); }
  public static ItemAttributeModifiers createAttributes(TierShim tier, int damage, float speed) { return TierShim.attributes(TierShim.material(tier), "createSwordAttributes", damage, speed); }
}
