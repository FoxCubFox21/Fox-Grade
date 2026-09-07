package foxgrade.shim;

import com.mojang.serialization.MapCodec;

/** Stands in for 1.21's {@code LootPoolEntryType} record. 26.2's registry holds the MapCodec itself; RegistryCompat unwraps
 *  this when the mod registers it, so the registry sees exactly what 26.2 expects. */
public final class LootPoolEntryTypeShim<T> {
  private final MapCodec<T> codec;
  public LootPoolEntryTypeShim(MapCodec<T> codec) { this.codec = codec; }
  public MapCodec<T> codec() { return codec; }
  @Override public String toString() { return "LootPoolEntryType[" + codec + "]"; }
}
