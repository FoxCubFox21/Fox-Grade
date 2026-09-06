// Third-party API bridge table (v0.8.0).
//
// When a mod calls a Fabric-API method whose name changed between the mod's compile target and
// the current Fabric-API, Fox-Grade's Minecraft-only bridge cannot help — the class lives in
// net/fabricmc/fabric/api/…, not net/minecraft/…, and the intermediary tables don't cover it.
//
// This class carries per-owner method rename tables sourced from a bundled JSON file that grows
// as real failures come in from the community. It layers on top of BridgeRemapper.mapMethodName()
// as another lookup source — after per-class and per-global tables miss.
//
// Scope: PURE RENAMES ONLY. Same signature, same return type. Argument-type or return-type
// changes need synthetic shim classes (planned for v0.9). If the JSON lists a method here whose
// signature also changed, the port will silently produce a call that fails at load. Document
// each entry with the version pairs it's known-safe for.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class FabricApiBridges {
  private final Map<String, Map<String, String>> renames;   // owner → oldMethodName → newMethodName
  // owner → (name+desc) → [newOwner, newName, newDesc]: full call-site redirection, for APIs
  // that were REMOVED rather than renamed. The new owner is usually a Fox-Grade shim class
  // injected into the ported jar (see ShimGenerator).
  private final Map<String, Map<String, String[]>> callRedirects;
  // owner → ("get "|"put "|"getstatic "|"putstatic ") + name:desc → [shimOwner, method, desc]: a field
  // that stopped existing becomes a static getter/setter call on a shim (Entity.noCulling).
  private final Map<String, Map<String, String[]>> fieldRedirects;
  private final Map<String, String> classRenames;    // third-party class renames (slash form)
  // owner → oldCtorDesc → adapter: same-arity constructor signature changes, adapted per-slot.
  public record CtorTransform(int slot, String viaOwner, String viaName, String viaDesc) { }
  public record CtorAdapter(String newDesc, java.util.List<CtorTransform> transforms) { }
  private final Map<String, Map<String, CtorAdapter>> ctorAdapters;
  // name+desc → newName, applied to MOD-owned classes: a mod class overriding a renamed MC
  // method must have its DECLARATION renamed too, or the JVM sees an abstract method un-overridden.
  private final Map<String, String> inheritedRenames;

  private FabricApiBridges(Map<String, Map<String, String>> renames, Map<String, Map<String, String[]>> callRedirects,
                           Map<String, String> classRenames, Map<String, Map<String, CtorAdapter>> ctorAdapters,
                           Map<String, String> inheritedRenames, Map<String, Map<String, String[]>> fieldRedirects) {
    this.renames = renames; this.callRedirects = callRedirects; this.classRenames = classRenames;
    this.ctorAdapters = ctorAdapters; this.inheritedRenames = inheritedRenames; this.fieldRedirects = fieldRedirects;
  }

  public Map<String, Map<String, CtorAdapter>> ctorAdapters() { return ctorAdapters; }
  public Map<String, String> inheritedRenames() { return inheritedRenames; }

  public Map<String, Map<String, String>> renames() { return renames; }
  public Map<String, Map<String, String[]>> callRedirects() { return callRedirects; }
  public Map<String, Map<String, String[]>> fieldRedirects() { return fieldRedirects; }
  public Map<String, String> classRenames() { return classRenames; }
  public int size() {
    int n = 0;
    for (var m : renames.values()) n += m.size();
    return n;
  }

  // Loads the shipped table plus, if present, a user extension at
  // <gamedir>/fox-grade.api-bridges.json (same shape). Entries in the user file win on collision.
  public static FabricApiBridges load(Path gameDir) throws IOException {
    Map<String, Map<String, String>> renames = new HashMap<>();
    Map<String, Map<String, String[]>> redirects = new HashMap<>();
    Map<String, String> classRenames = new HashMap<>();
    Map<String, Map<String, CtorAdapter>> ctorAdapters = new HashMap<>();
    Map<String, String> inheritedRenames = new HashMap<>();
    Map<String, Map<String, String[]>> fieldRedirects = new HashMap<>();
    try (InputStream shipped = FabricApiBridges.class.getResourceAsStream("/foxgrade/fabric-api-bridges.json")) {
      if (shipped != null) merge(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, new String(shipped.readAllBytes()));
    }
    Path user = gameDir.resolve("fox-grade.api-bridges.json");
    if (Files.exists(user)) merge(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, Files.readString(user));
    return new FabricApiBridges(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects);
  }

  private static void merge(Map<String, Map<String, String>> into, Map<String, Map<String, String[]>> redirects,
                            Map<String, String> classRenames, Map<String, Map<String, CtorAdapter>> ctorAdapters,
                            Map<String, String> inheritedRenames, Map<String, Map<String, String[]>> fieldRedirects, String json) {
    JsonObject o = new Gson().fromJson(json, JsonObject.class);
    if (o == null) return;
    if (o.has("renames") && o.get("renames").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("renames").entrySet()) {
        String owner = ownerEntry.getKey().replace('.', '/');
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, String> map = into.computeIfAbsent(owner, k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) map.put(m.getKey(), m.getValue().getAsString());
      }
    }
    if (o.has("classRenames") && o.get("classRenames").isJsonObject()) {
      for (var e : o.getAsJsonObject("classRenames").entrySet())
        classRenames.put(e.getKey().replace('.', '/'), e.getValue().getAsString().replace('.', '/'));
    }
    if (o.has("inheritedRenames") && o.get("inheritedRenames").isJsonObject()) {
      for (var e : o.getAsJsonObject("inheritedRenames").entrySet())
        inheritedRenames.put(e.getKey(), e.getValue().getAsString());
    }
    if (o.has("ctorAdapters") && o.get("ctorAdapters").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("ctorAdapters").entrySet()) {
        String owner = ownerEntry.getKey().replace('.', '/');
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, CtorAdapter> map = ctorAdapters.computeIfAbsent(owner, k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
          JsonObject a = m.getValue().getAsJsonObject();
          java.util.List<CtorTransform> ts = new java.util.ArrayList<>();
          for (var t : a.getAsJsonArray("transforms")) {
            JsonObject to = t.getAsJsonObject();
            var via = to.getAsJsonArray("via");
            ts.add(new CtorTransform(to.get("slot").getAsInt(),
                via.get(0).getAsString(), via.get(1).getAsString(), via.get(2).getAsString()));
          }
          map.put(m.getKey(), new CtorAdapter(a.get("newDesc").getAsString(), ts));
        }
      }
    }
    if (o.has("fieldRedirects") && o.get("fieldRedirects").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("fieldRedirects").entrySet()) {
        String owner = ownerEntry.getKey().replace('.', '/');
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, String[]> map = fieldRedirects.computeIfAbsent(owner, k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
          var arr = m.getValue().getAsJsonArray();
          map.put(m.getKey(), new String[]{arr.get(0).getAsString(), arr.get(1).getAsString(), arr.get(2).getAsString()});
        }
      }
    }
    if (o.has("callRedirects") && o.get("callRedirects").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("callRedirects").entrySet()) {
        String owner = ownerEntry.getKey().replace('.', '/');
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, String[]> map = redirects.computeIfAbsent(owner, k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
          var arr = m.getValue().getAsJsonArray();
          map.put(m.getKey(), new String[]{arr.get(0).getAsString(), arr.get(1).getAsString(), arr.get(2).getAsString()});
        }
      }
    }
  }
}
