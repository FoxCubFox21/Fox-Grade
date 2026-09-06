package foxgrade.shim;

/** 1.21's {@code WorldVersion.getPackVersion(PackType)} returned an int; 26.2 returns a major/minor PackFormat. */
public final class VersionCompat {
  private VersionCompat() {}
  public static int packVersion(net.minecraft.WorldVersion version, net.minecraft.server.packs.PackType type) { return version.packVersion(type).major(); }
}
