package foxgrade.shim;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.MerchantOffer;

/** 1.21.x {@code VillagerTrades$ItemListing}; 26.2 dropped the inner interface. */
public interface ItemListingShim {
  MerchantOffer getOffer(Entity trader, RandomSource random);
}
