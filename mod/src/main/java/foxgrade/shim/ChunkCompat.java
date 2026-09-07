package foxgrade.shim;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.stream.Stream;

/** 1.21 chunk-internal shapes that 26.2 reworked: paletted containers no longer take the id map, proto chunks take a
 *  container factory instead of the biome registry, structure generation wants the level key, and a few BlockState
 *  accessors were renamed. Distant Horizons reads chunks this way. */
public final class ChunkCompat {
  private ChunkCompat() { }

  public static Block block(BlockState state) { return state.getBlock(); }
  public static Stream<TagKey<Block>> getTags(BlockState state) { return state.typeHolder().tags(); }
  public static boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return state.propagatesSkylightDown(); }
  public static DyeColor color(BeaconBeamBlock block) { return block.getColor(); }

  /** 1.21 had no level key here; LOD generation only ever asked for the overworld's structure set. */
  public static void createStructures(ChunkGenerator gen, RegistryAccess registries, ChunkGeneratorStructureState state,
                                      StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
    gen.createStructures(registries, state, structures, chunk, templates, Level.OVERWORLD);
  }

  public static <T> Codec<PalettedContainer<T>> codecRW(IdMap<T> ids, Codec<T> codec, Strategy<T> strategy, T fallback) {
    return PalettedContainer.codecRW(codec, strategy, fallback);
  }

  /** 1.21's per-direction face shading, which 26.2 folds into the lighting pipeline. */
  public static float getShade(Object level, Direction direction, boolean shade) {
    if (!shade) return 1.0f;
    return switch (direction) {
      case DOWN -> 0.5f;
      case UP -> 1.0f;
      case NORTH, SOUTH -> 0.8f;
      default -> 0.6f;
    };
  }

  /** What PalettedContainerFactory.create(RegistryAccess) builds, from just the biome registry a 1.21 caller had. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static PalettedContainerFactory factory(Registry<Biome> biomes) {
    Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    BlockState air = Blocks.AIR.defaultBlockState();
    Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomes.asHolderIdMap());
    Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);
    return new PalettedContainerFactory(blockStrategy, air, (Codec) PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, air),
        biomeStrategy, plains, (Codec) PalettedContainer.codecRO(biomes.holderByNameCodec(), biomeStrategy, plains));
  }
}
