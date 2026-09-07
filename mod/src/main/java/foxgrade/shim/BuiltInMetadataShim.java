package foxgrade.shim;

import net.minecraft.server.packs.metadata.MetadataSectionType;

import java.util.Map;

/** 1.21's {@code BuiltInMetadata} (gone in 26.2): the pack.mcmeta sections a code-defined resource pack declares.
 *  Sections are keyed by name, whichever of the 1.21 serializer interface or the 26.2 section type the caller holds,
 *  so a pack's own getMetadataSection(serializer) override hands the game the section it built. */
public final class BuiltInMetadataShim {
  public static final BuiltInMetadataShim EMPTY = new BuiltInMetadataShim(Map.of());
  private final Map<String, Object> values;

  private BuiltInMetadataShim(Map<String, Object> values) { this.values = values; }

  public static BuiltInMetadataShim of() { return EMPTY; }
  public static BuiltInMetadataShim of(Object type, Object value) { return one(type, value); }
  public static BuiltInMetadataShim of(Object t1, Object v1, Object t2, Object v2) { return two(t1, v1, t2, v2); }
  // The 1.21 call site's static type is the MetadataSectionSerializer supertype (a shim interface in the port)...
  public static BuiltInMetadataShim of(MetadataSectionSerializerShim<?> type, Object value) { return one(type, value); }
  public static BuiltInMetadataShim of(MetadataSectionSerializerShim<?> t1, Object v1, MetadataSectionSerializerShim<?> t2, Object v2) { return two(t1, v1, t2, v2); }
  // ...or, after PackMetadataSection.TYPE was redirected, the 26.2 section type.
  public static BuiltInMetadataShim of(MetadataSectionType<?> type, Object value) { return one(type, value); }
  public static BuiltInMetadataShim of(MetadataSectionType<?> t1, Object v1, MetadataSectionType<?> t2, Object v2) { return two(t1, v1, t2, v2); }

  @SuppressWarnings("unchecked") public <T> T get(Object type) { return (T) values.get(key(type)); }
  @SuppressWarnings("unchecked") public <T> T get(MetadataSectionSerializerShim<T> type) { return (T) values.get(key(type)); }
  @SuppressWarnings("unchecked") public <T> T get(MetadataSectionType<T> type) { return (T) values.get(key(type)); }

  private static BuiltInMetadataShim one(Object type, Object value) {
    return value == null ? EMPTY : new BuiltInMetadataShim(Map.of(key(type), value));
  }
  private static BuiltInMetadataShim two(Object t1, Object v1, Object t2, Object v2) {
    Map<String, Object> m = new java.util.HashMap<>();
    if (v1 != null) m.put(key(t1), v1);
    if (v2 != null) m.put(key(t2), v2);
    return new BuiltInMetadataShim(Map.copyOf(m));
  }
  static String key(Object type) {
    if (type instanceof MetadataSectionType<?> t) return t.name();
    if (type instanceof MetadataSectionSerializerShim<?> s) return s.getMetadataSectionName();
    return String.valueOf(type);
  }
}
