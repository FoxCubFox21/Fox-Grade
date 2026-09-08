package foxgrade.shim;

import net.fabricmc.loader.api.metadata.ModMetadata;

public final class QuiltModMetadataImpl implements QuiltModMetadataShim {
  private final ModMetadata fabric;
  public QuiltModMetadataImpl(ModMetadata fabric) { this.fabric = fabric; }
  @Override public String id() {
    String id = fabric.getId();   // a port is registered as <id>_fgport; the mod knows itself by the original id
    return id.endsWith("_fgport") ? id.substring(0, id.length() - "_fgport".length()) : id;
  }
  @Override public String group() { return ""; }
  @Override public QuiltVersionShim version() { return new QuiltVersionImpl(fabric.getVersion().getFriendlyString()); }
  @Override public String name() { return fabric.getName(); }
  @Override public String description() { return fabric.getDescription(); }
  @Override public java.util.Collection<?> licenses() { return fabric.getLicense(); }
  @Override public java.util.Collection<?> contributors() { return java.util.List.of(); }
  @Override public String getContactInfo(String key) { return fabric.getContact().get(key).orElse(null); }
  @Override public java.util.Map<String, String> contactInfo() { return fabric.getContact().asMap(); }
  @Override public java.util.Collection<?> depends() { return java.util.List.of(); }
  @Override public java.util.Collection<?> breaks() { return java.util.List.of(); }
  @Override public String icon(int size) { return fabric.getIconPath(size).orElse(null); }
  @Override public boolean containsValue(String key) { return fabric.containsCustomValue(key); }
}
