package foxgrade;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Picks the {@link LoaderHost} for whatever Fox-Grade is running under.
 *
 *  <p>Detection is by classpath probe rather than configuration, so the same jar works on every loader: ask each
 *  implementation whether its loader API is present and answering. The standalone host is the fallback, which is what
 *  the offline checker runs under. */
public final class Loaders {
  private Loaders() { }
  private static volatile LoaderHost current;

  public static LoaderHost current() {
    LoaderHost h = current;
    if (h != null) return h;
    synchronized (Loaders.class) {
      if (current != null) return current;
      LoaderHost found = FabricHost.detect();          // Fabric, and Quilt via the same API
      if (found == null) found = NeoForgeHost.detect();
      if (found == null) found = new StandaloneHost();
      return current = found;
    }
  }

  /** For tests and the offline checker: pin the host explicitly. */
  public static void set(LoaderHost host) { current = host; }

  /** No loader at all: the offline checker (`foxgrade-check.sh`) runs the real pipeline outside the game. */
  static final class StandaloneHost implements LoaderHost {
    @Override public String name() { return "standalone"; }
    @Override public Path gameDir() { return Path.of("."); }
    @Override public boolean isServer() { return false; }
    @Override public String gameVersion() { return "?"; }
    @Override public List<Mod> mods() { return List.of(); }
    @Override public Optional<Mod> mod(String id) { return Optional.empty(); }
    @Override public boolean needsRestartToApply() { return false; }
  }

  /** A mod the engine knows about without a loader behind it. */
  public record SimpleMod(String id, String version, Path jar, JsonObject customBlock) implements LoaderHost.Mod {
    @Override public Optional<Path> findPath(String file) { return Optional.empty(); }
    @Override public Optional<String> iconPath(int size) { return Optional.empty(); }
    @Override public List<Path> jarPaths() { return jar == null ? List.of() : List.of(jar); }
    @Override public JsonObject custom(String key) { return customBlock; }
  }
}
