package foxgrade.shim;

import net.minecraft.resources.Identifier;

/** 1.21.x {@code ArmorMaterial$Layer(assetName, suffix, dyeable)}; 26.2 keys armor visuals by an equipment-asset id instead. */
public final class ArmorMaterialLayerShim {
  private final Identifier assetName; private final String suffix; private final boolean dyeable;
  public ArmorMaterialLayerShim(Identifier assetName) { this(assetName, "", false); }
  public ArmorMaterialLayerShim(Identifier assetName, boolean dyeable) { this(assetName, "", dyeable); }
  public ArmorMaterialLayerShim(Identifier assetName, String suffix, boolean dyeable) { this.assetName = assetName; this.suffix = suffix; this.dyeable = dyeable; }
  public Identifier assetName() { return assetName; }
  public String suffix() { return suffix; }
  public boolean dyeable() { return dyeable; }
  public Identifier texture(boolean innerTexture) { return Identifier.fromNamespaceAndPath(assetName.getNamespace(), "textures/models/armor/" + assetName.getPath() + "_layer_" + (innerTexture ? 2 : 1) + suffix + ".png"); }
}
