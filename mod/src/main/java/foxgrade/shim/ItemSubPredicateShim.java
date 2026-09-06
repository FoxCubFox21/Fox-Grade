package foxgrade.shim;

/** Stands in for 1.21's {@code net.minecraft.advancements.critereon.ItemSubPredicate}, removed in 26.2 (item predicates are
 *  data components now). Mods that registered their own sub-predicate types keep loading; nothing evaluates them. */
public interface ItemSubPredicateShim {
  boolean matches(net.minecraft.world.item.ItemStack stack);
}
