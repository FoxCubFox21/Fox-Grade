package foxgrade.shim;

/** QSL's {@code org.quiltmc.qsl.base.api.entrypoint.server.DedicatedServerModInitializer}. */
public interface QuiltServerModInitializerShim extends net.fabricmc.api.DedicatedServerModInitializer {
  String ENTRYPOINT_KEY = "server_init";
  void onInitializeServer(QuiltModContainerShim mod);
  @Override default void onInitializeServer() { onInitializeServer(QuiltSelfContainer.byClass(getClass()).orElse(null)); }
}
