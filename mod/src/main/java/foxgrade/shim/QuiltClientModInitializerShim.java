package foxgrade.shim;

/** QSL's {@code org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer}. */
public interface QuiltClientModInitializerShim extends net.fabricmc.api.ClientModInitializer {
  String ENTRYPOINT_KEY = "client_init";
  void onInitializeClient(QuiltModContainerShim mod);
  @Override default void onInitializeClient() { onInitializeClient(QuiltLoaderShim.containerOf(getClass())); }
}
