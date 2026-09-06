package foxgrade.shim;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** 1.21.x Fabric {@code FabricBrewingRecipeRegistryBuilder}: brewing recipes are data-driven on 26.2, so the BUILD
 *  event accepts listeners and never fires. */
public final class FabricBrewingRecipeRegistryBuilderShim {
  private FabricBrewingRecipeRegistryBuilderShim() {}
  public interface BuildCallback { void build(net.minecraft.world.item.alchemy.PotionBrewing.Builder builder); }
  public static final Event<BuildCallback> BUILD = EventFactory.createArrayBacked(BuildCallback.class, (listeners) -> (builder) -> {
    System.err.println("[Fox-Grade] brewing recipes are data-driven on 26.2; " + listeners.length + " 1.21.x brewing registrations are ignored");
  });
}
