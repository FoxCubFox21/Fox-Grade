package foxgrade.shim;

/** 1.21.x Fabric {@code FluidRenderHandler}; 26.2 renders fluids from data, so handlers are held but never consulted. */
public interface FluidRenderHandlerShim {
  net.minecraft.client.renderer.texture.TextureAtlasSprite[] getFluidSprites(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state);
  default int getFluidColor(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state) { return -1; }
  default void reloadTextures(net.minecraft.client.renderer.texture.TextureAtlas atlas) { }
  /** 1.21.x form (vertex consumer) and the 26.2 form (renderer + output) a delegating handler may call: fluids render through the game's own path. */
  default void renderFluid(net.minecraft.core.BlockPos pos, net.minecraft.client.renderer.block.BlockAndTintGetter level, com.mojang.blaze3d.vertex.VertexConsumer consumer, net.minecraft.world.level.block.state.BlockState blockState, net.minecraft.world.level.material.FluidState fluidState) { }
  default void renderFluid(net.minecraft.client.renderer.block.FluidRenderer renderer, net.minecraft.core.BlockPos pos, net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.client.renderer.block.FluidRenderer.Output output, net.minecraft.world.level.block.state.BlockState blockState, net.minecraft.world.level.material.FluidState fluidState) { }
}
