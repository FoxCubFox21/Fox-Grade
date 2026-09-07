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
  /** 1.21.x {@code BlockEntityType.BED}: beds stopped being block entities in 26.2; a null lets a class initializer finish. */
  private static net.minecraft.world.level.block.entity.BlockEntityType<?> BED;
  /** 1.21.x {@code BlockEntityType.BED}: beds stopped being block entities in 26.2. A detached, never-registered type
   *  keeps maps and class initialisers that key on it alive (null would not). */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static synchronized net.minecraft.world.level.block.entity.BlockEntityType<?> bedBlockEntityType() {
    if (BED != null) return BED;
    var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE;
    net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath("foxgrade", "bed");
    if (reg.containsKey(id)) return BED = reg.getValue(id);
    try {
      java.lang.reflect.Field frozenF = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen"); frozenF.setAccessible(true);
      if (!frozenF.getBoolean(reg)) {   // registry still open: a real (inert) entry — nothing makes beds use it
        return BED = net.minecraft.core.Registry.register(reg, id, new net.minecraft.world.level.block.entity.BlockEntityType((pos, state) -> null, java.util.Set.of()));
      }
    } catch (Throwable ignored) { }
    try {   // frozen: the constructor would create an intrusive holder the registry can never bind, so build it holder-less
      java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
      sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
      var t = (net.minecraft.world.level.block.entity.BlockEntityType<?>) u.allocateInstance(net.minecraft.world.level.block.entity.BlockEntityType.class);
      java.lang.reflect.Field f = net.minecraft.world.level.block.entity.BlockEntityType.class.getDeclaredField("factory"); f.setAccessible(true);
      f.set(t, (net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<?>) (pos, state) -> null);
      f = net.minecraft.world.level.block.entity.BlockEntityType.class.getDeclaredField("validBlocks"); f.setAccessible(true); f.set(t, java.util.Set.of());
      return BED = t;
    } catch (Throwable e) { throw new IllegalStateException("bed block entity stand-in", e); }
  }
  /** 1.21's {@code BlockState.isSolidRender(BlockGetter, BlockPos)} lost its arguments in 26.2. */
  public static boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos) { return state.isSolidRender(); }
  /** 26.2 constructor arguments 1.21 blocks never had: leaf particle chance, chest open/close sounds. */
  public static float leafParticleChance() { return 0.01f; }
  public static net.minecraft.sounds.SoundEvent chestOpenSound() { return net.minecraft.sounds.SoundEvents.CHEST_OPEN; }
  public static net.minecraft.sounds.SoundEvent chestCloseSound() { return net.minecraft.sounds.SoundEvents.CHEST_CLOSE; }
  /** Fabric's block-appearance hook ({@code FabricBlockState.getAppearance}) changed shape in 26.2; the appearance of a
   *  state, absent a facade mod, is the state. */
  public static net.minecraft.world.level.block.state.BlockState getAppearance(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side, net.minecraft.world.level.block.state.BlockState source, net.minecraft.core.BlockPos sourcePos) { return state; }
  /** {@code new LeavesBlock(props)}: LeavesBlock is abstract in 26.2; the tinted-particle variant is what oak/birch use. */
  public static net.minecraft.world.level.block.LeavesBlock leaves(net.minecraft.world.level.block.state.BlockBehaviour.Properties props) { return new net.minecraft.world.level.block.TintedParticleLeavesBlock(0.01f, props); }
  /** 1.21's {@code Properties.dropsLike(block)}: 26.2 overrides the loot table by key. */
  public static net.minecraft.world.level.block.state.BlockBehaviour.Properties dropsLike(net.minecraft.world.level.block.state.BlockBehaviour.Properties props, net.minecraft.world.level.block.Block block) { return props.overrideLootTable(block.getLootTable()); }
}
