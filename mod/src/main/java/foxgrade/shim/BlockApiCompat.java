package foxgrade.shim;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** 1.21.x block statics over 26.2. */
public final class BlockApiCompat {
  private BlockApiCompat() {}
  public static void dropHoneycomb(Level level, BlockPos pos) { if (level instanceof ServerLevel sl) Block.popResource(sl, pos, new ItemStack(Items.HONEYCOMB, 3)); }
  public static int moveFlags(boolean isMoving) { return 0; }
  public static boolean is(net.minecraft.world.level.block.state.BlockState state, Block block) { return state.getBlock() == block; }
  public static boolean falseValue() { return false; }

  public static net.minecraft.world.level.block.state.BlockBehaviour.Properties blockProperties() { return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(RegistryCompat.pendingBlockKey()); }
  public static net.minecraft.world.level.block.state.BlockBehaviour.Properties ofFullCopy(net.minecraft.world.level.block.state.BlockBehaviour source) { return net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(source).setId(RegistryCompat.pendingBlockKey()); }
  public static net.minecraft.world.level.block.state.BlockBehaviour.Properties ofLegacyCopy(net.minecraft.world.level.block.state.BlockBehaviour source) { return net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofLegacyCopy(source).setId(RegistryCompat.pendingBlockKey()); }
  /** {@code StateDefinition.Builder.add(props...)}: 26.2 base classes declare some properties (waterlogged) that 1.21.x
   *  subclasses added themselves; adding twice is fatal, so already-present properties are skipped. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.world.level.block.state.StateDefinition.Builder add(net.minecraft.world.level.block.state.StateDefinition.Builder builder, net.minecraft.world.level.block.state.properties.Property... props) {
    java.util.Map<String, ?> present = java.util.Map.of();
    try {
      java.lang.reflect.Field f = builder.getClass().getDeclaredField("properties"); f.setAccessible(true);
      Object v = f.get(builder); if (v instanceof java.util.Map<?, ?> m) present = (java.util.Map<String, ?>) m;
    } catch (ReflectiveOperationException ignore) { }
    java.util.List<net.minecraft.world.level.block.state.properties.Property> fresh = new java.util.ArrayList<>();
    for (net.minecraft.world.level.block.state.properties.Property prop : props) if (!present.containsKey(prop.getName())) fresh.add(prop);
    return fresh.isEmpty() ? builder : builder.add(fresh.toArray(new net.minecraft.world.level.block.state.properties.Property[0]));
  }
}
