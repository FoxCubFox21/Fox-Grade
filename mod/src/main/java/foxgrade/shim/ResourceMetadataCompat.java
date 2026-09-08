package foxgrade.shim;

/** 1.21.x {@code ResourceMetadata.getSection(serializer)}: a serializer from a 1.21.x mod is not a 26.2 metadata type. */
public final class ResourceMetadataCompat {
  private ResourceMetadataCompat() {}
  public static java.util.Optional<?> getSection(net.minecraft.server.packs.resources.ResourceMetadata metadata, MetadataSectionSerializerShim<?> serializer) { return java.util.Optional.empty(); }
  /** A 26.2 MetadataSectionType seen through the 1.21 serializer interface (see MetadataSectionTypeSerializer). */
  public static <T> MetadataSectionSerializerShim<T> serializer(net.minecraft.server.packs.metadata.MetadataSectionType<T> type) {
    return new MetadataSectionTypeSerializer<>(type);
  }
  /** 1.21's {@code PackResources.getMetadataSection(MetadataSectionSerializer)}; 26.2 takes the codec-typed section
   *  instead. Unwrap the serializer when it is one of ours, otherwise match the section by name against the pack
   *  metadata types the game still has. Returning null means "this pack declares no such section", which is exactly
   *  what a caller expects when it cannot be found. */
  public static Object packSection(net.minecraft.server.packs.PackResources pack, MetadataSectionSerializerShim<?> serializer) {
    if (pack == null || serializer == null) return null;
    try {
      if (serializer instanceof MetadataSectionTypeSerializer<?> wrapped) return pack.getMetadataSection(wrapped.type());
      String want = serializer.getMetadataSectionName();
      for (net.minecraft.server.packs.metadata.MetadataSectionType<?> t : new net.minecraft.server.packs.metadata.MetadataSectionType<?>[] {
          net.minecraft.server.packs.metadata.pack.PackMetadataSection.FALLBACK_TYPE,
          net.minecraft.server.packs.metadata.pack.PackMetadataSection.CLIENT_TYPE,
          net.minecraft.server.packs.metadata.pack.PackMetadataSection.SERVER_TYPE }) {
        if (t != null && t.name().equals(want)) return pack.getMetadataSection(t);
      }
    } catch (Throwable notAvailable) { }
    return null;
  }
}
