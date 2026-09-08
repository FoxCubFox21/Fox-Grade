package foxgrade;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** Biome JSON shape changes that a datapack carries no version stamp for.
 *
 *  <p>1.21 keyed a biome's carvers by carving step:
 *  <pre>"carvers": { "air": ["minecraft:cave", "minecraft:canyon"] }</pre>
 *  26.2 removed the liquid carving step and takes a plain carver set instead:
 *  <pre>"carvers": ["minecraft:cave", "minecraft:canyon"]</pre>
 *  A 1.21 biome therefore fails to parse ("Not a string: {\"air\":[...]}"), which leaves every biome in the pack
 *  unbound and the world unable to load. Flattening the map in registry order is exactly what the game's own data
 *  fixer would do, so the biome keeps the carvers it asked for. */
final class BiomeJsonFix {
  private BiomeJsonFix() { }

  /** True for the datapack paths this fix applies to. */
  static boolean applies(String entryName) {
    return entryName.endsWith(".json") && entryName.contains("/worldgen/biome/");
  }

  /** The same array back when nothing needed changing. */
  static byte[] apply(byte[] json) {
    try {
      JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
      if (root == null || !root.isJsonObject()) return json;
      JsonObject o = root.getAsJsonObject();
      JsonElement carvers = o.get("carvers");
      if (carvers == null || !carvers.isJsonObject()) return json;   // already a list, or absent
      JsonArray flat = new JsonArray();
      for (var e : carvers.getAsJsonObject().entrySet()) {
        JsonElement v = e.getValue();
        if (v.isJsonArray()) for (JsonElement c : v.getAsJsonArray()) flat.add(c);
        else if (v.isJsonPrimitive()) flat.add(v);
      }
      o.add("carvers", flat);
      return new GsonBuilder().disableHtmlEscaping().create().toJson(o).getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException notOurShape) {
      return json;
    }
  }
}
