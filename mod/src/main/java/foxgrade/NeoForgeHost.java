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

  private final Object modList;              // net.neoforged.fml.ModList
  private final Path gameDir;
  private final boolean server;
  private final String mcVersion;

  private NeoForgeHost(Object modList, Path gameDir, boolean server, String mcVersion) {
    this.modList = modList; this.gameDir = gameDir; this.server = server; this.mcVersion = mcVersion;
  }

  /** null unless NeoForge's FML is present and initialised. */
  static NeoForgeHost detect() {
    try {
      Class.forName("net.neoforged.fml.loading.FMLLoader");
    } catch (Throwable notNeoForge) {
      return null;
    }
    Path dir = gameDirOf();
    if (dir == null) return null;
    Object list = null;
    try {
      Class<?> ml = Class.forName("net.neoforged.fml.ModList");
      list = ml.getMethod("get").invoke(null);
    } catch (Throwable tooEarly) {
      // Discovery-time: the mod list does not exist yet. That is a valid state for the porter, which runs before it.
    }
    return new NeoForgeHost(list, dir, serverSide(), mcVersionOf());
  }

  private static Path gameDirOf() {
    try {
      Class<?> paths = Class.forName("net.neoforged.fml.loading.FMLPaths");
      Object gamedir = Enum.valueOf(paths.asSubclass(Enum.class), "GAMEDIR");
      return (Path) paths.getMethod("get").invoke(gamedir);
    } catch (Throwable t) {
      return null;
    }
  }

  private static boolean serverSide() {
    for (String[] probe : new String[][] {
        {"net.neoforged.fml.loading.FMLEnvironment", "dist"},
        {"net.neoforged.api.distmarker.Dist", null}}) {
      try {
        Object dist = Class.forName(probe[0]).getField(probe[1]).get(null);
        if (dist != null) return String.valueOf(dist).contains("SERVER");
      } catch (Throwable ignored) { }
    }
    return false;
  }

  private static String mcVersionOf() {
    try {
      Class<?> fml = Class.forName("net.neoforged.fml.loading.FMLLoader");
      Object info = fml.getMethod("versionInfo").invoke(null);
      return String.valueOf(info.getClass().getMethod("mcVersion").invoke(info));
    } catch (Throwable t) {
      return "?";
    }
  }

  @Override public String name() { return "NeoForge"; }

  @Override public Path gameDir() { return gameDir; }

  @Override public boolean isServer() { return server; }

  @Override public String gameVersion() { return mcVersion; }

  @Override public List<Mod> mods() {
    List<Mod> out = new ArrayList<>();
    if (modList == null) return out;
    try {
      @SuppressWarnings("unchecked")
      List<Object> infos = (List<Object>) modList.getClass().getMethod("getMods").invoke(modList);
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
