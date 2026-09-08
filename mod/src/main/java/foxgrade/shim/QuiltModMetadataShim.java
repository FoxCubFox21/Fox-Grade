package foxgrade.shim;

/** Quilt Loader's {@code org.quiltmc.loader.api.ModMetadata}, read from the Fabric container's metadata. */
public interface QuiltModMetadataShim {
  String id();
  String group();
  QuiltVersionShim version();
  String name();
  String description();
  java.util.Collection<?> licenses();
  java.util.Collection<?> contributors();
  String getContactInfo(String key);
  java.util.Map<String, String> contactInfo();
  java.util.Collection<?> depends();
  java.util.Collection<?> breaks();
  default java.util.Collection<?> provides() { return java.util.List.of(); }
  String icon(int size);
  boolean containsValue(String key);
}
