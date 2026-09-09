package foxgrade.shim;

import java.nio.file.Path;

/** FML statics that became instance methods, for the ones with no static equivalent to redirect to.
 *
 *  <p>Most of {@code FMLLoader}'s old statics have a direct replacement that is still static — {@code isProduction}
 *  and {@code getDist} moved to {@code FMLEnvironment} — and those are plain redirects needing no code. {@code
 *  getGamePath} does not: the game directory now hangs off the loader instance, and reaching it means either the
 *  instance or the {@code FMLPaths} enum, neither of which is a static method call a redirect can name.
 *
 *  <p>Both routes are tried, newest first, because which one exists depends on the NeoForge line. Reflection
 *  throughout, since this class is compiled against Minecraft and Fabric and never against NeoForge. */
public final class FmlCompat {
  private FmlCompat() { }

  /** The module name of a mod file, which FML 11 stopped exposing.
   *
   *  <p>{@code IModFileInfo.moduleName()} is gone with no replacement on that interface, and a language provider
   *  naming the module it is loading has nowhere else to ask. The mod file's own id is the closest true answer and
   *  the one FML itself now uses to identify a file.
   *
   *  <p>This is what a hollow pass looks like from the other side: kotlin-for-forge "passed" before its bundled
   *  language provider was ported, because the provider never loaded and so never ran. Porting it made the mod
   *  actually work, and it hit this immediately. */
  public static String moduleName(Object modFileInfo) {
    if (modFileInfo == null) return "";
    try {
      Object file = modFileInfo.getClass().getMethod("getFile").invoke(modFileInfo);
      if (file != null) {
        Object id = file.getClass().getMethod("getId").invoke(file);
        if (id != null) return String.valueOf(id);
      }
    } catch (Throwable notThisShape) { }
    return "";
  }

  public static Path getGamePath() {
    try {                                                          // current: FMLLoader.getCurrent().getGameDir()
      Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
      Object current = loader.getMethod("getCurrentOrNull").invoke(null);
      if (current != null) {
        Object dir = current.getClass().getMethod("getGameDir").invoke(current);
        if (dir instanceof Path p) return p;
      }
    } catch (Throwable olderOrAbsent) { }
    try {                                                          // older: the FMLPaths enum
      Class<?> paths = Class.forName("net.neoforged.fml.loading.FMLPaths");
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object gamedir = Enum.valueOf((Class<Enum>) paths.asSubclass(Enum.class), "GAMEDIR");
      Object dir = paths.getMethod("get").invoke(gamedir);
      if (dir instanceof Path p) return p;
    } catch (Throwable notThere) { }
    // Last resort. A mod asking where the game lives can work with the working directory; returning null cannot.
    return Path.of(".").toAbsolutePath().normalize();
  }
}
