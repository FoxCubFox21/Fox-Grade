package foxgrade.shim;

/** 1.21.x Fabric {@code TradeOfferHelper}: villager trades are data-driven on 26.2; registrations are noted and ignored. */
public final class TradeOfferHelperShim {
  private TradeOfferHelperShim() {}
  private static boolean said;
  private static void note() { if (!said) { said = true; System.err.println("[Fox-Grade] TradeOfferHelper: trades are data-driven on 26.2; offers registered by a 1.21.x mod are ignored"); } }
  public static void registerVillagerOffers(net.minecraft.world.entity.npc.villager.VillagerProfession profession, int level, java.util.function.Consumer<?> factory) { note(); }
  public static void registerWanderingTraderOffers(int level, java.util.function.Consumer<?> factory) { note(); }
  public static void registerRebalancedWanderingTraderOffers(java.util.function.Consumer<?> factory) { note(); }
  public static void refreshOffers() { }
}
