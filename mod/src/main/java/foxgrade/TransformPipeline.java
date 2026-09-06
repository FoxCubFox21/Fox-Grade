// Single-pass transform of one mod jar.
//
// Walks the source zip once, replacing entries as it goes:
//   · signature files (META-INF/*.SF|RSA|DSA|EC)    dropped — a rewritten jar cannot satisfy them
//   · fabric.mod.json                                pin depends.minecraft to target, add foxgrade marker
//   · *.accesswidener                                remap owners + descriptors
//   · *refmap*.json (mixin refmap shape)             remap L…; class refs + method/field names
//   · *.class                                        ASM bytecode remap (classes + methods + fields)
//   · everything else                                verbatim passthrough
//
// Idempotency: on port, the pipeline stamps `custom.foxgrade.pastPort: "<mc>"` inside the
// fabric.mod.json. On a later launch that sees the same jar and same target, the caller can skip
// the entire transform — the port already happened. isAlreadyPortedFor() answers that question
// without running the pipeline, so we don't churn mods-backup/ with re-ports of ports.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class TransformPipeline {
  private static final Pattern SIG = Pattern.compile("META-INF/.*\\.(SF|RSA|DSA|EC)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern REFMAP = Pattern.compile("(?i)refmap");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public static final class Outcome {
    public final byte[] outputBytes;
    public final int metaFixed, awFiles, awOwners, awDescs, refmapFiles, refmapHits, classesRemapped, mixinsStripped, autoStripped;
    public final java.util.Set<String> unresolvedRefs;   // MC classes referenced but absent in target
    public final java.util.List<String> deregisteredMixins = new java.util.ArrayList<>();   // removed from configs
    Outcome(byte[] b, int mf, int aw, int awo, int awd, int rf, int rh, int cr, int ms, int as, java.util.Set<String> ur) {
      this.outputBytes = b; this.metaFixed = mf; this.awFiles = aw; this.awOwners = awo; this.awDescs = awd;
      this.refmapFiles = rf; this.refmapHits = rh; this.classesRemapped = cr; this.mixinsStripped = ms; this.autoStripped = as;
      this.unresolvedRefs = ur;
    }
    public String oneLine() {
      String base = String.format("meta=%d, aw=%d(%do/%dd), refmap=%d(%dr), classes=%d, mixinsStripped=%d, autoStripped=%d",
          metaFixed, awFiles, awOwners, awDescs, refmapFiles, refmapHits, classesRemapped, mixinsStripped, autoStripped);
      if (!deregisteredMixins.isEmpty()) base += String.format(", mixinsDeregistered=%d", deregisteredMixins.size());
      if (unresolvedRefs.isEmpty()) return base;
      var sample = unresolvedRefs.stream().limit(3).map((c) -> c.substring(c.lastIndexOf('/') + 1)).toList();
      return base + String.format("; ⚠ %d UNRESOLVED ref(s) — crashes if reached (%s%s)",
          unresolvedRefs.size(), String.join(", ", sample), unresolvedRefs.size() > 3 ? ", …" : "");
    }
  }

  // Cheap check: does this jar's fabric.mod.json already declare it's been ported for `targetMc`?
  // Used to skip re-porting on a second launch after the first port succeeded.
  public static boolean isAlreadyPortedFor(Path jar, String targetMc) {
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      ZipEntry e = zf.getEntry("fabric.mod.json");
      if (e == null) return false;
      try (InputStream in = zf.getInputStream(e)) {
        JsonObject meta = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        if (meta == null || !meta.has("custom") || !meta.get("custom").isJsonObject()) return false;
        JsonObject custom = meta.getAsJsonObject("custom");
        if (!custom.has("foxgrade") || !custom.get("foxgrade").isJsonObject()) return false;
        JsonObject fg = custom.getAsJsonObject("foxgrade");
        return fg.has("pastPort") && targetMc.equals(fg.get("pastPort").getAsString());
      }
    } catch (Exception ignored) { return false; }
  }

  public static Outcome transform(Path src, String targetMc, RulesLoader rules, IntermediaryBridge bridge, FabricApiBridges apiBridges, Map<String, Set<String>> blocklist) throws IOException {
    // Per-port shim namespace: two ported jars must never define the same foxgrade/shim class,
    // or whichever loads first wins for both — including across Fox-Grade versions with
    // different shim bytes. The suffix comes from the source jar name; sanitised to a valid
    // Java identifier.
    String shimNs = src.getFileName().toString().replaceAll("\\.jar$", "").replaceAll("[^A-Za-z0-9]", "_");
    Map<String, String> mergedClasses = new HashMap<>(bridge.size() + rules.size());
    mergedClasses.putAll(bridge.classTable());
    mergedClasses.putAll(rules.slashTable());
    BytecodeRemapper remapper = new BytecodeRemapper(bridge, rules.slashTable(), apiBridges);
    // Auto-blocklist: pre-flight every refmap through translation and check each resolved
    // selector against the target MC's class inventory (full signatures). A selector that no
    // longer resolves — the method was removed or its signature changed — will fatal the mixin
    // injector at apply time. We can't get the handler's NAME from the refmap (its keys are raw
    // annotation strings), so we collect the broken RAW KEYS per mixin class here, and the class
    // pass below uses AnnotationTargetScanner to find which handler methods carry those strings
    // in their annotations, stripping exactly those.
    AutoBlocklistFromRefmap auto = new AutoBlocklistFromRefmap(targetMc);
    Map<String, Set<String>> brokenKeysByClass = new HashMap<>();
    Map<String, Map<String, String>> refmapByClass = new HashMap<>();   // raw key → translated selector
    int autoStripped = 0;
    if (auto.isLoaded()) {
      try (ZipFile in0 = new ZipFile(src.toFile())) {
        var entries0 = in0.entries();
        while (entries0.hasMoreElements()) {
          ZipEntry e = entries0.nextElement();
          String name = e.getName();
          if (!name.endsWith(".json") || !REFMAP.matcher(name).find()) continue;
          byte[] raw = readAll(in0, e);
          try {
            JsonElement parsed = new Gson().fromJson(new String(raw, StandardCharsets.UTF_8), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject() || !parsed.getAsJsonObject().has("mappings")) continue;
            JsonElement translated = MixinRefmapRemapper.rewrite(parsed, bridge, rules.slashTable(), apiBridges).json;
            JsonObject origMap = parsed.getAsJsonObject().getAsJsonObject("mappings");
            JsonObject transMap = translated.getAsJsonObject().getAsJsonObject("mappings");
            for (var clsEntry : origMap.entrySet()) {
              String mixinCls = clsEntry.getKey();
              if (!clsEntry.getValue().isJsonObject()) continue;
              JsonObject transCls = transMap != null && transMap.has(mixinCls) ? transMap.getAsJsonObject(mixinCls) : null;
              for (var sel : clsEntry.getValue().getAsJsonObject().entrySet()) {
                String rawKey = sel.getKey();
                String resolved = transCls != null && transCls.has(rawKey)
                    ? transCls.get(rawKey).getAsString() : sel.getValue().getAsString();
                refmapByClass.computeIfAbsent(mixinCls, k -> new HashMap<>()).put(rawKey, resolved);
                if (!auto.targetExists(resolved)) {
                  brokenKeysByClass.computeIfAbsent(mixinCls, k -> new HashSet<>()).add(rawKey);
                }
              }
            }
          } catch (Exception ignore) { /* not a real refmap, skip */ }
        }
      }
    }
    int metaFixed = 0, awFiles = 0, awOwners = 0, awDescs = 0, refmapFiles = 0, refmapHits = 0, classesRemapped = 0, mixinsStripped = 0;
    PortVerifier verifier = new PortVerifier(auto);
    // Every class of the jar, with its superclass in target names, so both the verifier and the
    // call rewrites can resolve members up a mod class's chain into the game's classes.
    try (ZipFile pre = new ZipFile(src.toFile())) {
      var en = pre.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
        try {
          org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(readAll(pre, e));
          String[] itfs = cr.getInterfaces();
          for (int i = 0; i < itfs.length; i++) itfs[i] = remapper.mapClass(itfs[i]);
          verifier.declareModClass(cr.getClassName(), cr.getSuperName() == null ? null : remapper.mapClass(cr.getSuperName()), itfs);
        } catch (Exception ignore) { }
      }
    } catch (Exception ignore) { }
    remapper.setSuperOf(verifier::superOf);
    String[] fromMcHolder = { "" };
    Set<String> fatalMixins = new HashSet<>();          // mixin classes to deregister from configs
    java.util.List<String> strippedNames = new java.util.ArrayList<>();   // "MixinClass#handler" per strip, for the panel
    java.util.LinkedHashMap<String, byte[]> buffered = new java.util.LinkedHashMap<>();
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (ZipFile in = new ZipFile(src.toFile()); ZipOutputStream out = new ZipOutputStream(sink)) {
      var entries = in.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (SIG.matcher(name).matches()) continue;
        if (e.isDirectory()) { buffered.put(name, new byte[0]); continue; }
        byte[] raw = readAll(in, e);
        byte[] emit = raw;
        // Nested Jar-in-Jar dependencies get the FULL pipeline recursively — an untransformed
        // bundled dep (say, an intermediary-era cloth-config) otherwise ships inside a "ported"
        // jar and crashes the loader the moment no newer copy of that dep is installed.
        if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
          try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fg-nested", ".jar");
            java.nio.file.Files.write(tmp, raw);
            Outcome inner = transform(tmp, targetMc, rules, bridge, apiBridges, blocklist);
            java.nio.file.Files.deleteIfExists(tmp);
            emit = inner.outputBytes;
            classesRemapped += inner.classesRemapped;
            awFiles += inner.awFiles; awOwners += inner.awOwners; awDescs += inner.awDescs;
            refmapFiles += inner.refmapFiles; refmapHits += inner.refmapHits;
            mixinsStripped += inner.mixinsStripped; autoStripped += inner.autoStripped;
          } catch (Exception ex) { /* leave the nested jar as-is if recursion fails */ }
          buffered.put(name, emit);
          continue;
        }
        if (name.equals("fabric.mod.json")) {
          try {
            JsonObject meta = new Gson().fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            // Remember what the mod was built FOR before the pin overwrites it — the panel shows
            // "from → to" per port.
            if (meta.has("depends") && meta.get("depends").isJsonObject()) {
              var dep = meta.getAsJsonObject("depends").get("minecraft");
              if (dep != null) {
                if (dep.isJsonPrimitive()) fromMcHolder[0] = dep.getAsString();
                else if (dep.isJsonArray()) {
                  StringBuilder sb = new StringBuilder();
                  for (var el : dep.getAsJsonArray()) { if (sb.length() > 0) sb.append(" | "); sb.append(el.getAsString()); }
                  fromMcHolder[0] = sb.toString();
                }
              }
            }
            boolean touched = FabricMetaFixer.rewriteMeta(meta, targetMc);
            // Idempotency marker — this jar has been transformed for targetMc, don't do it again.
            JsonObject custom = meta.has("custom") && meta.get("custom").isJsonObject() ? meta.getAsJsonObject("custom") : new JsonObject();
            JsonObject fg = custom.has("foxgrade") && custom.get("foxgrade").isJsonObject() ? custom.getAsJsonObject("foxgrade") : new JsonObject();
            fg.addProperty("pastPort", targetMc);
            // Alias the id so an official build appearing later can never hard-crash Fabric's
            // duplicate-id resolution; `provides` keeps satisfying everything that depends on
            // the original id. The original id rides in the marker for the yield-to-official
            // check at preLaunch.
            if (meta.has("id") && !meta.get("id").getAsString().endsWith("_fgport")) {
              String origId = meta.get("id").getAsString();
              fg.addProperty("originalId", origId);
              meta.addProperty("id", origId + "_fgport");
              com.google.gson.JsonArray provides = meta.has("provides") && meta.get("provides").isJsonArray()
                  ? meta.getAsJsonArray("provides") : new com.google.gson.JsonArray();
              provides.add(origId);
              meta.add("provides", provides);
            }
            custom.add("foxgrade", fg);
            meta.add("custom", custom);
            emit = (GSON.toJson(meta) + "\n").getBytes(StandardCharsets.UTF_8);
            if (touched) metaFixed++;
          } catch (Exception ex) { /* leave the meta alone if malformed */ }
        } else if ((name.toLowerCase().endsWith(".accesswidener") || name.toLowerCase().endsWith(".ct") || name.toLowerCase().endsWith(".classtweaker")) && !mergedClasses.isEmpty()) {
          try {
            AccessWidenerRemapper.Names names = new AccessWidenerRemapper.Names() {
              @Override public String method(String o, String n, String d) { return remapper.mapMethodName(o, n, d); }
              @Override public String field(String o, String n, String d) { return remapper.mapFieldName(o, n, d); }
            };
            AccessWidenerRemapper.Result r = AccessWidenerRemapper.rewrite(new String(raw, StandardCharsets.UTF_8), mergedClasses, names);
            if (r.owners > 0 || r.descriptors > 0) { emit = r.text.getBytes(StandardCharsets.UTF_8); awFiles++; awOwners += r.owners; awDescs += r.descriptors; }
          } catch (Exception ex) { /* leave AW alone if we can't parse it */ }
        } else if (name.endsWith(".json") && REFMAP.matcher(name).find() && !mergedClasses.isEmpty()) {
          try {
            JsonElement parsed = new Gson().fromJson(new String(raw, StandardCharsets.UTF_8), JsonElement.class);
            if (parsed != null && parsed.isJsonObject() && (parsed.getAsJsonObject().has("mappings") || parsed.getAsJsonObject().has("data"))) {
              MixinRefmapRemapper.Result r = MixinRefmapRemapper.rewrite(parsed, bridge, rules.slashTable(), apiBridges);
              if (r.hits > 0) { emit = (GSON.toJson(r.json) + "\n").getBytes(StandardCharsets.UTF_8); refmapFiles++; refmapHits += r.hits; }
            }
          } catch (Exception ex) { /* not a refmap after all */ }
        } else if (name.endsWith(".class")) {
          byte[] remapped = remapper.remap(raw);
          if (remapped != raw) { emit = remapped; classesRemapped++; }
          String slashClass = name.substring(0, name.length() - 6);
          // Manual mixin blocklist first.
          if (blocklist.containsKey(slashClass)) {
            var wanted = blocklist.get(slashClass);
            MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) ->
                (wanted == null && !mn.equals("<init>") && !mn.equals("<clinit>")) || (wanted != null && wanted.contains(mn)));
            if (strip.bytes != null) {
              emit = strip.bytes; mixinsStripped += strip.strippedNames.size();
              for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n);
            }
          }
          // Auto blocklist: strip handlers whose annotations reference a broken selector. The
          // annotation strings survive the ASM remap untouched (string values are not remapped),
          // so they still equal the refmap's raw keys collected in the pre-scan.
          Set<String> broken = brokenKeysByClass.get(slashClass);
          if (broken != null && !broken.isEmpty()) {
            var handlers = AnnotationTargetScanner.scan(emit, broken);
            if (!handlers.isEmpty()) {
              Set<String> names = new HashSet<>();
              for (var h : handlers) names.add(h.name + h.desc);
              MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) -> names.contains(mn + md));
              if (strip.bytes != null) { emit = strip.bytes; autoStripped += strip.strippedNames.size(); }
            }
          }
          // @Overwrite/@Shadow methods resolve by DECLARED name with no refmap entry — check
          // them against the target's member inventory. Missing shadow METHODS are stripped;
          // a missing shadow FIELD makes the class fatally broken — recorded here, and the
          // config post-pass below deregisters it from its mixin config entirely.
          if (auto.isLoaded()) {
            try {
              var scan2 = OverwriteScanner.scan(emit, auto, refmapByClass.getOrDefault(slashClass, Map.of()));
              if (scan2.fatallyBroken) {
                fatalMixins.add(slashClass);
              } else if (!scan2.strippableMethods.isEmpty()) {
                Set<String> names2 = new HashSet<>();
                for (var h : scan2.strippableMethods) names2.add(h.name + h.desc);
                MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) -> names2.contains(mn + md));
                if (strip.bytes != null) {
                  emit = strip.bytes; autoStripped += strip.strippedNames.size();
                  for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n);
                }
              }
            } catch (Exception ignore) { }
          }
          if (verifier.isActive()) { try { verifier.scan(emit); } catch (Exception ignore) { } }
        }
        buffered.put(name, emit);
      }
      // Post-pass: a fatally broken mixin class (missing @Shadow field) is removed from its
      // mixin config so Mixin never processes it — the feature goes inert instead of the game
      // going down. Config lists hold SIMPLE names relative to the config's `package`.
      // Crash-proofing: every mixin config in a ported jar is made non-required with
      // defaultRequire 0. A conflicting or unmatched injection then skips with a log line
      // instead of taking the whole game down — overlap with another mod degrades the ported
      // feature, never the boot. The strip/deregistration machinery stays as the layer that
      // removes KNOWN-broken handlers so they don't even log.
      for (var entry : buffered.entrySet()) {
        String n = entry.getKey();
        if (!n.endsWith(".json") || !n.contains("mixin")) continue;
        try {
          JsonObject cfg = new Gson().fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
          if (cfg == null || !cfg.has("package")) continue;
          cfg.addProperty("required", false);
          JsonObject inj = cfg.has("injectors") && cfg.get("injectors").isJsonObject()
              ? cfg.getAsJsonObject("injectors") : new JsonObject();
          inj.addProperty("defaultRequire", 0);
          cfg.add("injectors", inj);
          entry.setValue((GSON.toJson(cfg) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
      }
      if (!fatalMixins.isEmpty()) {
        // FEATURE-level granularity: mixin configs group cohesive features (ferritecore ships
        // one config per feature). A fatally broken member usually cooperates with its config
        // siblings (duck interfaces, shared impl) — surgically removing just the broken class
        // leaves the survivors casting to interfaces that never got added. Empty the WHOLE
        // config instead: the feature goes inert as a unit.
        for (var entry : buffered.entrySet()) {
          String n = entry.getKey();
          if (!n.endsWith(".json") || !(n.contains("mixin"))) continue;
          try {
            JsonObject cfg = new Gson().fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
            if (cfg == null || !cfg.has("package")) continue;
            String pkg = cfg.get("package").getAsString().replace('.', '/');
            boolean hasFatal = false;
            for (String listKey : new String[]{"mixins", "client", "server"}) {
              if (!cfg.has(listKey) || !cfg.get(listKey).isJsonArray()) continue;
              for (var el : cfg.getAsJsonArray(listKey)) {
                if (fatalMixins.contains(pkg + "/" + el.getAsString().replace('.', '/'))) { hasFatal = true; break; }
              }
            }
            if (hasFatal) {
              for (String listKey : new String[]{"mixins", "client", "server"}) {
                if (cfg.has(listKey)) cfg.add(listKey, new com.google.gson.JsonArray());
              }
              entry.setValue((GSON.toJson(cfg) + "\n").getBytes(StandardCharsets.UTF_8));
            }
          } catch (Exception ignore) { /* not a mixin config */ }
        }
      }
      // Shim injection. Two ways in: (a) an unresolved reference with a reviewed shim keyed on
      // the Minecraft name (Tuple, Tesselator) is injected AT that name; (b) a redirect fired and
      // named a foxgrade/shim class, injected under the per-port namespace so two ports never
      // share one copy. Shims may depend on other shims (ShimGenerator.SHIM_DEPS); the closure
      // is injected, and every injected class is rewritten with the full rename map so
      // shim-to-shim references point at the namespaced copies as well.
      java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
      for (String missing : new java.util.ArrayList<>(verifier.missing())) {
        if (ShimGenerator.SHIMS.containsKey(missing)) { wanted.add(missing); verifier.missing().remove(missing); }
      }
      wanted.addAll(remapper.usedShims());
      java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(wanted);
      while (!queue.isEmpty()) {
        for (String dep : ShimGenerator.SHIM_DEPS.getOrDefault(queue.poll(), java.util.List.of())) if (wanted.add(dep)) queue.add(dep);
      }
      Map<String, String> shimMap = new HashMap<>();
      for (String shimCls : wanted) {
        String nsName = namespacedShim(shimCls, shimNs);
        if (!nsName.equals(shimCls)) shimMap.put(shimCls, nsName);
      }
      for (String shimCls : wanted) {
        var shim = ShimGenerator.SHIMS.get(shimCls);
        if (shim == null) continue;
        String entryName = shimMap.getOrDefault(shimCls, shimCls) + ".class";
        if (buffered.containsKey(entryName)) continue;
        byte[] bytes = shim.get();
        if (!shimMap.isEmpty()) bytes = ShimGenerator.renameClasses(bytes, shimMap);
        buffered.put(entryName, bytes);
      }
      // Second pass over the mod's classes: point call sites at the namespaced shim names.
      if (!shimMap.isEmpty()) {
        for (var entry : buffered.entrySet()) {
          if (!entry.getKey().endsWith(".class") || entry.getKey().startsWith("foxgrade/shim/")) continue;
          byte[] b = entry.getValue();
          boolean touches = false;
          for (String k : shimMap.keySet()) if (new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).contains(k)) { touches = true; break; }
          if (!touches) continue;
          entry.setValue(ShimGenerator.renameClasses(b, shimMap));
        }
      }
      // Final marker enrichment: the panel in-game shows per-port stats, which only exist now
      // that every stage has run. Rewrite custom.foxgrade with the completed numbers.
      byte[] metaBytes = buffered.get("fabric.mod.json");
      if (metaBytes != null) {
        try {
          JsonObject meta = new Gson().fromJson(new String(metaBytes, StandardCharsets.UTF_8), JsonObject.class);
          JsonObject custom = meta.has("custom") && meta.get("custom").isJsonObject() ? meta.getAsJsonObject("custom") : new JsonObject();
          JsonObject fg = custom.has("foxgrade") && custom.get("foxgrade").isJsonObject() ? custom.getAsJsonObject("foxgrade") : new JsonObject();
          fg.addProperty("source", src.getFileName().toString());
          fg.addProperty("fromMc", fromMcHolder[0]);
          fg.addProperty("summary", String.format("%d classes remapped, %d handler(s) stripped, %d unresolved ref(s)",
              classesRemapped, mixinsStripped + autoStripped, verifier.missing().size()));
          com.google.gson.JsonArray un = new com.google.gson.JsonArray();
          verifier.missing().stream().limit(400).forEach((c) -> un.add(c.substring(c.lastIndexOf('/') + 1)));
          fg.add("unresolved", un);
          com.google.gson.JsonArray sh = new com.google.gson.JsonArray();
          strippedNames.stream().limit(40).forEach(sh::add);
          fg.add("strippedHandlers", sh);
          com.google.gson.JsonArray dr = new com.google.gson.JsonArray();
          fatalMixins.stream().limit(20).forEach((c) -> dr.add(c.substring(c.lastIndexOf('/') + 1)));
          fg.add("disabledMixins", dr);
          custom.add("foxgrade", fg);
          meta.add("custom", custom);
          buffered.put("fabric.mod.json", (GSON.toJson(meta) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
      }
      for (var entry : buffered.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    Outcome o = new Outcome(sink.toByteArray(), metaFixed, awFiles, awOwners, awDescs, refmapFiles, refmapHits, classesRemapped, mixinsStripped, autoStripped, verifier.missing());
    o.deregisteredMixins.addAll(fatalMixins);
    return o;
  }

  // Only foxgrade/shim/* classes get namespaced; MC-named shims (net/minecraft/util/Tuple) must
  // keep their exact name to satisfy the mod's references, and identical bytes make their
  // collisions harmless.
  private static String namespacedShim(String shimCls, String ns) {
    if (!shimCls.startsWith("foxgrade/shim/")) return shimCls;
    return "foxgrade/shim/" + ns + "/" + shimCls.substring("foxgrade/shim/".length());
  }


  private static byte[] readAll(ZipFile z, ZipEntry e) throws IOException {
    try (InputStream is = z.getInputStream(e)) { return is.readAllBytes(); }
  }
}
