package foxgrade.shim;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/** 1.21.x {@code net.minecraft.world.item.Tier}: mods implement it for custom tool tiers; 26.2 uses the ToolMaterial record. */
public interface TierShim {
  int getUses();
  float getSpeed();
  float getAttackDamageBonus();
  TagKey<Block> getIncorrectBlocksForDrops();
  int getEnchantmentValue();
  Ingredient getRepairIngredient();
  /** The 26.2 attribute builders are private on the record; reach them reflectively (names are Mojang's, unobfuscated at runtime). */
  static net.minecraft.world.item.component.ItemAttributeModifiers attributes(ToolMaterial material, String builder, float damage, float speed) {
    try {
      java.lang.reflect.Method m = ToolMaterial.class.getDeclaredMethod(builder, float.class, float.class); m.setAccessible(true);
      return (net.minecraft.world.item.component.ItemAttributeModifiers) m.invoke(material, damage, speed);
    } catch (ReflectiveOperationException e) { return net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY; }
  }
  static ToolMaterial material(TierShim tier) {
    return new ToolMaterial(tier.getIncorrectBlocksForDrops(), tier.getUses(), tier.getSpeed(), tier.getAttackDamageBonus(), tier.getEnchantmentValue(), null);
  }
}
