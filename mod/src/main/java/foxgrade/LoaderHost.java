package foxgrade;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Everything Fox-Grade needs from the mod loader it is running under, and nothing more.
 *
 *  <p>The porting engine itself is loader-agnostic: it rewrites bytecode against a Minecraft version, which is the same
 *  work whoever is loading the jars. Only six things are actually loader business — where the game directory is, what
 *  mods are loaded, their metadata, whether this is a server, the game version, and whether ports can be applied
 *  without restarting. Keeping those behind this interface is what lets one engine serve Fabric, Quilt and NeoForge.
 *
 *  <p>Implementations: {@link FabricHost} (Fabric and Quilt, which provides the same loader API) and
 *  {@link StandaloneHost} (no loader at all — the offline checker). */
public interface LoaderHost {

  /** For logs and reports: "Fabric", "Quilt", "NeoForge", "standalone". */
  String name();

  Path gameDir();

  /** True on a dedicated server, where there is no client and no self-restart. */
  boolean isServer();

  /** The running Minecraft version, e.g. "26.2"; "?" when it cannot be determined. */
  String gameVersion();

  List<Mod> mods();

  Optional<Mod> mod(String id);

  default boolean isModLoaded(String id) { return mod(id).isPresent(); }

  /** Whether a newly written jar only takes effect after the game restarts.
   *
   *  <p>True on Fabric and Quilt: the loader has already committed to a mod set by the time any mod code runs, so a
   *  port must be applied by relaunching. False on NeoForge, whose discovery pipeline lets a port be handed to the
   *  loader before it commits — there the mod comes up running on the same launch. */
  boolean needsRestartToApply();

  /** One loaded mod, in the terms Fox-Grade cares about. */
  interface Mod {
    String id();

    String version();

    /** A file inside the mod's jar, if present. */
    Optional<Path> findPath(String file);

    /** The mod's file(s) on disk — the jar itself, not its inner roots. Empty when the loader will not say. */
    List<Path> jarPaths();

    /** Path inside the jar to the mod's declared icon at the requested size, if any. */
    Optional<String> iconPath(int size);

    /** A custom metadata block by key ("foxgrade", "loom:injected_interfaces"), as plain JSON, or null when absent.
     *  Loaders model custom metadata with their own types; JSON is the common shape and what the engine already uses. */
    JsonObject custom(String key);
  }
}
