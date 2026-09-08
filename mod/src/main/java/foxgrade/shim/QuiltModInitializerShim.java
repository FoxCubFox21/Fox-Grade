package foxgrade.shim;

/** QSL's {@code org.quiltmc.qsl.base.api.entrypoint.ModInitializer}: Fabric calls onInitialize(), the mod implemented the
 *  Quilt form with its own container as the argument. */
public interface QuiltModInitializerShim extends net.fabricmc.api.ModInitializer {
  String ENTRYPOINT_KEY = "init";
  void onInitialize(QuiltModContainerShim mod);
  @Override default void onInitialize() { onInitialize(QuiltSelfContainer.byClass(getClass()).orElse(null)); }
}
