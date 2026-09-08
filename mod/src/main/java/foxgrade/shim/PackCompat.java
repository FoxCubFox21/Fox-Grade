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
  /** Fabric API moved the pack activation enum to {@code api.resource.v1.pack.PackActivationType} and changed
   *  {@code ModNioPackResources.create} to take the new one. The old enum still exists, so a 1.21 mod loads and then
   *  cannot find the method (towns-and-towers). Convert by constant name — the two enums carry the same constants. */
  public static net.fabricmc.fabric.impl.resource.pack.ModNioPackResources createModPack(
      String name, net.fabricmc.loader.api.ModContainer container, String subPath,
      net.minecraft.server.packs.PackType packType,
      net.fabricmc.fabric.api.resource.ResourcePackActivationType activation, boolean alwaysEnabled) {
    net.fabricmc.fabric.api.resource.v1.pack.PackActivationType converted;
    try {
      converted = net.fabricmc.fabric.api.resource.v1.pack.PackActivationType.valueOf(activation.name());
    } catch (RuntimeException unknownConstant) {
      converted = net.fabricmc.fabric.api.resource.v1.pack.PackActivationType.NORMAL;
    }
    return net.fabricmc.fabric.impl.resource.pack.ModNioPackResources.create(name, container, subPath, packType, converted, alwaysEnabled);
  }
}
