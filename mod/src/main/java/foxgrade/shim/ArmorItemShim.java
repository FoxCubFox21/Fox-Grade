package foxgrade.shim;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;

/**
 * 1.21.x ArmorItem (removed: armour is an ordinary Item with an equippable component). A mod's
 * material was a registry holder of a type that no longer exists, so a built-in material of the
 * same tier is substituted when it cannot be mapped — the item equips and renders; its exact
 * stats may differ, and the report says so.
 */
public class ArmorItemShim extends Item {
  private final ArmorType type;
  public ArmorItemShim(Object material, ArmorType type, Item.Properties props) { super(props.humanoidArmor(material(material), type)); this.type = type; }
  /** 1.21.x {@code ArmorItem(Holder<ArmorMaterial>, ArmorItem.Type, Properties)} — the exact descriptor mods compile against. */
  public ArmorItemShim(net.minecraft.core.Holder<?> material, ArmorType type, Item.Properties props) { this((Object) material, type, props); }
  public ArmorItemShim(ArmorMaterial material, ArmorType type, Item.Properties props) { this((Object) material, type, props); }
  public ArmorType getType() { return type; }
  private static ArmorMaterial material(Object m) {
    if (m instanceof ArmorMaterial am) return am;
    if (m instanceof net.minecraft.core.Holder<?> h && h.value() instanceof ArmorMaterial am) return am;
    String n = String.valueOf(m).toLowerCase();
    if (n.contains("netherite")) return ArmorMaterials.NETHERITE;
    if (n.contains("diamond")) return ArmorMaterials.DIAMOND;
    if (n.contains("gold")) return ArmorMaterials.GOLD;
    if (n.contains("chain")) return ArmorMaterials.CHAINMAIL;
    if (n.contains("leather")) return ArmorMaterials.LEATHER;
    return ArmorMaterials.IRON;
  }
}
