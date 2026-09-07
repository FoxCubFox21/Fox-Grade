package foxgrade;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** 26.2 rejects a template-pool element weight above 150 ("Value 250 outside of range [1:150]"). 1.21 packs leaned on
 *  YUNG's API lifting that cap, so a pool over the limit is rescaled to fit, keeping the elements' ratios. */
final class TemplatePoolFix {
  private TemplatePoolFix() { }
  static final int MAX = 150;

  /** The same array back when nothing needed changing. */
  static byte[] clamp(byte[] json) {
    try {
      JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
      if (root == null || !root.isJsonObject()) return json;
      JsonElement els = root.getAsJsonObject().get("elements");
      if (els == null || !els.isJsonArray()) return json;
      int max = 0;
      for (JsonElement e : els.getAsJsonArray()) {
        Integer w = weight(e);
        if (w != null) max = Math.max(max, w);
      }
      if (max <= MAX) return json;
      for (JsonElement e : els.getAsJsonArray()) {
        Integer w = weight(e);
        if (w == null) continue;
        e.getAsJsonObject().addProperty("weight", Math.max(1, (int) Math.round(w * (double) MAX / max)));
      }
      return new GsonBuilder().disableHtmlEscaping().create().toJson(root).getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return json;
    }
  }

  private static Integer weight(JsonElement e) {
    if (!e.isJsonObject()) return null;
    JsonElement w = e.getAsJsonObject().get("weight");
    if (w == null || !w.isJsonPrimitive() || !w.getAsJsonPrimitive().isNumber()) return null;
    return w.getAsInt();
  }
}
