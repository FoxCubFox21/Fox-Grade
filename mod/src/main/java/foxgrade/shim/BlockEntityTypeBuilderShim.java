package foxgrade.shim;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** 1.21.x {@code BlockEntityType$Builder}; 26.2 constructs the type directly. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class BlockEntityTypeBuilderShim<T extends BlockEntity> {
  private final BlockEntityType.BlockEntitySupplier<? extends T> factory;
  private final java.util.Set<Block> blocks;
  private BlockEntityTypeBuilderShim(BlockEntityType.BlockEntitySupplier<? extends T> factory, java.util.Set<Block> blocks) { this.factory = factory; this.blocks = blocks; }
  public static <T extends BlockEntity> BlockEntityTypeBuilderShim<T> of(BlockEntityType.BlockEntitySupplier<? extends T> factory, Block... blocks) {
    return new BlockEntityTypeBuilderShim<>(factory, java.util.Set.of(blocks));
  }
  /** {@code build(Type<?>)} in 1.21.x; the DataFixer type was never used by mods. */
  public BlockEntityType<T> build(Object dataFixerType) { return new BlockEntityType(factory, blocks); }
  /** The exact 1.21.x descriptor: {@code build(Type<?>)}. */
  public BlockEntityType<T> build(com.mojang.datafixers.types.Type<?> dataFixerType) { return build((Object) null); }
  public BlockEntityType<T> build() { return build(null); }
}
