package foxgrade;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link LoaderHost} over NeoForge's FML.
 *
 *  <p>Reached entirely by reflection on purpose: Fox-Grade is compiled against Fabric, so NeoForge's classes are not on
 *  the build classpath, and one jar has to run on either loader. Every lookup is cached and every failure degrades to a
 *  sensible default rather than throwing, because a loader API that moved between NeoForge versions must never take the
 *  game down — the porting engine underneath does not depend on any of it. */
final class NeoForgeHost implements LoaderHost {

  private final Path gameDir;
  private final boolean server;
  private final String mcVersion;
  private volatile Object modList;           // net.neoforged.fml.ModList, resolved on first use

  private NeoForgeHost(Path gameDir, boolean server, String mcVersion) {
    this.gameDir = gameDir; this.server = server; this.mcVersion = mcVersion;
  }

  /** null unless NeoForge's FML is present and answering.
   *
   *  <p>FML's own API changed shape between the NeoForge line that targets 1.21 and the one that targets 26.2: what
   *  used to be statics on {@code FMLLoader} now hang off an instance from {@code FMLLoader.getCurrent()}. Every probe
   *  below therefore tries the current shape first and the older one second, so this host keeps working on both. */
  static NeoForgeHost detect() {
    try {
      Class.forName("net.neoforged.fml.loading.FMLLoader");
    } catch (Throwable notNeoForge) {
      return null;
    }
    Path dir = gameDirOf();
    if (dir == null) return null;
    return new NeoForgeHost(dir, serverSide(), mcVersionOf());
  }

  /** The FMLLoader instance on builds that have one; null on older builds and before startup finishes. */
  private static Object fml() {
    try {
      Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
      return loader.getMethod("getCurrentOrNull").invoke(null);
    } catch (Throwable olderOrAbsent) {
      return null;
    }
  }

  /** {@code method} called on the FMLLoader instance, or null if there is no instance or no such method. */
  private static Object onLoader(String method) {
    Object fml = fml();
    if (fml == null) return null;
    try { return fml.getClass().getMethod(method).invoke(fml); } catch (Throwable t) { return null; }
  }

  private static Path gameDirOf() {
    Object dir = onLoader("getGameDir");
    if (dir instanceof Path p) return p;
    try {                                                          // older FML: the FMLPaths enum
      Class<?> paths = Class.forName("net.neoforged.fml.loading.FMLPaths");
      Object gamedir = Enum.valueOf(paths.asSubclass(Enum.class), "GAMEDIR");
      return (Path) paths.getMethod("get").invoke(gamedir);
    } catch (Throwable t) {
      return null;
    }
  }

  private static boolean serverSide() {
    Object dist = onLoader("getDist");
    if (dist != null) return String.valueOf(dist).contains("SERVER");
    for (String[] probe : new String[][] {
        {"net.neoforged.fml.loading.FMLEnvironment", "dist"},
        {"net.neoforged.api.distmarker.Dist", null}}) {
      try {
        Object d = Class.forName(probe[0]).getField(probe[1]).get(null);
        if (d != null) return String.valueOf(d).contains("SERVER");
      } catch (Throwable ignored) { }
    }
    return false;
  }

  private static String mcVersionOf() {
    Object info = onLoader("getVersionInfo");
    if (info == null) {
      try {                                                        // older FML: a static of the same name
        Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
        info = loader.getMethod("versionInfo").invoke(null);
      } catch (Throwable t) { info = null; }
    }
    if (info != null) {
      try { return String.valueOf(info.getClass().getMethod("mcVersion").invoke(info)); } catch (Throwable ignored) { }
    }
    // Last resort, and a reliable one: FML is handed the version on the command line as --fml.mcVersion.
    String[] argv = System.getProperty("sun.java.command", "").split("\\s+");
    for (int i = 0; i + 1 < argv.length; i++) if (argv[i].equals("--fml.mcVersion")) return argv[i + 1];
    return "?";
  }

  /** NeoForge's mod list, resolved lazily.
   *
   *  <p>Lazily because of when this host is first asked for: Fox-Grade's locator runs during discovery, before any mod
   *  list exists, and {@link Loaders} caches the host it finds. Resolving eagerly would pin a null for the whole run
   *  and leave the in-game panel with nothing to show. */
  private Object modList() {
    Object list = modList;
    if (list != null) return list;
    for (String[] probe : new String[][] {
        {"net.neoforged.fml.ModList", "get"},                       // after loading: the real list
        {"net.neoforged.fml.loading.LoadingModList", "get"}}) {     // during loading: what discovery has so far
      try {
        Object found = Class.forName(probe[0]).getMethod(probe[1]).invoke(null);
        if (found != null) return modList = found;
      } catch (Throwable notYet) { }
    }
    return null;
  }

  @Override public String name() { return "NeoForge"; }

  @Override public Path gameDir() { return gameDir; }

  @Override public boolean isServer() { return server; }

  @Override public String gameVersion() { return mcVersion; }

  @Override public List<Mod> mods() {
    List<Mod> out = new ArrayList<>();
    Object list = modList();
    if (list == null) return out;
    try {
      @SuppressWarnings("unchecked")
      List<Object> infos = (List<Object>) list.getClass().getMethod("getMods").invoke(list);
      for (Object info : infos) out.add(new NeoMod(info));
    } catch (Throwable t) { /* leave the list empty rather than fail a launch */ }
    return out;
  }

  @Override public Optional<Mod> mod(String id) {
    for (Mod m : mods()) if (m.id().equals(id)) return Optional.of(m);
    return Optional.empty();
  }

  /** NeoForge's discovery pipeline accepts ported jars before the loader commits to a mod set, so nothing has to
   *  restart to apply a port — unlike Fabric and Quilt. */
  @Override public boolean needsRestartToApply() { return false; }

  /** One entry of NeoForge's mod list (net.neoforged.neoforgespi.language.IModInfo). */
  private record NeoMod(Object info) implements Mod {
    private Object call(String method) {
      try { return info.getClass().getMethod(method).invoke(info); } catch (Throwable t) { return null; }
    }
    @Override public String id() { return String.valueOf(call("getModId")); }
    @Override public String version() { return String.valueOf(call("getVersion")); }
    @Override public Optional<Path> findPath(String file) {
      try {
        Object owning = call("getOwningFile");                       // IModFileInfo
        Object modFile = owning.getClass().getMethod("getFile").invoke(owning);
        Path p = (Path) modFile.getClass().getMethod("findResource", String[].class)
            .invoke(modFile, (Object) new String[] {file});
        return p != null && java.nio.file.Files.exists(p) ? Optional.of(p) : Optional.empty();
      } catch (Throwable t) {
        return Optional.empty();
      }
    }
    @Override public List<Path> jarPaths() {
      try {
        Object owning = call("getOwningFile");
        Object modFile = owning.getClass().getMethod("getFile").invoke(owning);
        return List.of((Path) modFile.getClass().getMethod("getFilePath").invoke(modFile));
      } catch (Throwable t) {
        return List.of();
      }
    }
    @Override public Optional<String> iconPath(int size) {
      try {
        Object owning = call("getOwningFile");
        Object modFile = owning.getClass().getMethod("getFile").invoke(owning);
        Object logo = modFile.getClass().getMethod("getModFileInfo").invoke(modFile);
        @SuppressWarnings("unchecked")
        Optional<String> o = (Optional<String>) logo.getClass().getMethod("getLogoFile").invoke(logo);
        return o == null ? Optional.empty() : o;
      } catch (Throwable t) {
        return Optional.empty();
      }
    }

    /** NeoForge keeps arbitrary metadata in the mods.toml `modproperties` table. */
    @Override public JsonObject custom(String key) {
      try {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props = (java.util.Map<String, Object>) call("getModProperties");
        if (props == null) return null;
        Object block = props.get(key);
        if (block == null) return null;
        return com.google.gson.JsonParser.parseString(String.valueOf(block)).getAsJsonObject();
      } catch (Throwable t) {
        return null;
      }
    }
  }
}
