package foxgrade;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vanilla registry ids the target version renamed (chain → iron_chain). World data goes through the game's own data
 *  fixers, but datapack and resource JSON carries no data version, so a 1.21 pack naming the old id fails to load.
 *  The table is mined from the game's DataFixers by gen-data-id-renames.py. */
final class DataIdRenames {
  private DataIdRenames() { }
  private static volatile Map<String, String> table;

  static Map<String, String> table() {
    Map<String, String> t = table;
    if (t != null) return t;
    Map<String, String> m = new LinkedHashMap<>();
    try (InputStream in = DataIdRenames.class.getResourceAsStream("/foxgrade/data-id-renames.json")) {
      if (in != null) {
        JsonElement root = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        if (root.isJsonObject()) for (var e : root.getAsJsonObject().entrySet()) m.put(e.getKey(), e.getValue().getAsString());
      }
    } catch (Exception ignored) { }
    return table = m;
  }

  /** The same array back when nothing in the file referred to a renamed id. */
  static byte[] apply(byte[] json) {
    Map<String, String> t = table();
    if (t.isEmpty()) return json;
    try {
      JsonElement root = Json.parse(new String(json, StandardCharsets.UTF_8));
      boolean[] changed = {false};
      JsonElement out = walk(root, t, changed);
      if (!changed[0]) return json;
      return new GsonBuilder().disableHtmlEscaping().create().toJson(out).getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return json;
    }
  }

  private static JsonElement walk(JsonElement e, Map<String, String> t, boolean[] changed) {
    if (e == null) return null;
    if (e.isJsonArray()) {
      JsonArray a = e.getAsJsonArray();
      for (int i = 0; i < a.size(); i++) a.set(i, walk(a.get(i), t, changed));
      return a;
    }
    if (e.isJsonObject()) {
      JsonObject o = e.getAsJsonObject(), r = new JsonObject();
      boolean keyChanged = false;
      for (var en : o.entrySet()) {
        String k = rename(en.getKey(), t);
        if (k != null) keyChanged = true;
        r.add(k != null ? k : en.getKey(), walk(en.getValue(), t, changed));
      }
      if (keyChanged) changed[0] = true;
      return r;   // the rebuilt object carries the rewritten values either way
    }
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
      String to = rename(e.getAsString(), t);
      if (to != null) { changed[0] = true; return new JsonPrimitive(to); }
    }
    return e;
  }

  /** null when the string is not a renamed id in any of the forms datapacks and resource packs use. */
  static String rename(String s, Map<String, String> t) {
    String r = t.get(s);
    if (r != null) return r;
    int br = s.indexOf('[');                                   // "minecraft:chain[axis=y]"
    if (br > 0 && (r = t.get(s.substring(0, br))) != null) return r + s.substring(br);
    for (String kind : new String[] {"block/", "item/"}) {     // model paths: minecraft:block/chain, block/chain
      for (String ns : new String[] {"minecraft:", ""}) {
        String pre = ns + kind;
        if (!s.startsWith(pre)) continue;
        String to = t.get("minecraft:" + s.substring(pre.length()));
        if (to != null && to.startsWith("minecraft:")) return pre + to.substring("minecraft:".length());
      }
    }
    return null;
  }
}
