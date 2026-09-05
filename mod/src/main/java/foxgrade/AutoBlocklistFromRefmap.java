// Automatically extend the mixin-handler blocklist by verifying refmap targets against the real
// target-MC class inventory.
//
// After Fox-Grade's translation, a mixin refmap looks like:
//   { "mappings": { "com/example/mixin/FooMixin": {
//       "handlerName": "Lnet/minecraft/foo/Bar;method(...)V",
//       "otherHandler": "Lnet/minecraft/foo/Bar;field:I"
//   }}}
//
// If `net/minecraft/foo/Bar.method(...)V` doesn't exist on Bar in the target MC — because Mojang
// changed the signature or removed the method — the mixin handler will fail injection with
// "no targets matching", crashing the whole client. This class walks the refmap, checks each
// target selector against the bundled MC-class inventory, and returns the set of handlers whose
// targets are gone. Those get stripped alongside the manually-blocklisted ones.
//
// Bundled resource: mc-<mc>.classes.json.gz — {classFqcn: {"m": [methodNames], "f": [fieldNames]}}
// Produced offline by the same mining tool that built the classmoves table.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AutoBlocklistFromRefmap {

  // Map<class, {"m": Set<method>, "f": Set<field>}>. Only cares about names, not signatures —
  // if the name is present the injector's descriptor-check will handle finer signature verify.
  private final Map<String, Set<String>> methodsByClass = new HashMap<>();
  private final Map<String, Set<String>> fieldsByClass = new HashMap<>();
  private boolean loaded = false;

  public AutoBlocklistFromRefmap(String targetMc) throws IOException {
    String resource = "/foxgrade/mc-" + targetMc + ".classes.json.gz";
    try (InputStream in = AutoBlocklistFromRefmap.class.getResourceAsStream(resource)) {
      if (in == null) return;                       // no inventory — feature quietly disabled
      try (GZIPInputStream gz = new GZIPInputStream(in)) {
        var out = new ByteArrayOutputStream();
        gz.transferTo(out);
        JsonObject o = new Gson().fromJson(out.toString("UTF-8"), JsonObject.class);
        for (var e : o.entrySet()) {
          JsonObject c = e.getValue().getAsJsonObject();
          Set<String> m = new HashSet<>();
          if (c.has("m")) c.getAsJsonArray("m").forEach((x) -> m.add(x.getAsString()));
          Set<String> f = new HashSet<>();
          if (c.has("f")) c.getAsJsonArray("f").forEach((x) -> f.add(x.getAsString()));
          methodsByClass.put(e.getKey(), m);
          fieldsByClass.put(e.getKey(), f);
        }
        loaded = true;
      }
    }
  }

  public boolean isLoaded() { return loaded; }
  public Set<String> classNames() { return methodsByClass.keySet(); }
  public Set<String> methodSet(String cls) { return methodsByClass.get(cls); }
  public Set<String> fieldSet(String cls) { return fieldsByClass.get(cls); }

  // Walk the refmap and return Map<mixinClass, Set<handlerName>> that should be stripped because
  // their (translated) target selector no longer resolves in the target MC.
  public Map<String, Set<String>> scanRefmap(byte[] refmapJson) {
    Map<String, Set<String>> result = new HashMap<>();
    if (!loaded) return result;
    JsonObject o = new Gson().fromJson(new String(refmapJson), JsonObject.class);
    if (o == null || !o.has("mappings") || !o.get("mappings").isJsonObject()) return result;
    for (var mixinEntry : o.getAsJsonObject("mappings").entrySet()) {
      String mixin = mixinEntry.getKey();
      if (!mixinEntry.getValue().isJsonObject()) continue;
      for (var handlerEntry : mixinEntry.getValue().getAsJsonObject().entrySet()) {
        String handler = handlerEntry.getKey();
        if (!handlerEntry.getValue().isJsonPrimitive()) continue;
        String selector = handlerEntry.getValue().getAsString();
        if (!targetExists(selector)) {
          result.computeIfAbsent(mixin, k -> new HashSet<>()).add(cleanHandlerName(handler));
        }
      }
    }
    return result;
  }

  // Convenience: scan every refmap in a jar. Returns the merged map.
  public Map<String, Set<String>> scanJar(java.nio.file.Path jar) throws IOException {
    Map<String, Set<String>> merged = new HashMap<>();
    if (!loaded) return merged;
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      var entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (!name.endsWith(".json") || !name.toLowerCase().contains("refmap")) continue;
        try (InputStream in = zf.getInputStream(e)) {
          byte[] bytes = in.readAllBytes();
          for (var entry : scanRefmap(bytes).entrySet()) {
            merged.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
          }
        }
      }
    }
    return merged;
  }

  // Parses "Lowner;name(paramDesc)ret" or "Lowner;name:desc" and verifies the FULL SIGNATURE
  // against the target class. Name-only checking is not enough: MC 26.2 kept `setLevel` on
  // Minecraft but dropped an argument, so the name exists while the mixin's 2-arg target does
  // not — the injection still fatals. The inventory stores members as "name(desc)ret" /
  // "name:desc" strings, so an exact-string containment check IS the signature check.
  public boolean targetExists(String selector) {
    if (selector == null || !selector.startsWith("L")) return true;   // don't strip on parse fail
    int semi = selector.indexOf(';');
    if (semi < 0) return true;
    String owner = selector.substring(1, semi);
    String rest = selector.substring(semi + 1);
    // method: name(...)ret — inventory holds "name(paramDesc)retDesc"
    // An owner absent from the inventory is either (a) a non-MC class we can't judge — keep the
    // handler — or (b) an MC class REMOVED in the target: everything on it is gone, strip. The
    // ferritecore ItemRenderer.<init> @At target sat in case (b) and the old blanket "owner
    // unknown → don't strip" kept a handler that fataled the injector.
    boolean mcOwner = owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/blaze3d/");
    int paren = rest.indexOf('(');
    if (paren >= 0) {
      Set<String> ms = methodsByClass.get(owner);
      if (ms == null) return !mcOwner;
      return ms.contains(rest);
    }
    // field: name:type — inventory holds "name:desc"
    int colon = rest.indexOf(':');
    if (colon >= 0) {
      Set<String> fs = fieldsByClass.get(owner);
      if (fs == null) return !mcOwner;
      return fs.contains(rest);
    }
    return true;
  }

  // Handler names in refmap can carry a "name(desc)ret" suffix format when the mixin uses a
  // fully-qualified target. The blocklist matches on the JAVA method NAME only.
  private static String cleanHandlerName(String raw) {
    int paren = raw.indexOf('(');
    return paren < 0 ? raw : raw.substring(0, paren);
  }
}
