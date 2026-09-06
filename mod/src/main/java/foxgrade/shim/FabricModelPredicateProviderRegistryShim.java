package foxgrade.shim;

/** 1.21.x Fabric {@code FabricModelPredicateProviderRegistry}: item model predicates are data-driven on 26.2. */
public final class FabricModelPredicateProviderRegistryShim {
  private FabricModelPredicateProviderRegistryShim() {}
  private static boolean said;
  private static void note() { if (!said) { said = true; System.err.println("[Fox-Grade] item model predicates are data-driven on 26.2; predicate providers from a 1.21.x mod are ignored"); } }
  public static void register(net.minecraft.resources.Identifier id, ClampedItemPropertyFunctionShim provider) { note(); }
  public static void register(net.minecraft.world.item.Item item, net.minecraft.resources.Identifier id, ClampedItemPropertyFunctionShim provider) { note(); }
}
