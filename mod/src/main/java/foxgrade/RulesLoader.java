// Load rules.json.gz from resources and build the class-rename tables Fox-Grade transforms use.
//
// The rules file is the same one the Node tools grew — a per-target-version map with a `renames`
// array of {fromFqcn, toFqcn, verified, kind, chainable}. Only verified moves/renames are used
// for auto-application; unverified entries are for the AI-assisted CLI pass and stay out of the
// runtime port.
//
// Two useful views of the same table:
//   · slashTable()  Map<slashOwner, slashOwner>  — for descriptor and refmap rewriting
//   · dotTable()    Map<dottedFqcn, dottedFqcn>  — for accesswidener owner rewriting
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class RulesLoader {
  private final Map<String, String> slash;  // net/minecraft/block/Block -> net/minecraft/world/level/block/Block
  private final Map<String, String> dot;    // net.minecraft.block.Block -> net.minecraft.world.level.block.Block

  private RulesLoader(Map<String, String> slash, Map<String, String> dot) { this.slash = slash; this.dot = dot; }

  public Map<String, String> slashTable() { return slash; }
  public Map<String, String> dotTable() { return dot; }
  public int size() { return slash.size(); }

  public static RulesLoader load(String targetMc) throws IOException { return load(targetMc, null); }

  public static RulesLoader load(String targetMc, java.nio.file.Path cacheDir) throws IOException {
    targetMc = Targets.tables(targetMc);          // a version may share another's tables
    String stamp = TableCache.stamp("/foxgrade/rules.json.gz", targetMc);
    try (java.io.DataInputStream in = TableCache.open(cacheDir, "rules-" + targetMc + ".bin", stamp)) {
      if (in != null) return new RulesLoader(TableCache.readMap(in), TableCache.readMap(in));
    } catch (IOException stale) { }
    RulesLoader r = parse(targetMc);
    try (java.io.DataOutputStream out = TableCache.create(cacheDir, "rules-" + targetMc + ".bin", stamp)) {
      if (out != null) { TableCache.writeMap(out, r.slash); TableCache.writeMap(out, r.dot); }
    } catch (IOException ignored) { }
    return r;
  }

  private static RulesLoader parse(String targetMc) throws IOException {
    try (InputStream raw = RulesLoader.class.getResourceAsStream("/foxgrade/rules.json.gz")) {
      if (raw == null) throw new IOException("rules.json.gz missing from bundled resources");
      try (GZIPInputStream gz = new GZIPInputStream(raw)) {
        var out = new ByteArrayOutputStream();
        gz.transferTo(out);
        JsonObject all = new Gson().fromJson(out.toString("UTF-8"), JsonObject.class);
        if (!all.has(targetMc) || !all.get(targetMc).isJsonObject()) {
          return new RulesLoader(Map.of(), Map.of());
        }
        JsonObject block = all.getAsJsonObject(targetMc);
        JsonArray renames = block.has("renames") ? block.getAsJsonArray("renames") : new JsonArray();
        Map<String, String> slash = new HashMap<>(), dot = new HashMap<>();
        for (var el : renames) {
          if (!el.isJsonObject()) continue;
          JsonObject r = el.getAsJsonObject();
          if (!r.has("verified") || !r.get("verified").getAsBoolean()) continue;
          String kind = r.has("kind") ? r.get("kind").getAsString() : "";
          if (!kind.equals("move") && !kind.equals("rename")) continue;
          String from = r.get("fromFqcn").getAsString();
          String to = r.get("toFqcn").getAsString();
          dot.put(from, to);
          slash.put(from.replace('.', '/'), to.replace('.', '/'));
        }
        return new RulesLoader(slash, dot);
      }
    }
  }
}
