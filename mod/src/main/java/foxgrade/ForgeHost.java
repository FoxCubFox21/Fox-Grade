package foxgrade;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link LoaderHost} over Minecraft Forge's FML.
 *
 *  <p>Forge and NeoForge share an ancestor and have drifted, so this is deliberately its own class rather than a
 *  parameter on {@link NeoForgeHost}: the package roots differ throughout, and Forge kept the flat static API that
 *  NeoForge moved onto a loader instance. Trying to serve both from one set of probes would mean a class whose every
 *  method carries an if, and which is wrong for whichever loader is asked about second.
 *
 *  <p>Reflective for the same reason as the NeoForge host: Fox-Grade is compiled against Fabric, one jar has to run
 *  on every loader, and a loader API that moved must never be the thing that stops a game starting. */
final class ForgeHost implements LoaderHost {

  private final Path gameDir;
  private final boolean server;
  private final String mcVersion;
  private volatile Object modList;

  private ForgeHost(Path gameDir, boolean server, String mcVersion) {
    this.gameDir = gameDir; this.server = server; this.mcVersion = mcVersion;
  }

  /** null unless Forge's FML is present and answering. */
  static ForgeHost detect() {
    try {
      Class.forName("net.minecraftforge.fml.loading.FMLLoader");
    } catch (Throwable notForge) {
      return null;
    }
    Path dir = gameDirOf();
    return dir == null ? null : new ForgeHost(dir, serverSide(), mcVersionOf());
  }

  private static Object callStatic(String owner, String method) {
    try { return Class.forName(owner).getMethod(method).invoke(null); } catch (Throwable t) { return null; }
  }

  private static Path gameDirOf() {
    Object p = callStatic("net.minecraftforge.fml.loading.FMLLoader", "getGamePath");
    if (p instanceof Path path) return path;
    try {                                                        // the FMLPaths enum, if getGamePath ever goes
      Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
      Object gamedir = Enum.valueOf(paths.asSubclass(Enum.class), "GAMEDIR");
      return (Path) paths.getMethod("get").invoke(gamedir);
    } catch (Throwable t) {
      return null;
    }
  }

  private static boolean serverSide() {
    Object dist = callStatic("net.minecraftforge.fml.loading.FMLLoader", "getDist");
    if (dist == null) {
      try { dist = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment").getField("dist").get(null); }
      catch (Throwable t) { dist = null; }
    }
    return dist != null && String.valueOf(dist).contains("SERVER");
  }

  private static String mcVersionOf() {
    Object info = callStatic("net.minecraftforge.fml.loading.FMLLoader", "versionInfo");
    if (info != null) {
      try { return String.valueOf(info.getClass().getMethod("mcVersion").invoke(info)); } catch (Throwable ignored) { }
    }
    return "?";
  }

  @Override public String name() { return "Forge"; }

  @Override public Path gameDir() { return gameDir; }

  @Override public boolean isServer() { return server; }

  @Override public String gameVersion() { return mcVersion; }

  /** Forge, like NeoForge, has no mod list yet while a locator is running, so this resolves on first use. */
  private Object modList() {
    Object list = modList;
    if (list != null) return list;
    for (String[] probe : new String[][] {
        {"net.minecraftforge.fml.ModList", "get"},
        {"net.minecraftforge.fml.loading.LoadingModList", "get"}}) {
      Object found = callStatic(probe[0], probe[1]);
      if (found != null) return modList = found;
    }
    return null;
  }

  @Override public List<Mod> mods() {
    List<Mod> out = new ArrayList<>();
    Object list = modList();
    if (list == null) return out;
    try {
      @SuppressWarnings("unchecked")
      List<Object> infos = (List<Object>) list.getClass().getMethod("getMods").invoke(list);
      for (Object info : infos) out.add(new ForgeMod(info));
    } catch (Throwable t) { /* leave the list empty rather than fail a launch */ }
    return out;
  }

  @Override public Optional<Mod> mod(String id) {
    for (Mod m : mods()) if (m.id().equals(id)) return Optional.of(m);
    return Optional.empty();
  }

  /** Forge's locator runs while discovery is open, so a port applies without restarting, as on NeoForge. */
  @Override public boolean needsRestartToApply() { return false; }

  /** One entry of Forge's mod list (net.minecraftforge.forgespi.language.IModInfo). */
  private record ForgeMod(Object info) implements Mod {
    private Object call(String method) {
      try { return info.getClass().getMethod(method).invoke(info); } catch (Throwable t) { return null; }
    }
    private Object modFile() {
      try {
        Object owning = call("getOwningFile");
        return owning == null ? null : owning.getClass().getMethod("getFile").invoke(owning);
      } catch (Throwable t) { return null; }
    }
    @Override public String id() { return String.valueOf(call("getModId")); }
    @Override public String version() { return String.valueOf(call("getVersion")); }
    @Override public Optional<Path> findPath(String file) {
      try {
        Object f = modFile();
        if (f == null) return Optional.empty();
        Path p = (Path) f.getClass().getMethod("findResource", String[].class).invoke(f, (Object) new String[] {file});
        return p != null && java.nio.file.Files.exists(p) ? Optional.of(p) : Optional.empty();
      } catch (Throwable t) { return Optional.empty(); }
    }
    @Override public List<Path> jarPaths() {
      try {
        Object f = modFile();
        return f == null ? List.of() : List.of((Path) f.getClass().getMethod("getFilePath").invoke(f));
      } catch (Throwable t) { return List.of(); }
    }
    @Override public Optional<String> iconPath(int size) {
      try {
        Object f = modFile();
        if (f == null) return Optional.empty();
        Object fileInfo = f.getClass().getMethod("getModFileInfo").invoke(f);
        @SuppressWarnings("unchecked")
        Optional<String> logo = (Optional<String>) fileInfo.getClass().getMethod("getLogoFile").invoke(fileInfo);
        return logo == null ? Optional.empty() : logo;
      } catch (Throwable t) { return Optional.empty(); }
    }
    /** Forge keeps arbitrary metadata in mods.toml's `modproperties`; Fox-Grade's report is a file beside it. */
    @Override public JsonObject custom(String key) {
      if ("foxgrade".equals(key)) {
        Optional<Path> report = findPath(TransformPipeline.PORT_REPORT);
        if (report.isPresent()) {
          try {
            return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.get())).getAsJsonObject();
          } catch (Exception unreadable) { /* fall through */ }
        }
      }
      try {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props = (java.util.Map<String, Object>) call("getModProperties");
        Object block = props == null ? null : props.get(key);
        return block == null ? null : com.google.gson.JsonParser.parseString(String.valueOf(block)).getAsJsonObject();
      } catch (Throwable t) { return null; }
    }
  }
}
