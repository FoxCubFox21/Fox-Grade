package foxgrade.shim;

/** 1.21.x Fabric {@code BlockRenderLayerMap}: cutout/translucent layers per block. 26.2 reads the layer from the
 *  block model JSON ("render_type"), so registrations are accepted and ignored. */
public interface BlockRenderLayerMapShim {
  BlockRenderLayerMapShim INSTANCE = new Impl();
  void putBlock(net.minecraft.world.level.block.Block block, net.minecraft.client.renderer.rendertype.RenderType renderLayer);
  void putBlocks(net.minecraft.client.renderer.rendertype.RenderType renderLayer, net.minecraft.world.level.block.Block... blocks);
  void putFluid(net.minecraft.world.level.material.Fluid fluid, net.minecraft.client.renderer.rendertype.RenderType renderLayer);
  void putFluids(net.minecraft.client.renderer.rendertype.RenderType renderLayer, net.minecraft.world.level.material.Fluid... fluids);

  final class Impl implements BlockRenderLayerMapShim {
    private boolean said;
    private void note() { if (!said) { said = true; System.err.println("[Fox-Grade] block render layers are chosen by the block model JSON on 26.2; BlockRenderLayerMap registrations are ignored"); } }
    @Override public void putBlock(net.minecraft.world.level.block.Block block, net.minecraft.client.renderer.rendertype.RenderType renderLayer) { note(); }
    @Override public void putBlocks(net.minecraft.client.renderer.rendertype.RenderType renderLayer, net.minecraft.world.level.block.Block... blocks) { note(); }
    @Override public void putFluid(net.minecraft.world.level.material.Fluid fluid, net.minecraft.client.renderer.rendertype.RenderType renderLayer) { note(); }
    @Override public void putFluids(net.minecraft.client.renderer.rendertype.RenderType renderLayer, net.minecraft.world.level.material.Fluid... fluids) { note(); }
  }
}
