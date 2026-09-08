package foxgrade.shim;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Quilt Loader's {@code org.quiltmc.loader.api.QuiltLoader} statics, answered by Fabric Loader. */
public final class QuiltLoaderShim {
  private QuiltLoaderShim() { }
  private static final Map<String, String> ENTRYPOINT_KEYS = Map.of("init", "main", "client_init", "client", "server_init", "server", "pre_launch", "preLaunch");
  private static FabricLoader fl() { return FabricLoader.getInstance(); }

  /** The container whose jar holds the class: how a Quilt entrypoint learns which mod it is. */
  public static QuiltModContainerShim containerOf(Class<?> cls) {
    String res = cls.getName().replace('.', '/') + ".class";
    for (var m : fl().getAllMods()) if (m.findPath(res).isPresent()) return new QuiltModContainerImpl(m);
    return fl().getModContainer("foxgrade").map(QuiltModContainerImpl::new).orElse(null);
  }

  public static <T> List<T> getEntrypoints(String key, Class<T> type) { return fl().getEntrypoints(ENTRYPOINT_KEYS.getOrDefault(key, key), type); }
  public static Optional<QuiltModContainerShim> getModContainer(String id) { return fl().getModContainer(id).map(QuiltModContainerImpl::new); }
  public static Optional<QuiltModContainerShim> getModContainer(Class<?> cls) { return Optional.ofNullable(containerOf(cls)); }
  public static Collection<QuiltModContainerShim> getAllMods() { return fl().getAllMods().stream().<QuiltModContainerShim>map(QuiltModContainerImpl::new).toList(); }
  public static boolean isModLoaded(String id) { return fl().isModLoaded(id); }
  public static boolean isDevelopmentEnvironment() { return fl().isDevelopmentEnvironment(); }
  public static Object getGameInstance() { return fl().getGameInstance(); }
  public static String getNormalizedGameVersion() { return gameVersion(); }
  public static String getRawGameVersion() { return gameVersion(); }
  private static String gameVersion() { return fl().getModContainer("minecraft").map((m) -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown"); }
  public static Path getGameDir() { return fl().getGameDir(); }
  public static Path getCacheDir() { return fl().getGameDir().resolve(".cache"); }
  public static Path getConfigDir() { return fl().getConfigDir(); }
  public static Path getGlobalCacheDir() { return getCacheDir(); }
  public static Path getGlobalConfigDir() { return getConfigDir(); }
  public static boolean globalDirsEnabled() { return false; }
  public static String[] getLaunchArguments(boolean sanitize) { return fl().getLaunchArguments(sanitize); }
  public static net.fabricmc.loader.api.ObjectShare getObjectShare() { return fl().getObjectShare(); }
  public static String createModTable() { return ""; }
}
