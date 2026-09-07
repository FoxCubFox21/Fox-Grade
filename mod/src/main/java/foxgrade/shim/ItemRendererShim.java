package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 1.21.x {@code ItemRenderer} (removed): renderStatic submits through the frame's collector. */
public final class ItemRendererShim {
  private static final ItemRendererShim INSTANCE = new ItemRendererShim();
  private static boolean warned;
  public static ItemRendererShim get() { return INSTANCE; }
  /** BlockEntityRendererProvider.Context.getItemRenderer() (26.2 hands renderers an ItemModelResolver instead). */
  public static ItemRendererShim get(Object context) { return INSTANCE; }

  public void renderStatic(ItemStack stack, ItemDisplayContext ctx, int light, int overlay, PoseStack ps, MultiBufferSourceShim buffers, Level level, int seed) {
    submit(stack, ctx, light, overlay, ps, buffers, level, seed);
  }
  public void renderStatic(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack ps, MultiBufferSourceShim buffers, Level level, int light, int overlay, int seed) {
    submit(stack, ctx, light, overlay, ps, buffers, level, seed);
  }
  private static void submit(ItemStack stack, ItemDisplayContext ctx, int light, int overlay, PoseStack ps, MultiBufferSourceShim buffers, Level level, int seed) {
    if (stack == null || stack.isEmpty()) return;
    if (!(buffers instanceof RecordingBufferSource rec) || rec.collector() == null) {
      if (!warned) { warned = true; System.err.println("[Fox-Grade] item render outside a frame was dropped"); }
      return;
    }
    ItemStackRenderState state = new ItemStackRenderState();
    Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, stack, ctx, level, null, seed);
    state.submit(ps, rec.collector(), light, overlay, 0);
  }
}
