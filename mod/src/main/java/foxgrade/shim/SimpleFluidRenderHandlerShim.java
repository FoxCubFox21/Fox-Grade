package foxgrade.shim;

import net.minecraft.resources.Identifier;

/** 1.21.x Fabric {@code SimpleFluidRenderHandler(still, flowing[, overlay][, tint])}. */
public class SimpleFluidRenderHandlerShim implements FluidRenderHandlerShim {
  protected final Identifier stillTexture, flowingTexture, overlayTexture; protected final int tint;
  public SimpleFluidRenderHandlerShim(Identifier still, Identifier flowing) { this(still, flowing, null, -1); }
  public SimpleFluidRenderHandlerShim(Identifier still, Identifier flowing, int tint) { this(still, flowing, null, tint); }
  public SimpleFluidRenderHandlerShim(Identifier still, Identifier flowing, Identifier overlay) { this(still, flowing, overlay, -1); }
  public SimpleFluidRenderHandlerShim(Identifier still, Identifier flowing, Identifier overlay, int tint) { this.stillTexture = still; this.flowingTexture = flowing; this.overlayTexture = overlay; this.tint = tint; }
  @Override public net.minecraft.client.renderer.texture.TextureAtlasSprite[] getFluidSprites(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state) { return new net.minecraft.client.renderer.texture.TextureAtlasSprite[0]; }
  @Override public int getFluidColor(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState state) { return tint; }
}
