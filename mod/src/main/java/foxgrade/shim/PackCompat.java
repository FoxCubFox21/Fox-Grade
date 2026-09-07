package foxgrade.shim;

/** 1.21's {@code new PackMetadataSection(description, format[, supportedFormats])}: 26.2 takes the supported range. */
public final class PackCompat {
  private PackCompat() {}
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.util.InclusiveRange range(int format, java.util.Optional supported) {
    if (supported != null && supported.isPresent() && supported.get() instanceof net.minecraft.util.InclusiveRange r) return r;
    return new net.minecraft.util.InclusiveRange(format, format);
  }
  public static net.minecraft.util.InclusiveRange<Integer> range(int format) { return new net.minecraft.util.InclusiveRange<>(format, format); }
  /** 1.21's single PackMetadataSection.TYPE; 26.2 splits it per pack kind, and FALLBACK_TYPE reads either. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.server.packs.metadata.MetadataSectionType<net.minecraft.server.packs.metadata.pack.PackMetadataSection> type() {
    return (net.minecraft.server.packs.metadata.MetadataSectionType) net.minecraft.server.packs.metadata.pack.PackMetadataSection.FALLBACK_TYPE;
  }
}
