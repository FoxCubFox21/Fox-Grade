package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** What this installation has learned from its own runs.
 *
 *  <p>The crash guard already retires a port that took the game down, which stops the bleeding and forgets the
 *  lesson: reinstall the mod and Fox-Grade will port it exactly the same way, into exactly the same crash. Writing
 *  the verdict down turns a one-off rescue into something the instance knows.
 *
 *  <p>Kept in {@code .fox-grade/learned.json} beside the cache, in the same shape as the feed it can be contributed
 *  to, so what one machine learns is a file someone can read and, if they choose, publish for everyone. Nothing is
 *  sent anywhere from here — this writes a local file and reads it back. */
public final class KnowledgeBase {
  private KnowledgeBase() { }

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private static Path file(Path gameDir) {
    return gameDir.resolve(".fox-grade").resolve("learned.json");
  }

  static JsonObject read(Path gameDir) {
    try {
      Path f = file(gameDir);
      if (!Files.exists(f)) return new JsonObject();
      JsonObject o = new Gson().fromJson(Files.readString(f), JsonObject.class);
      return o == null ? new JsonObject() : o;
    } catch (Exception unreadable) {
      return new JsonObject();
    }
  }

  /** Records that a port of {@code modId} took the game down on {@code targetMc}, with whatever the report blamed. */
  public static void recordCrash(Path gameDir, String modId, String targetMc, String evidence) {
    try {
      JsonObject all = read(gameDir);
      JsonObject crashes = all.has("crashed") && all.get("crashed").isJsonObject()
          ? all.getAsJsonObject("crashed") : new JsonObject();
      JsonObject entry = crashes.has(modId) && crashes.get(modId).isJsonObject()
          ? crashes.getAsJsonObject(modId) : new JsonObject();
      entry.addProperty("target", targetMc);
      entry.addProperty("lastSeen", java.time.LocalDate.now().toString());
      entry.addProperty("times", (entry.has("times") ? entry.get("times").getAsInt() : 0) + 1);
      if (evidence != null && !evidence.isBlank()) entry.addProperty("evidence", evidence.strip());
      crashes.add(modId, entry);
      all.add("crashed", crashes);
      all.addProperty("schema", 1);
      Files.createDirectories(file(gameDir).getParent());
      Files.writeString(file(gameDir), GSON.toJson(all) + "\n", StandardCharsets.UTF_8);
    } catch (IOException cannotWrite) {
      // Learning is an improvement, never a requirement. A read-only game directory must not stop a port.
    }
  }

  /** Mod ids this installation has seen crash on this target, with how many times. */
  public static JsonObject crashes(Path gameDir) {
    JsonObject all = read(gameDir);
    return all.has("crashed") && all.get("crashed").isJsonObject() ? all.getAsJsonObject("crashed") : new JsonObject();
  }

  /** Anti-rules this installation has learned or been sent, in the same shape the bundled blocklist uses.
   *
   *  <p>Both sources are anti-rules — "do not apply this, it is known to break" — and they combine rather than
   *  compete: a fact learned here is not less true because the feed has not heard of it yet, nor the other way
   *  round. Returned as a blocklist document so the loader merges it with the code that already merges the
   *  bundled file and the user's own overrides.
   *
   *  <p>Null when there is nothing to add, which is the common case and costs nothing. */
  public static String blocklistDocument(Path gameDir) {
    JsonArray entries = new JsonArray();
    appendEntries(RulesFeed.section(gameDir, "mixinBlocklist"), entries);
    JsonObject local = read(gameDir);
    appendEntries(local.has("mixinBlocklist") && local.get("mixinBlocklist").isJsonObject()
        ? local.getAsJsonObject("mixinBlocklist") : null, entries);
    if (entries.size() == 0) return null;   // JsonArray.isEmpty is Gson 2.8.7; 1.17.1 ships 2.8.0
    JsonObject doc = new JsonObject();
    doc.add("entries", entries);
    return GSON.toJson(doc);
  }

  private static void appendEntries(JsonObject section, JsonArray into) {
    if (section == null || !section.has("entries") || !section.get("entries").isJsonArray()) return;
    for (JsonElement e : section.getAsJsonArray("entries")) into.add(e);
  }
}
