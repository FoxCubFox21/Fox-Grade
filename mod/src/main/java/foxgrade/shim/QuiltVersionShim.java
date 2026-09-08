package foxgrade.shim;

/** Quilt Loader's {@code org.quiltmc.loader.api.Version}, over a Fabric version. */
public interface QuiltVersionShim extends Comparable<QuiltVersionShim> {
  static QuiltVersionShim of(String raw) { return new QuiltVersionImpl(raw); }
  String raw();
  default boolean isSemantic() {
    try { return net.fabricmc.loader.api.Version.parse(raw()) instanceof net.fabricmc.loader.api.SemanticVersion; } catch (Exception e) { return false; }
  }
  int compareTo(QuiltVersionShim other);
}
