package foxgrade.shim;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import com.mojang.blaze3d.vertex.PoseStack;

/** 1.21.x Fabric {@code BuiltinItemRendererRegistry}: lets a mod draw an item itself instead of from a model.
 *  Fabric API dropped it after 26.2 moved item rendering to special model types, so registrations are remembered and
 *  reported but never drive rendering. Keeping the type and the registration call working is what lets the mod load. */
public interface BuiltinItemRendererRegistryShim {

  BuiltinItemRendererRegistryShim INSTANCE = new Impl();

  void register(ItemLike item, DynamicItemRenderer renderer);

  DynamicItemRenderer get(ItemLike item);

  /** The renderer callback itself; signature unchanged from 1.21.x so a mod's lambda still fits. */
  @FunctionalInterface
  interface DynamicItemRenderer {
    void render(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSourceShim vertexConsumers,
                int light, int overlay);
  }

  final class Impl implements BuiltinItemRendererRegistryShim {
    private final java.util.Map<Object, DynamicItemRenderer> renderers = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean warned;

    @Override public void register(ItemLike item, DynamicItemRenderer renderer) {
      if (item == null || renderer == null) return;
      renderers.put(item, renderer);
      if (!warned) {
        warned = true;
        System.err.println("[Fox-Grade] built-in item renderers registered — inactive on 26.2 (item rendering is model-driven now)");
      }
    }

    @Override public DynamicItemRenderer get(ItemLike item) { return item == null ? null : renderers.get(item); }
  }
}
