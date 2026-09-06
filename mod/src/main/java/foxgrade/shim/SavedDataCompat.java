package foxgrade.shim;

import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/** 1.21.x {@code DimensionDataStorage.computeIfAbsent(factory, name)} over 26.2's typed storage. The mod's own
 *  {@code save(CompoundTag, Provider)} override (no longer an override in 26.2) is reached reflectively. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class SavedDataCompat {
  private SavedDataCompat() {}
  private static final Map<String, SavedDataType<?>> TYPES = new ConcurrentHashMap<>();
  private static DataFixTypes defaultFixType() {
    for (String n : new String[]{"SAVED_DATA_MAP_DATA", "SAVED_DATA_COMMAND_STORAGE", "LEVEL"}) { try { return DataFixTypes.valueOf(n); } catch (IllegalArgumentException ignore) { } }
    return DataFixTypes.values()[0];
  }
  private static CompoundTag saveOf(SavedData data) {
    try {
      java.lang.reflect.Method m = data.getClass().getMethod("save", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
      Object r = m.invoke(data, new CompoundTag(), null);
      return r instanceof CompoundTag t ? t : new CompoundTag();
    } catch (ReflectiveOperationException e) { return new CompoundTag(); }
  }
  public static SavedDataType<?> typeOf(SavedDataFactoryShim factory, String name) {
    return TYPES.computeIfAbsent(name, (n) -> {
      Identifier id = Identifier.tryParse(n.contains(":") ? n : "foxgrade:" + n.toLowerCase().replaceAll("[^a-z0-9/._-]", "_"));
      Codec<SavedData> codec = CompoundTag.CODEC.xmap((tag) -> (SavedData) factory.deserializer.apply(tag, null), SavedDataCompat::saveOf);
      return new SavedDataType(id, factory.constructor, codec, factory.type != null ? factory.type : defaultFixType());
    });
  }
  public static SavedData computeIfAbsent(SavedDataStorage storage, SavedDataFactoryShim factory, String name) { return storage.computeIfAbsent((SavedDataType) typeOf(factory, name)); }
  public static SavedData get(SavedDataStorage storage, SavedDataFactoryShim factory, String name) { return storage.get((SavedDataType) typeOf(factory, name)); }
  public static void set(SavedDataStorage storage, String name, SavedData data) {
    SavedDataType<?> t = TYPES.get(name);
    if (t != null) storage.set((SavedDataType) t, data); else System.err.println("[Fox-Grade] saved data '" + name + "' set before any factory described it — ignored");
  }
}
