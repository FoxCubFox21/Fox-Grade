package foxgrade.shim;

/** 1.21.x Fabric {@code FluidRenderHandler}; 26.2 renders fluids from data, so handlers are held but never consulted. */
public interface FluidRenderHandlerShim {
  net.minecraft.client.renderer.texture.TextureAtlasSprite[] getFluidSprites(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state);
  default int getFluidColor(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state) { return -1; }
  default void reloadTextures(net.minecraft.client.renderer.texture.TextureAtlas atlas) { }
}
