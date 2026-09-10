package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** JSON parsing that works on every Minecraft Fox-Grade targets.
 *
 *  <p>{@code JsonParser.parseString} is a static added in Gson 2.8.6. Minecraft ships its own Gson, and the older
 *  versions ship an older one — so a call that compiles fine and works on 26.2 dies on 1.17.1 with
 *  {@code NoSuchMethodError: JsonParser.parseString}, during preLaunch, before Fox-Grade can report anything. That
 *  is a particularly bad way to fail: the porter itself takes the game down on exactly the versions it was just
 *  taught to support.
 *
 *  <p>{@code Gson.fromJson} has been there since the beginning and does the same job, so everything goes through
 *  here rather than through whichever API happened to be in scope. */
final class Json {
  private Json() { }

  private static final Gson GSON = new Gson();

  static JsonElement parse(String text) {
    return GSON.fromJson(text, JsonElement.class);
  }

  static JsonObject parseObject(String text) {
    JsonElement e = parse(text);
    return e == null || !e.isJsonObject() ? null : e.getAsJsonObject();
  }

  static JsonElement parse(com.google.gson.stream.JsonReader reader) {
    return GSON.fromJson(reader, JsonElement.class);
  }

  /** Straight from a Reader, which is how the bundled tables are read without holding the whole file as a String. */
  static JsonElement parse(java.io.Reader reader) {
    return GSON.fromJson(reader, JsonElement.class);
  }

  /** The field names of an object.
   *
   *  <p>{@code JsonObject.keySet} arrived in Gson 2.8.1 and Minecraft 1.17.1 ships 2.8.0, so calling it there fails
   *  with NoSuchMethodError in preLaunch — the same shape of failure as parseString, and found the same way, by
   *  someone running it. {@code entrySet} has always been there. */
  static java.util.List<String> keys(JsonObject o) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if (o == null) return out;
    for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) out.add(e.getKey());
    return out;
  }
}
