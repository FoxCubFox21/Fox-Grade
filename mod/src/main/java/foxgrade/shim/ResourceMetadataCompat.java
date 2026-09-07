package foxgrade.shim;

/** 1.21.x {@code ResourceMetadata.getSection(serializer)}: a serializer from a 1.21.x mod is not a 26.2 metadata type. */
public final class ResourceMetadataCompat {
  private ResourceMetadataCompat() {}
  public static java.util.Optional<?> getSection(net.minecraft.server.packs.resources.ResourceMetadata metadata, MetadataSectionSerializerShim<?> serializer) { return java.util.Optional.empty(); }
  /** A 26.2 MetadataSectionType seen through the 1.21 serializer interface (see MetadataSectionTypeSerializer). */
  public static <T> MetadataSectionSerializerShim<T> serializer(net.minecraft.server.packs.metadata.MetadataSectionType<T> type) {
    return new MetadataSectionTypeSerializer<>(type);
  }
}
