package foxgrade.shim;

/** 1.21.x Fabric {@code FluidRenderHandlerRegistry}: kept for {@code get}, never drives rendering on 26.2. */
public interface FluidRenderHandlerRegistryShim {
  FluidRenderHandlerRegistryShim INSTANCE = new Impl();
  void register(net.minecraft.world.level.material.Fluid fluid, FluidRenderHandlerShim handler);
  void register(net.minecraft.world.level.material.Fluid still, net.minecraft.world.level.material.Fluid flowing, FluidRenderHandlerShim handler);
  FluidRenderHandlerShim get(net.minecraft.world.level.material.Fluid fluid);

  final class Impl implements FluidRenderHandlerRegistryShim {
    private final java.util.Map<Object, FluidRenderHandlerShim> handlers = new java.util.HashMap<>();
    @Override public void register(net.minecraft.world.level.material.Fluid fluid, FluidRenderHandlerShim handler) { handlers.put(fluid, handler); System.err.println("[Fox-Grade] fluid render handler registered — inactive on 26.2 (fluid rendering is data-driven now)"); }
    @Override public void register(net.minecraft.world.level.material.Fluid still, net.minecraft.world.level.material.Fluid flowing, FluidRenderHandlerShim handler) { register(still, handler); handlers.put(flowing, handler); }
    @Override public FluidRenderHandlerShim get(net.minecraft.world.level.material.Fluid fluid) { return handlers.get(fluid); }
  }
}
