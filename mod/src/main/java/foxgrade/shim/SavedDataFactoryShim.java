package foxgrade.shim;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** 1.21.x {@code SavedData.Factory(constructor, deserializer[, dataFixType])}; 26.2 describes saved data with a codec-typed
 *  {@code SavedDataType}. The factory is kept as data and turned into a type on first use. */
public final class SavedDataFactoryShim<T extends SavedData> {
  public final Supplier<T> constructor; public final BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer; public final DataFixTypes type;
  public SavedDataFactoryShim(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer, DataFixTypes type) { this.constructor = constructor; this.deserializer = deserializer; this.type = type; }
  public SavedDataFactoryShim(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer) { this(constructor, deserializer, null); }
  public Supplier<T> constructor() { return constructor; }
  public BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer() { return deserializer; }
  public DataFixTypes type() { return type; }
}
