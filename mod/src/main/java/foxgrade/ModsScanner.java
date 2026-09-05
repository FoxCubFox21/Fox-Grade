// Walk the mods/ folder, open each jar just far enough to read its fabric.mod.json, and hand back
// a lightweight ModInfo per jar. Jars without a fabric.mod.json are recorded as unclassified — a
// non-Fabric jar is not an error, but Fox-Grade cannot decide about it either.
//
// The scanner NEVER modifies a jar. Reading a zip entry is cheap; nothing here alters state on
// disk. That keeps the "just observe first" pass safe even in the first launch after installing
// Fox-Grade, when the user may not yet trust it to touch their mods.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModsScanner {
  private static final Gson GSON = new Gson();

  public static List<ModInfo> scan(Path modsDir) throws IOException {
    List<ModInfo> out = new ArrayList<>();
    try (var stream = Files.list(modsDir)) {
      var jars = stream.filter((p) -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
      for (Path jar : jars) out.add(readOne(jar));
    }
    return out;
  }

  private static ModInfo readOne(Path jar) {
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      ZipEntry e = zf.getEntry("fabric.mod.json");
      if (e == null) return new ModInfo(jar, jar.getFileName().toString(), "?", null, false, null);
      String text;
      try (InputStream in = zf.getInputStream(e)) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      JsonObject obj = GSON.fromJson(text, JsonObject.class);
      String id = obj.has("id") ? obj.get("id").getAsString() : jar.getFileName().toString();
      String version = obj.has("version") ? obj.get("version").getAsString() : "?";
      String mcRange = null;
      if (obj.has("depends") && obj.get("depends").isJsonObject()) {
        JsonObject deps = obj.getAsJsonObject("depends");
        if (deps.has("minecraft")) {
          // minecraft may be a string or an array of ORed ranges
          if (deps.get("minecraft").isJsonPrimitive()) mcRange = deps.get("minecraft").getAsString();
          else if (deps.get("minecraft").isJsonArray()) {
            var arr = deps.getAsJsonArray("minecraft");
            var parts = new ArrayList<String>();
            arr.forEach((el) -> parts.add(el.getAsString()));
            mcRange = String.join(" || ", parts);
          }
        }
      }
      return new ModInfo(jar, id, version, mcRange, true, null);
    } catch (Exception err) {
      return new ModInfo(jar, jar.getFileName().toString(), "?", null, false, err.getMessage());
    }
  }

  public static final class ModInfo {
    public final Path jar;
    public final String id;
    public final String version;
    public final String mcRange;         // null if this mod declares no minecraft dep (that's fine)
    public final boolean hasFabricJson;  // false = jar has no fabric.mod.json at all
    public final String error;           // non-null only if the scan itself threw

    public ModInfo(Path jar, String id, String version, String mcRange, boolean hasFabricJson, String error) {
      this.jar = jar; this.id = id; this.version = version;
      this.mcRange = mcRange; this.hasFabricJson = hasFabricJson; this.error = error;
    }
    public String display() { return id + " " + version; }
  }
}
