package foxgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link LoaderHost} over the Fabric Loader API. Quilt Loader provides that same API, so this serves both; only
 *  {@link #name()} tells them apart. */
final class FabricHost implements LoaderHost {
  private final boolean quilt;

  private FabricHost(boolean quilt) { this.quilt = quilt; }

  /** null when the Fabric Loader API is not on the classpath or has no running instance. */
  static FabricHost detect() {
    try {
      FabricLoader loader = FabricLoader.getInstance();
      if (loader == null) return null;
      return new FabricHost(loader.isModLoaded("quilt_loader"));
    } catch (Throwable notLoaded) {
      return null;
    }
  }

  @Override public String name() { return quilt ? "Quilt" : "Fabric"; }

  @Override public Path gameDir() { return FabricLoader.getInstance().getGameDir(); }

  @Override public boolean isServer() {
    try { return FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER; }
    catch (Throwable t) { return false; }
  }

  @Override public String gameVersion() {
    return FabricLoader.getInstance().getModContainer("minecraft")
        .map((m) -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
  }

  @Override public List<Mod> mods() {
    List<Mod> out = new ArrayList<>();
    try { for (ModContainer c : FabricLoader.getInstance().getAllMods()) out.add(new FabricMod(c)); }
    catch (Throwable standalone) { /* no loader running */ }
    return out;
  }

  @Override public Optional<Mod> mod(String id) {
    try { return FabricLoader.getInstance().getModContainer(id).map(FabricMod::new); }
    catch (Throwable standalone) { return Optional.empty(); }
  }

  /** Fabric and Quilt read the mod set before any mod code runs, so a port only lands on the next launch. */
  @Override public boolean needsRestartToApply() { return true; }

  private record FabricMod(ModContainer c) implements Mod {
    @Override public String id() { return c.getMetadata().getId(); }
    @Override public String version() { return c.getMetadata().getVersion().getFriendlyString(); }
    @Override public Optional<Path> findPath(String file) { return c.findPath(file); }
    @Override public List<Path> jarPaths() {
      try { return c.getOrigin().getPaths(); } catch (Throwable notAFile) { return List.of(); }
    }
    @Override public Optional<String> iconPath(int size) { return c.getMetadata().getIconPath(size); }
    @Override public JsonObject custom(String key) {
      CustomValue cv = c.getMetadata().getCustomValue(key);
      if (cv == null || cv.getType() != CustomValue.CvType.OBJECT) return null;
      return (JsonObject) toJson(cv);
    }
  }

  /** Fabric models custom metadata with its own CustomValue tree; the engine speaks JSON everywhere else. */
  private static com.google.gson.JsonElement toJson(CustomValue v) {
    switch (v.getType()) {
      case OBJECT: {
        JsonObject o = new JsonObject();
        for (var e : v.getAsObject()) o.add(e.getKey(), toJson(e.getValue()));
        return o;
      }
      case ARRAY: {
        JsonArray a = new JsonArray();
        for (CustomValue e : v.getAsArray()) a.add(toJson(e));
        return a;
      }
      case STRING: return new JsonPrimitive(v.getAsString());
      case NUMBER: return new JsonPrimitive(v.getAsNumber());
      case BOOLEAN: return new JsonPrimitive(v.getAsBoolean());
      default: return com.google.gson.JsonNull.INSTANCE;
    }
  }
}
