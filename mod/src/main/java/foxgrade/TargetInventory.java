package foxgrade;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** What the target version actually declares, read from the bundled class inventory.
 *
 *  <p>The same file {@code AutoBlocklistFromRefmap} uses, exposed as "which members does this class have". It answers
 *  the one question a table written for another version cannot answer for itself: is this thing still here? */
final class TargetInventory {
  private TargetInventory() { }

  private static final Map<String, Map<String, Set<String>>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

  /** class (slash form) -> the full signatures of its methods and fields, in exactly the shape a redirect key uses:
   *  {@code name(desc)ret} for a method, {@code name:desc} for a field. Empty when the target ships no inventory. */
  static Map<String, Set<String>> membersByClass(String targetMc) {
    return CACHE.computeIfAbsent(targetMc, TargetInventory::read);
  }

  private static Map<String, Set<String>> read(String targetMc) {
    Map<String, Set<String>> out = new HashMap<>();
    try (InputStream raw = TargetInventory.class.getResourceAsStream("/foxgrade/mc-" + targetMc + ".classes.json.gz")) {
      if (raw == null) return out;
      try (var in = new GZIPInputStream(raw)) {
        JsonObject all = JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
        for (var e : all.entrySet()) {
          JsonObject v = e.getValue().getAsJsonObject();
          Set<String> sigs = new HashSet<>();
          collect(v, "m", sigs);
          collect(v, "f", sigs);
          out.put(e.getKey(), sigs);
        }
      }
    } catch (Exception noInventory) {
      return Map.of();
    }
    return out;
  }

  private static void collect(JsonObject o, String key, Set<String> into) {
    if (!o.has(key) || !o.get(key).isJsonArray()) return;
    for (JsonElement x : o.getAsJsonArray(key)) into.add(x.getAsString());
  }
}
