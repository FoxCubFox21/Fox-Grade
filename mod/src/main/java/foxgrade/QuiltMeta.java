package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Quilt-only mods (a {@code quilt.mod.json} and no {@code fabric.mod.json}): the metadata maps onto Fabric's almost
 *  one to one, so the port gets a synthesised fabric.mod.json and is a plain Fabric mod from then on. Loader-API
 *  differences (Quilt's entrypoint interfaces, QuiltLoader) are shims. */
final class QuiltMeta {
  private QuiltMeta() { }
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final Map<String, String> ENTRYPOINTS = Map.of("init", "main", "client_init", "client", "server_init", "server", "pre_launch", "preLaunch");

  /** The jar's fabric.mod.json text — the real one, or one synthesised from quilt.mod.json; null when it is neither. */
  static String fabricMeta(ZipFile zf) {
    try {
      ZipEntry e = zf.getEntry("fabric.mod.json");
      if (e != null) return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
      ZipEntry q = zf.getEntry("quilt.mod.json");
      if (q != null) return fabricJsonFrom(new String(zf.getInputStream(q).readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception ignored) { }
    return null;
  }

  static boolean isQuiltOnly(ZipFile zf) { return zf.getEntry("fabric.mod.json") == null && zf.getEntry("quilt.mod.json") != null; }

  /** quilt.mod.json (schema 1) → fabric.mod.json (schema 1); null when the text is not a Quilt manifest. */
  static String fabricJsonFrom(String quiltJson) {
    JsonElement root = JsonParser.parseString(quiltJson);
    if (root == null || !root.isJsonObject()) return null;
    JsonObject q = root.getAsJsonObject();
    JsonObject ql = obj(q, "quilt_loader");
    if (ql == null) return null;
    JsonObject f = new JsonObject();
    f.addProperty("schemaVersion", 1);
    if (ql.has("id")) f.add("id", ql.get("id"));
    if (ql.has("version")) f.add("version", ql.get("version"));
    JsonObject md = obj(ql, "metadata");
    if (md != null) {
      for (String k : new String[] {"name", "description", "license", "icon"}) if (md.has(k)) f.add(k, md.get(k));
      JsonElement c = md.get("contributors");   // {"Name": "role"} or ["Name"]
      if (c != null) {
        JsonArray authors = new JsonArray();
        if (c.isJsonObject()) for (var en : c.getAsJsonObject().entrySet()) authors.add(en.getKey());
        else if (c.isJsonArray()) for (JsonElement x : c.getAsJsonArray()) authors.add(x.isJsonPrimitive() ? x.getAsString() : x.toString());
        if (!authors.isEmpty()) f.add("authors", authors);
      }
      if (md.has("contact") && md.get("contact").isJsonObject()) f.add("contact", md.get("contact"));
    }
    JsonObject mc = obj(q, "minecraft");
    String env = mc != null && mc.has("environment") ? mc.get("environment").getAsString() : "*";
    f.addProperty("environment", env.equals("dedicated_server") ? "server" : env.equals("client") ? "client" : "*");
    JsonObject eps = obj(ql, "entrypoints");
    if (eps != null) {
      JsonObject out = new JsonObject();
      for (var en : eps.entrySet()) {
        String key = ENTRYPOINTS.getOrDefault(en.getKey(), en.getKey());
        JsonArray list = en.getValue().isJsonArray() ? en.getValue().getAsJsonArray() : new JsonArray();
        if (!en.getValue().isJsonArray()) list.add(en.getValue());   // a single entry may be a bare string or {adapter, value}
        out.add(key, list);
      }
      f.add("entrypoints", out);
    }
    JsonElement mixin = q.get("mixin");
    if (mixin != null) {
      JsonArray list = mixin.isJsonArray() ? mixin.getAsJsonArray() : new JsonArray();
      if (!mixin.isJsonArray()) list.add(mixin);
      f.add("mixins", list);
    }
    if (q.has("access_widener")) f.add("accessWidener", q.get("access_widener"));
    JsonObject depends = new JsonObject();
    depends.addProperty("fabricloader", "*");
    JsonElement deps = ql.get("depends");
    if (deps != null && deps.isJsonArray()) {
      for (JsonElement d : deps.getAsJsonArray()) {
        String id; JsonElement versions = null; boolean optional = false;
        if (d.isJsonPrimitive()) id = d.getAsString();
        else if (d.isJsonObject()) {
          JsonObject o = d.getAsJsonObject();
          if (!o.has("id")) continue;
          id = o.get("id").getAsString(); versions = o.get("versions");
          optional = o.has("optional") && o.get("optional").isJsonPrimitive() && o.get("optional").getAsBoolean();
        } else continue;
        if (optional || id.equals("quilt_loader") || id.equals("quilt_base") || id.equals("qsl")) continue;
        if (id.equals("quilted_fabric_api") || id.equals("quilt_fabric_api")) id = "fabric-api";
        JsonElement range = versions == null ? null : versions.isJsonPrimitive() || versions.isJsonArray() ? versions : null;
        if (range == null) depends.addProperty(id, "*"); else depends.add(id, range);
      }
    }
    f.add("depends", depends);
    JsonElement jars = ql.get("jars");
    if (jars != null && jars.isJsonArray()) {
      JsonArray out = new JsonArray();
      for (JsonElement j : jars.getAsJsonArray()) { JsonObject o = new JsonObject(); o.add("file", j); out.add(o); }
      f.add("jars", out);
    }
    JsonElement provides = ql.get("provides");
    if (provides != null && provides.isJsonArray()) {
      JsonArray out = new JsonArray();
      for (JsonElement p : provides.getAsJsonArray()) out.add(p.isJsonObject() && p.getAsJsonObject().has("id") ? p.getAsJsonObject().get("id").getAsString() : p.getAsString());
      f.add("provides", out);
    }
    JsonObject fg = new JsonObject(); fg.addProperty("fromQuilt", true);
    JsonObject custom = new JsonObject(); custom.add("foxgrade", fg);
    f.add("custom", custom);
    return GSON.toJson(f) + "\n";
  }

  private static JsonObject obj(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null; }
}
