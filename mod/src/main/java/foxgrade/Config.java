// User-facing configuration file. Lives at <gamedir>/fox-grade.config.json.
//
// v0.3.0 shape:
//   {
//     "port": [ "mod-id-1", "mod-id-2" ],   // opt-in: only these mods get transformed
//     "portAll": false                      // set true to transform every candidate automatically
//   }
//
// The default is portAll=false, port=[]. That means Fox-Grade OBSERVES a fresh install (writes
// fox-grade-report.txt, does nothing else) until the user explicitly opts in — a mod-porter that
// silently rewrites every mod on first launch is a mod-porter that will one day silently break
// somebody's world.
//
// If the config file doesn't exist, a template one is written to the game directory so the user
// can see the shape without hunting the docs.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class Config {
  public final boolean portAll;
  public final Set<String> portIds;

  private Config(boolean portAll, Set<String> portIds) { this.portAll = portAll; this.portIds = portIds; }

  public boolean shouldPort(String modId) { return portAll || portIds.contains(modId); }

  public static Config load(Path gameDir) throws IOException {
    Path f = gameDir.resolve("fox-grade.config.json");
    if (!Files.exists(f)) { writeTemplate(f); return new Config(false, Set.of()); }
    try {
      JsonObject o = new Gson().fromJson(Files.readString(f), JsonObject.class);
      boolean portAll = o.has("portAll") && o.get("portAll").getAsBoolean();
      Set<String> ids = new HashSet<>();
      if (o.has("port") && o.get("port").isJsonArray()) o.getAsJsonArray("port").forEach((el) -> ids.add(el.getAsString()));
      return new Config(portAll, ids);
    } catch (Exception e) {
      throw new IOException("could not parse " + f + ": " + e.getMessage(), e);
    }
  }

  private static void writeTemplate(Path f) throws IOException {
    JsonObject o = new JsonObject();
    var arr = new com.google.gson.JsonArray();
    o.add("port", arr);
    o.addProperty("portAll", false);
    Files.writeString(f, "// Fox-Grade config. Add mod IDs to `port` to have Fox-Grade transform them\n"
        + "// (backup original + widen fabric.mod.json + remap bytecode + fix AW/refmap + apply blocklist).\n"
        + "// Set `portAll` true to transform every candidate mod automatically — safer to opt in per mod.\n"
        + new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n");
  }
}
