package foxgrade.shim;

/** 1.21.x {@code net.minecraft.client.renderer.item.ItemProperties}: item model predicates are data-driven on 26.2. */
public final class ItemPropertiesShim {
  private ItemPropertiesShim() {}
  private static boolean said;
  private static void note() { if (!said) { said = true; System.err.println("[Fox-Grade] item model predicates (ItemProperties.register) are data-driven on 26.2; 1.21.x registrations are ignored"); } }
  public static void register(net.minecraft.world.item.Item item, net.minecraft.resources.Identifier id, ClampedItemPropertyFunctionShim function) { note(); }
  public static void registerGeneric(net.minecraft.resources.Identifier id, ClampedItemPropertyFunctionShim function) { note(); }
  public static ClampedItemPropertyFunctionShim getProperty(net.minecraft.world.item.ItemStack stack, net.minecraft.resources.Identifier id) { return null; }
}
