// Translate Fabric intermediary names — classes AND members — to their real Mojang names at the
// target MC version.
//
// Why: most Fabric mods on the wire are compiled against intermediary, not Mojang. Fox-Grade's
// rules table names classes by Mojang FQCN. Without a bridge, the bytecode remapper never
// matches anything on a Fabric mod. And a class-only bridge leaves method calls like
// `Minecraft.method_1574()` untouched — Fabric's runtime remap does not cover 26.x, so those
// crash at load. The bridge therefore carries three tables:
//
//   classes  Map<slashInter, slashMoj>         net/minecraft/class_310 → net/minecraft/client/Minecraft
//   methods  Map<slashOwner, Map<inter, moj>>  by owner: method_1574 → tick, method_5773 → doTick, …
//   fields   Map<slashOwner, Map<inter, moj>>  by owner: field_1729 → level, …
//
// The bridge itself is generated offline by chaining Fabric intermediary-1.21.1 with Mojang's
// 1.21.1 mappings, joining on the obfuscated method/field descriptor, then projecting mojang
// class names forward through Fox-Grade's classmoves.1.21.1-26.2 table. Shipped gzipped as
// intermediary-to-mojang.<mc>.json[.gz].
//
// When no bridge for the target MC is bundled, the tables come back empty and the remapper
// falls back to plain Fox-Grade rules — same behaviour as v0.5.0.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class IntermediaryBridge {
  private final Map<String, String> classes;                        // slash → slash
  private final Map<String, Map<String, String>> methods;           // slashOwner → inter → moj
  private final Map<String, Map<String, String>> fields;            // slashOwner → inter → moj
  private final Map<String, String> globalMethods;                  // inter → moj — fallback for inherited members
  private final Map<String, String> globalFields;
  // Mojang-source mods (compiled with official mappings) don't use intermediary names at all.
  // Their calls carry 1.21.1 mojang names that 26.2 renamed (getLocation → location). These
  // tables are keyed "name+descriptor" (original 1.21.1 desc) per owner, plus a global table
  // for entries whose name+descriptor is unique across all owners (inherited-call fallback).
  private final Map<String, Map<String, String>> mojangMethods;
  private final Map<String, String> mojangMethodsGlobal;
  // Yarn-compiled mods carry yarn names (net/minecraft/util/math/BlockPos). Same shape as the
  // mojang tables: per-owner name+desc → final target name.
  private final Map<String, Map<String, String>> yarnMethods;
  private final Map<String, Map<String, String>> yarnFields;

  private IntermediaryBridge(Map<String, String> classes, Map<String, Map<String, String>> methods, Map<String, Map<String, String>> fields,
                             Map<String, String> globalMethods, Map<String, String> globalFields,
                             Map<String, Map<String, String>> mojangMethods, Map<String, String> mojangMethodsGlobal,
                             Map<String, Map<String, String>> yarnMethods, Map<String, Map<String, String>> yarnFields) {
    this.classes = classes; this.methods = methods; this.fields = fields;
    this.globalMethods = globalMethods; this.globalFields = globalFields;
    this.mojangMethods = mojangMethods; this.mojangMethodsGlobal = mojangMethodsGlobal;
    this.yarnMethods = yarnMethods; this.yarnFields = yarnFields;
  }

  public Map<String, String> classTable() { return classes; }
  public Map<String, Map<String, String>> methodTable() { return methods; }
  public Map<String, Map<String, String>> fieldTable() { return fields; }
  public Map<String, String> globalMethodTable() { return globalMethods; }
  public Map<String, String> globalFieldTable() { return globalFields; }
  public Map<String, Map<String, String>> mojangMethodTable() { return mojangMethods; }
  public Map<String, String> mojangMethodGlobalTable() { return mojangMethodsGlobal; }
  public Map<String, Map<String, String>> yarnMethodTable() { return yarnMethods; }
  public Map<String, Map<String, String>> yarnFieldTable() { return yarnFields; }
  public int size() { return classes.size(); }
  public int memberCount() {
    int n = 0;
    for (var m : methods.values()) n += m.size();
    for (var m : fields.values()) n += m.size();
    return n;
  }

  public static IntermediaryBridge load(String targetMc) throws IOException { return load(targetMc, null); }

  /** With a cache directory, the parsed tables are read from / written to a binary cache (see TableCache). */
  public static IntermediaryBridge load(String targetMc, java.nio.file.Path cacheDir) throws IOException {
    targetMc = Targets.tables(targetMc);          // a version may share another's tables
    String stamp = TableCache.stamp("/foxgrade/intermediary-to-mojang." + targetMc + ".json.gz", targetMc);
    try (java.io.DataInputStream in = TableCache.open(cacheDir, "intermediary-" + targetMc + ".bin", stamp)) {
      if (in != null) {
        return new IntermediaryBridge(TableCache.readMap(in), TableCache.readNested(in), TableCache.readNested(in), TableCache.readMap(in), TableCache.readMap(in),
            TableCache.readNested(in), TableCache.readMap(in), TableCache.readNested(in), TableCache.readNested(in));
      }
    } catch (IOException stale) { /* parse below and rewrite */ }
    IntermediaryBridge b = parse(targetMc);
    try (java.io.DataOutputStream out = TableCache.create(cacheDir, "intermediary-" + targetMc + ".bin", stamp)) {
      if (out != null) {
        TableCache.writeMap(out, b.classes); TableCache.writeNested(out, b.methods); TableCache.writeNested(out, b.fields); TableCache.writeMap(out, b.globalMethods); TableCache.writeMap(out, b.globalFields);
        TableCache.writeNested(out, b.mojangMethods); TableCache.writeMap(out, b.mojangMethodsGlobal); TableCache.writeNested(out, b.yarnMethods); TableCache.writeNested(out, b.yarnFields);
      }
    } catch (IOException ignored) { }
    return b;
  }

  private static IntermediaryBridge parse(String targetMc) throws IOException {
    // Try plain first (dev convenience), then the gzipped resource (the shipped form).
    String base = "/foxgrade/intermediary-to-mojang." + targetMc + ".json";
    String text = readResource(base);
    if (text == null) text = readGzResource(base + ".gz");
    if (text == null) return new IntermediaryBridge(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    JsonObject o = new Gson().fromJson(text, JsonObject.class);
    if (o == null) return new IntermediaryBridge(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    Map<String, String> classes = new HashMap<>();
    if (o.has("classes") && o.get("classes").isJsonObject()) {
      for (var e : o.getAsJsonObject("classes").entrySet())
        classes.put(e.getKey().replace('.', '/'), e.getValue().getAsString().replace('.', '/'));
    }
    Map<String, Map<String, String>> methods = new HashMap<>();
    Map<String, Map<String, String>> fields = new HashMap<>();
    if (o.has("members") && o.get("members").isJsonObject()) {
      for (var clsEntry : o.getAsJsonObject("members").entrySet()) {
        String slashOwner = clsEntry.getKey().replace('.', '/');
        JsonObject ms = clsEntry.getValue().getAsJsonObject();
        if (ms.has("methods") && ms.get("methods").isJsonObject()) {
          Map<String, String> m = new HashMap<>();
          for (var me : ms.getAsJsonObject("methods").entrySet()) m.put(me.getKey(), me.getValue().getAsString());
          if (!m.isEmpty()) methods.put(slashOwner, m);
        }
        if (ms.has("fields") && ms.get("fields").isJsonObject()) {
          Map<String, String> m = new HashMap<>();
          for (var me : ms.getAsJsonObject("fields").entrySet()) m.put(me.getKey(), me.getValue().getAsString());
          if (!m.isEmpty()) fields.put(slashOwner, m);
        }
      }
    }
    Map<String, String> globalMethods = new HashMap<>();
    Map<String, String> globalFields = new HashMap<>();
    if (o.has("globalMethods") && o.get("globalMethods").isJsonObject()) {
      for (var e : o.getAsJsonObject("globalMethods").entrySet()) globalMethods.put(e.getKey(), e.getValue().getAsString());
    }
    if (o.has("globalFields") && o.get("globalFields").isJsonObject()) {
      for (var e : o.getAsJsonObject("globalFields").entrySet()) globalFields.put(e.getKey(), e.getValue().getAsString());
    }
    Map<String, Map<String, String>> mojangMethods = new HashMap<>();
    if (o.has("mojangMethods") && o.get("mojangMethods").isJsonObject()) {
      for (var ce : o.getAsJsonObject("mojangMethods").entrySet()) {
        Map<String, String> m = new HashMap<>();
        for (var me : ce.getValue().getAsJsonObject().entrySet()) m.put(me.getKey(), me.getValue().getAsString());
        mojangMethods.put(ce.getKey(), m);
      }
    }
    Map<String, String> mojangMethodsGlobal = new HashMap<>();
    if (o.has("mojangMethodsGlobal") && o.get("mojangMethodsGlobal").isJsonObject()) {
      for (var e : o.getAsJsonObject("mojangMethodsGlobal").entrySet()) mojangMethodsGlobal.put(e.getKey(), e.getValue().getAsString());
    }
    Map<String, Map<String, String>> yarnMethods = loadOwnerTable(o, "yarnMethods");
    Map<String, Map<String, String>> yarnFields = loadOwnerTable(o, "yarnFields");
    return new IntermediaryBridge(classes, methods, fields, globalMethods, globalFields, mojangMethods, mojangMethodsGlobal, yarnMethods, yarnFields);
  }

  private static Map<String, Map<String, String>> loadOwnerTable(JsonObject o, String key) {
    Map<String, Map<String, String>> out = new HashMap<>();
    if (o.has(key) && o.get(key).isJsonObject()) {
      for (var ce : o.getAsJsonObject(key).entrySet()) {
        Map<String, String> m = new HashMap<>();
        for (var me : ce.getValue().getAsJsonObject().entrySet()) m.put(me.getKey(), me.getValue().getAsString());
        out.put(ce.getKey(), m);
      }
    }
    return out;
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = IntermediaryBridge.class.getResourceAsStream(path)) {
      if (in == null) return null;
      return new String(in.readAllBytes());
    }
  }
  private static String readGzResource(String path) throws IOException {
    try (InputStream in = IntermediaryBridge.class.getResourceAsStream(path)) {
      if (in == null) return null;
      try (GZIPInputStream gz = new GZIPInputStream(in)) {
        var out = new ByteArrayOutputStream();
        gz.transferTo(out);
        return out.toString("UTF-8");
      }
    }
  }
}
