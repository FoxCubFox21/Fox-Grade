package foxgrade.shim;

/** 1.21.x {@code BakedModel}: mods implement it for custom block/item models. 26.2 renders through a different model
 *  pipeline, so implementations load but are never asked to render. */
public interface BakedModelShim {
  default java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side, net.minecraft.util.RandomSource random) { return java.util.List.of(); }
  default boolean useAmbientOcclusion() { return true; }
  default boolean isGui3d() { return false; }
  default boolean usesBlockLight() { return false; }
  default boolean isCustomRenderer() { return false; }
  default net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
  default net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { return null; }
  default ItemOverridesShim getOverrides() { return ItemOverridesShim.EMPTY; }
}
