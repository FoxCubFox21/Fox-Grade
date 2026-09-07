package foxgrade.shim;

import com.mojang.serialization.MapCodec;

/** Stands in for 1.21's {@code LootItemFunctionType} record; 26.2 registers the MapCodec itself (RegistryCompat unwraps this). */
public final class LootItemFunctionTypeShim<T> {
  private final MapCodec<T> codec;
  public LootItemFunctionTypeShim(MapCodec<T> codec) { this.codec = codec; }
  public MapCodec<T> codec() { return codec; }
}
