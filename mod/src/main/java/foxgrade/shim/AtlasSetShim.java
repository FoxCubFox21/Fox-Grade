package foxgrade.shim;

/** Type stand-in for 1.21's {@code AtlasSet}; 26.2 manages atlases through AtlasManager. */
public class AtlasSetShim {
  public net.minecraft.client.renderer.texture.TextureAtlas getAtlas(net.minecraft.resources.Identifier id) { return net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(id); }
  public void close() { }
}
