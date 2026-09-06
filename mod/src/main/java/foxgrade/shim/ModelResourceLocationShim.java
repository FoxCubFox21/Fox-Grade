package foxgrade.shim;

import net.minecraft.resources.Identifier;

/** 1.21.x {@code ModelResourceLocation(id, variant)}; 26.2 keys models by plain identifiers. Constructed freely, resolved by nobody. */
public final class ModelResourceLocationShim {
  public static final String INVENTORY_VARIANT = "inventory"; public static final String STANDALONE_VARIANT = "fabric_resource";
  private final Identifier id; private final String variant;
  public ModelResourceLocationShim(Identifier id, String variant) { this.id = id; this.variant = variant; }
  public static ModelResourceLocationShim vanilla(String path, String variant) { return new ModelResourceLocationShim(Identifier.withDefaultNamespace(path), variant); }
  public static ModelResourceLocationShim inventory(Identifier id) { return new ModelResourceLocationShim(id, INVENTORY_VARIANT); }
  public static ModelResourceLocationShim standalone(Identifier id) { return new ModelResourceLocationShim(id, STANDALONE_VARIANT); }
  public Identifier id() { return id; }
  public String variant() { return variant; }
  public String getVariant() { return variant; }
  public String getVariantOrThrow() { return variant; }
  @Override public boolean equals(Object o) { return o instanceof ModelResourceLocationShim m && m.id.equals(id) && m.variant.equals(variant); }
  @Override public int hashCode() { return id.hashCode() * 31 + variant.hashCode(); }
  @Override public String toString() { return id + "#" + variant; }
}
