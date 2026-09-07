package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/** Re-serialises a JSON file that only a lenient parser accepts. Returns null when the file is already strict (or is not
 *  JSON at all — those are left untouched for the game to report). */
public final class JsonStrict {
  private JsonStrict() {}
  public static byte[] strictenIfNeeded(byte[] raw) {
    String text = new String(raw, StandardCharsets.UTF_8);
    try {
      JsonReader strict = new JsonReader(new StringReader(text)); strict.setLenient(false);
      JsonElement e = com.google.gson.internal.bind.TypeAdapters.JSON_ELEMENT.read(strict);
      if (strict.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IllegalStateException("trailing content");
      if (e != null) return null;
    } catch (RuntimeException | java.io.IOException notStrict) { /* fall through */ }
    try {
      JsonReader lenient = new JsonReader(new StringReader(text)); lenient.setLenient(true);
      JsonElement e = com.google.gson.JsonParser.parseReader(lenient);
      if (e == null || e.isJsonNull()) return null;
      return (new Gson().toJson(e) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException notJson) { return null; }
  }
}
