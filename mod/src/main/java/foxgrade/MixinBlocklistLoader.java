// Load the mixin-handler blocklist from the shipped resource and, if present, a user extension
// at <gamedir>/fox-grade.mixin-blocklist.json. Entries from both files are merged; a whole-class
// entry (methods omitted or empty) beats a per-method entry.
//
// Shape:
//   { "entries": [ { "mixin": "com/example/Mixin", "methods": ["handlerA"], "reason": "..." }, ... ] }
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MixinBlocklistLoader {

  // Map<slashMixin, Set<methodName>|null>. null = "strip every method on this class".
  public static Map<String, Set<String>> load(Path gameDir) throws IOException {
    Map<String, Set<String>> map = new HashMap<>();
    try (InputStream shipped = MixinBlocklistLoader.class.getResourceAsStream("/foxgrade/mixin-blocklist.json")) {
      if (shipped != null) merge(map, new String(shipped.readAllBytes()));
    }
    // Anti-rules learned on this machine and published since the mod was built, merged on top of what shipped and
    // under the user's own file, which stays the last word.
    String learned = KnowledgeBase.blocklistDocument(gameDir);
    if (learned != null) merge(map, learned);
    Path user = gameDir.resolve("fox-grade.mixin-blocklist.json");
    if (Files.exists(user)) merge(map, Files.readString(user));
    return map;
  }

  private static void merge(Map<String, Set<String>> map, String json) {
    JsonObject o = new Gson().fromJson(json, JsonObject.class);
    if (o == null || !o.has("entries")) return;
    for (var el : o.getAsJsonArray("entries")) {
      if (!el.isJsonObject()) continue;
      JsonObject e = el.getAsJsonObject();
      if (!e.has("mixin")) continue;
      String cls = e.get("mixin").getAsString().replace('.', '/');
      Set<String> existing = map.get(cls);
      var arr = e.has("methods") && e.get("methods").isJsonArray() ? e.getAsJsonArray("methods") : null;
      if (arr == null || arr.size() == 0) { map.put(cls, null); continue; }
      if (existing == null && map.containsKey(cls)) continue;                // whole-class already wins
      Set<String> set = existing != null ? existing : new HashSet<>();
      for (var m : arr) set.add(m.getAsString());
      map.put(cls, set);
    }
  }
}
