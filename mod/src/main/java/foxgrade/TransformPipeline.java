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

  /** Phase timings of the most recent transform(), for the launch log. */
  public static volatile String lastTimings = "";
  private static final java.util.concurrent.ConcurrentHashMap<String, AutoBlocklistFromRefmap> INVENTORIES = new java.util.concurrent.ConcurrentHashMap<>();

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

  /** Quilt Loader hosts register themselves as the mod "quilt_loader" (it also answers the Fabric loader API). */
  /** Where the port report lives inside a ported jar, for mods whose manifest cannot carry it. */
  public static final String PORT_REPORT = "foxgrade/port-report.json";

  /** A mod bundled inside another mod's jar, under whichever directory that loader uses.
   *
   *  <p>Fabric nests under {@code META-INF/jars/} and NeoForge under {@code META-INF/jarjar/}, and until this knew
   *  about the second one every library a NeoForge mod ships inside itself was copied through untouched. That is not
   *  a cosmetic omission: the bundled jar keeps its own manifest, so Xaero's Minimap shipped a xaerolib still gated
   *  at {@code minecraft = "[1.21, 1.21.1]"}, NeoForge refused to load it, and the minimap was then held back for a
   *  dependency it was carrying all along. */
  static boolean isNestedMod(String entryName) {
    return entryName.endsWith(".jar")
        && (entryName.startsWith("META-INF/jars/") || entryName.startsWith("META-INF/jarjar/"));
  }

  static boolean isQuiltHost() {
    try { return Loaders.current().name().equals("Quilt"); } catch (Throwable t) { return false; }
  }

  static boolean isNeoForgeHost() {
    // The standalone checker has no loader under it, so it cannot answer this by asking. Being able to run the
    // NeoForge path outside the game is what makes NeoForge ports testable without launching one.
    String forced = System.getProperty("foxgrade.loader", "");
    if (!forced.isEmpty()) return forced.equalsIgnoreCase("neoforge");
    try { return Loaders.current().name().equals("NeoForge"); } catch (Throwable t) { return false; }
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
    // Shims are compiled classes, so which build of them to inject depends on the version being ported for.
    ShimGenerator.targetVersion(targetMc);
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
    long tStart = System.nanoTime();
    AutoBlocklistFromRefmap auto = INVENTORIES.computeIfAbsent(targetMc, (v) -> { try { return new AutoBlocklistFromRefmap(v); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } });   // parsed once per launch, not per jar
    Map<String, Set<String>> brokenKeysByClass = new HashMap<>();
    Map<String, Set<String>> changedKeysByClass = new HashMap<>();   // target exists, but its parameter count changed
    Map<String, Map<String, String>> refmapByClass = new HashMap<>();   // raw key → translated selector
    boolean hasRefmapFile = false;
    int autoStripped = 0;
    int jsonStrictened = 0;
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
            hasRefmapFile = true;
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
                } else if (arityChanged(sel.getValue().getAsString(), resolved)) {
                  changedKeysByClass.computeIfAbsent(mixinCls, k -> new HashSet<>()).add(rawKey);
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
    remapper.setOracles(verifier::declares, verifier::finalInChain, verifier::implementedInChain, verifier::declaredInChain);
    remapper.setInterfacesOf(verifier::interfacesOf);
    remapper.setIsInterface(verifier::isInterface);
    remapper.setAbstractsOf(verifier::abstractsOf);
    remapper.setIsFinalClass(verifier::isFinalClass);
    String[] fromMcHolder = { "" };
    long tPrescan = System.nanoTime(), tClasses = tPrescan, tShims = tPrescan;
    Set<String> fatalMixins = new HashSet<>();          // mixin classes to deregister from configs
    Map<String, String> relocated = new HashMap<>();
    Map<String, Set<String>> strippedByClass = new HashMap<>();   // class → name+desc of every method removed from it
    Map<String, RemovedEventSynth.Missing> removedEvents = new HashMap<>();   // Fabric API callback types 26.2 dropped, referenced by this jar
    Set<String> mixinClasses = new HashSet<>();   // neutralised mixins moved out of their declared mixin package (Mixin refuses to load anything left inside it)
    java.util.List<String> strippedNames = new java.util.ArrayList<>();   // "MixinClass#handler" per strip, for the panel
    java.util.LinkedHashMap<String, byte[]> buffered = new java.util.LinkedHashMap<>();
    Set<String> droppedNested = new HashSet<>();   // bundled jars left out of the port (and of fabric.mod.json "jars")
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (ZipFile in = new ZipFile(src.toFile()); ZipOutputStream out = new ZipOutputStream(sink)) {
      // Every name the source jar holds, known before anything is written. The manifest is rewritten while the jar is
      // still being read, so asking what has been buffered so far would answer "not yet" for a file that is simply
      // later in the archive — and drop a declaration the jar does back.
      java.util.Set<String> sourceEntries = new java.util.HashSet<>();
      for (var it = in.entries(); it.hasMoreElements(); ) sourceEntries.add(it.nextElement().getName());
      boolean quiltOnly = QuiltMeta.isQuiltOnly(in);
      var entries = in.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (SIG.matcher(name).matches()) continue;
        if (e.isDirectory()) { buffered.put(name, new byte[0]); continue; }
        byte[] raw = readAll(in, e);
        if (NeoForgeMetaFixer.isManifest(name)) {
          // A NeoForge/Forge jar: widen its Minecraft and loader gates so the target will load it. Everything else
          // about the port is loader-agnostic, because it is Minecraft that changed, not the loader reading the jar.
          byte[] widened = NeoForgeMetaFixer.widen(raw, targetMc, sourceEntries);
          if (widened != raw) { raw = widened; metaFixed++; }
        }
        if (quiltOnly && name.equals("quilt.mod.json")) {
          // A Quilt-only mod: its manifest becomes a fabric.mod.json (same ids, entrypoints, mixins, widener), and the port
          // is a plain Fabric mod from here on. The original manifest rides along under another name, for the record.
          String synth = QuiltMeta.fabricJsonFrom(new String(raw, StandardCharsets.UTF_8));
          if (synth != null) { buffered.put("quilt.mod.json.original", raw); raw = synth.getBytes(StandardCharsets.UTF_8); name = "fabric.mod.json"; }
        }
        byte[] emit = raw;
        // Nested Jar-in-Jar dependencies get the FULL pipeline recursively — an untransformed
        // bundled dep (say, an intermediary-era cloth-config) otherwise ships inside a "ported"
        // jar and crashes the loader the moment no newer copy of that dep is installed.
        if (isNestedMod(name)) {
          try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fg-nested", ".jar");
            java.nio.file.Files.write(tmp, raw);
            // Quilt Loader ships MixinExtras itself and rejects a mod's bundled copy as a duplicate mod ("The solver returned a
            // solution with duplicate mods"); Fabric Loader merely picks the newer one. On a Quilt host the bundled copy is dropped.
            String nestedId = JarDeps.idOf(tmp);
            // Same for a bundled Fabric API module (an old fabric-networking-api-v1 inside Distant Horizons): Quilt's solver
            // sees two providers of one module and gives up ("Failed to pre-process a rule set"); the installed Fabric API wins.
            boolean bundledMixinExtras = nestedId != null && (nestedId.contains("mixinextras") || JarDeps.providesOf(tmp).contains("mixinextras"));
            boolean bundledFabricModule = nestedId != null && (nestedId.equals("fabric-api") || FabricMetaFixer.isFabricModuleId(nestedId));
            if (isQuiltHost() && (bundledMixinExtras || bundledFabricModule)) {
              java.nio.file.Files.deleteIfExists(tmp);
              droppedNested.add(name);
              System.err.println("[Fox-Grade] bundled " + name + " left out: the loader already provides " + (bundledMixinExtras ? "mixinextras" : nestedId) + " on Quilt");
              continue;
            }
            Outcome inner = transform(tmp, targetMc, rules, bridge, apiBridges, blocklist);
            java.nio.file.Files.deleteIfExists(tmp);
            emit = inner.outputBytes;
            classesRemapped += inner.classesRemapped;
            awFiles += inner.awFiles; awOwners += inner.awOwners; awDescs += inner.awDescs;
            refmapFiles += inner.refmapFiles; refmapHits += inner.refmapHits;
            mixinsStripped += inner.mixinsStripped; autoStripped += inner.autoStripped;
          } catch (Exception ex) {
            System.err.println("[Fox-Grade] bundled jar " + name + " could not be ported (" + ex + "); shipped unchanged");
            if (System.getenv("FOXGRADE_DEBUG_STRIP") != null) ex.printStackTrace();
          }
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
        } else if (name.endsWith(".json") && (name.startsWith("data/") || name.startsWith("assets/"))) {
          // 26.2 loads datapack and resource JSON with a STRICT parser; 1.21 accepted comments, trailing commas and the
          // like. Files that fail the strict read are re-serialised from a lenient parse; clean files stay byte-identical.
          byte[] strictened = JsonStrict.strictenIfNeeded(raw);
          if (name.contains("/worldgen/template_pool/")) {   // 26.2 caps pool element weights at 150; 1.21 packs leaned on YUNG's API lifting that
            byte[] poolSrc = strictened != null ? strictened : raw, pooled = TemplatePoolFix.clamp(poolSrc);
            if (pooled != poolSrc) strictened = pooled;
          }
          {   // vanilla ids the target renamed (chain → iron_chain): datapack JSON has no data version to fix it up
            byte[] idSrc = strictened != null ? strictened : raw, renamed = DataIdRenames.apply(idSrc);
            if (renamed != idSrc) strictened = renamed;
          }
          if (BiomeJsonFix.applies(name)) {   // 26.2 flattened a biome's carvers map into a plain carver set
            byte[] bSrc = strictened != null ? strictened : raw, fixed = BiomeJsonFix.apply(bSrc);
            if (fixed != bSrc) strictened = fixed;
          }
          if (strictened != null) { emit = strictened; jsonStrictened++; }
        } else if ((name.toLowerCase().endsWith(".accesswidener") || name.toLowerCase().endsWith(".aw") || name.toLowerCase().endsWith(".ct") || name.toLowerCase().endsWith(".classtweaker") || AccessTransformerRemapper.isTransformer(name)) && !mergedClasses.isEmpty()) {
          try {
            AccessWidenerRemapper.Names names = new AccessWidenerRemapper.Names() {
              @Override public String method(String o, String n, String d) { return remapper.mapMethodName(o, n, d); }
              @Override public String field(String o, String n, String d) { return remapper.mapFieldName(o, n, d); }
              @Override public String clazz(String o) { return remapper.mapClass(o); }
              @Override public String desc(String d) { try { return remapper.mapDescriptor(d); } catch (RuntimeException e) { return null; } }
              @Override public String fieldDesc(String o, String n, String d) {
                String owner = remapper.mapClass(o); String desc = remapper.mapDescriptor(d);
                Map<String, String[]> fr = apiBridges.fieldRedirects().get(owner);
                if (fr == null) return desc;
                for (String kind : new String[] {"get ", "getstatic "}) {
                  String[] to = fr.get(kind + n + ":" + desc);
                  if (to != null && to.length > 1 && (to[0].equals("holder") || to[0].equals("retype"))) return to[1];
                }
                return desc;
              }
            };
            if (AccessTransformerRemapper.isTransformer(name)) {
              // NeoForge/Forge access transformer: same job as a widener, different file format.
              AccessTransformerRemapper.Result at = AccessTransformerRemapper.rewrite(new String(raw, StandardCharsets.UTF_8), names);
              if (at.owners > 0 || at.members > 0) {
                emit = at.text.getBytes(StandardCharsets.UTF_8);
                awFiles++; awOwners += at.owners; awDescs += at.members;
              }
              buffered.put(name, emit);
              continue;
            }
            AccessWidenerRemapper.Result r = AccessWidenerRemapper.rewrite(new String(raw, StandardCharsets.UTF_8), mergedClasses, names);
            // Even a widener with no member lines needs its header namespace rewritten (Fabric refuses an
            // "intermediary" header on the unobfuscated 26.x runtime), so compare the text, not the hit counts.
            if (r.owners > 0 || r.descriptors > 0 || !r.text.equals(new String(raw, StandardCharsets.UTF_8))) { emit = r.text.getBytes(StandardCharsets.UTF_8); awFiles++; awOwners += r.owners; awDescs += r.descriptors; }
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
          {
            ClassLoader cl = TransformPipeline.class.getClassLoader();
            byte[] after = RemovedEventSynth.collect(emit,
                c -> verifier.knows(c) || ShimGenerator.SHIMS.containsKey(c) || cl.getResource(c + ".class") != null,
                (o, f) -> { try { Class.forName(o.replace('/', '.'), false, cl).getDeclaredField(f); return true; } catch (NoSuchFieldException nsf) { return false; } catch (Throwable t) { return true; } },
                removedEvents);
            if (after != emit) { emit = after; remapper.usedShims().add(RemovedEventSynth.DEAD_OWNER); strippedNames.add(name.substring(name.lastIndexOf('/') + 1).replace(".class", "") + " (registers for an event removed from Fabric API; it never fires)"); }
          }
          String slashClass = name.substring(0, name.length() - 6);
          // Manual mixin blocklist first.
          if (blocklist.containsKey(slashClass)) {
            var wanted = blocklist.get(slashClass);
            MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) ->
                (wanted == null && !mn.equals("<init>") && !mn.equals("<clinit>")) || (wanted != null && wanted.contains(mn)));
            if (strip.bytes != null) strippedByClass.computeIfAbsent(slashClass, k -> new HashSet<>()).addAll(strip.strippedKeys);
            if (strip.bytes != null) {
              emit = strip.bytes; mixinsStripped += strip.strippedNames.size();
              for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n);
            }
          }
          // Auto blocklist: strip handlers whose annotations reference a broken selector. The
          // annotation strings survive the ASM remap untouched (string values are not remapped),
          // so they still equal the refmap's raw keys collected in the pre-scan.
          java.util.List<String> mixinTargets = MixinTargets.of(emit);
          // No refmap (Mojang-mapped, or intermediary selectors Fabric remaps at runtime): translate the selectors ourselves so
          // they are judged like refmap values, and write the 26.2 form back so Mixin finds targets whose names changed.
          if (!mixinTargets.isEmpty() && !refmapByClass.containsKey(slashClass)) {
            Map<String, String> synth = new HashMap<>();
            for (String rawSel : AnnotationTargetScanner.selectorStrings(emit)) {
              if (rawSel.isEmpty()) continue;
              String full = rawSel.startsWith("L") && rawSel.indexOf(';') > 0 ? rawSel : "L" + mixinTargets.get(0) + ";" + rawSel;
              String mapped = MixinRefmapRemapper.translateSelector(full, bridge, rules.slashTable(), apiBridges);
              if (!rawSel.startsWith("L")) mapped = mapped.substring(mapped.indexOf(';') + 1);   // keep bare selectors bare
              if (!mapped.equals(rawSel)) synth.put(rawSel, mapped);
              String judged = rawSel.startsWith("L") ? mapped : "L" + mixinTargets.get(0) + ";" + mapped;
              if (auto.isLoaded() && auto.targetExists(judged) && arityChanged(rawSel, mapped)) changedKeysByClass.computeIfAbsent(slashClass, k -> new HashSet<>()).add(rawSel);
            }
            if (!synth.isEmpty()) { refmapByClass.put(slashClass, synth); }
          }
          // Injectors selected by bare name are matched by Mixin against the 26.2 method of that name; when its
          // parameters changed, the handler's mirrored parameters cannot line up. Strip those handlers.
          if (!mixinTargets.isEmpty() && auto.isLoaded()) {
            var bare = AnnotationTargetScanner.bareInjectMismatches(emit, mixinTargets,
                (t, n) -> {
                  Set<String> ms = auto.methodSet(t); if (ms == null) return Set.of();
                  // the 26.2 name may differ from the 1.21 one (RenderLayer.render → submit): follow the rename tables up the hierarchy
                  Set<String> names = new HashSet<>(); names.add(n);
                  for (String c = t, guard = ""; c != null && guard.length() < 32; c = verifier.superOf(c), guard += "x") {
                    Map<String, String> rn = apiBridges.renames().get(c); if (rn != null && rn.containsKey(n)) names.add(rn.get(n));
                    Map<String, String> mj = bridge.mojangMethodTable().get(c);
                    if (mj != null) for (var me : mj.entrySet()) if (me.getKey().startsWith(n + "(")) { String v = me.getValue(); int q = v.indexOf('('); names.add(q > 0 ? v.substring(0, q) : v); }
                  }
                  Set<String> r = new HashSet<>();
                  for (String m : ms) for (String nm : names) if (m.startsWith(nm + "(")) r.add(m);
                  return r;
                },
                n -> {
                  String m = bridge.globalMethodTable().get(n);
                  if (m == null) for (var ge : bridge.globalMethodTable().entrySet()) if (ge.getKey().startsWith(n + "(")) { m = ge.getValue(); break; }
                  if (m == null) m = n;
                  int p = m.indexOf('('); return p > 0 ? m.substring(0, p) : m;
                });
            if (!bare.isEmpty()) {
              Set<String> names = new HashSet<>();
              for (var h : bare) names.add(h.name + h.desc);
              MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) -> names.contains(mn + md));
            if (strip.bytes != null) strippedByClass.computeIfAbsent(slashClass, k -> new HashSet<>()).addAll(strip.strippedKeys);
              if (strip.bytes != null) {
                emit = strip.bytes; autoStripped += strip.strippedNames.size();
                for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n + " (target's parameters changed)");
              }
            }
          }
          if (!mixinTargets.isEmpty() && refmapByClass.containsKey(slashClass)) {
            Map<String, String> m = refmapByClass.get(slashClass);
            boolean synthesized = !hasRefmapFile;   // real refmaps are rewritten by the refmap pass; synthesized ones are written into the class
            if (synthesized) emit = AnnotationTargetScanner.rewriteSelectors(emit, m);
          }
          Set<String> broken = brokenKeysByClass.getOrDefault(slashClass, Set.of());
          Set<String> changed = changedKeysByClass.getOrDefault(slashClass, Set.of());
          if (!broken.isEmpty() || !changed.isEmpty()) {
            var handlers = AnnotationTargetScanner.scan(emit, broken, changed);
            if (!handlers.isEmpty()) {
              Set<String> names = new HashSet<>();
              for (var h : handlers) names.add(h.name + h.desc);
              MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) -> names.contains(mn + md));
            if (strip.bytes != null) strippedByClass.computeIfAbsent(slashClass, k -> new HashSet<>()).addAll(strip.strippedKeys);
              if (strip.bytes != null) {
                emit = strip.bytes; autoStripped += strip.strippedNames.size();
                for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n + (changed.isEmpty() ? " (target no longer exists)" : " (target gone or its parameters changed)"));
              }
            }
          }
          // A mixin whose target class no longer exists cannot apply at all; Mixin would then refuse
          // every later load of the mixin class. Deregister it from its config.
          if (!mixinTargets.isEmpty()) {                                       // failed injections become warnings, not crashes
            java.util.List<String> softened = new java.util.ArrayList<>();
            emit = MixinRequireZeroer.zero(emit, softened);
            for (String h : softened) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "." + h);
            mixinClasses.add(slashClass);
          }
          org.objectweb.asm.ClassReader mixinReader = mixinTargets.isEmpty() ? null : new org.objectweb.asm.ClassReader(emit);
          boolean mixinIsInterface = mixinReader != null && (mixinReader.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
          String mixinSuper = mixinReader == null ? null : mixinReader.getSuperName();
          for (String target : mixinTargets) {
            if (!PortVerifier.isGameClass(target)) continue;
            boolean gone = !verifier.knows(target);
            boolean kindFlipped = !gone && !mixinIsInterface && Boolean.TRUE.equals(verifier.isInterface(target));   // Mixin: "target type mismatch"
            // Mixin also refuses a mixin whose declared superclass is not in the target's hierarchy any more
            // (AxeItem no longer extends DiggerItem): "Super class X of Y was not found in the hierarchy of target"
            boolean superLost = false;
            if (!gone && mixinSuper != null && PortVerifier.isGameClass(mixinSuper) && !mixinSuper.equals("java/lang/Object")) {
              // Mixin walks the target's PARENT chain: a superclass that vanished from 26.2 (DiggerItem), or that is the
              // target itself (EffectRenderingInventoryScreen folded into AbstractContainerScreen), is lost either way.
              superLost = true;
              if (verifier.knows(mixinSuper))
                for (String c = verifier.superOf(target), guard = ""; c != null && guard.length() < 48; c = verifier.superOf(c), guard += "x") if (c.equals(mixinSuper)) { superLost = false; break; }
            }
            if (gone || kindFlipped || superLost) {
              fatalMixins.add(slashClass);
              emit = MixinNeutralizer.neutralize(emit);   // loadable as a plain class; invokers return defaults
              relocated.put(slashClass, slashClass.contains("/mixin/") ? slashClass.replace("/mixin/", "/mixinfg/") : slashClass + "_fg");
              strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + " (whole mixin: target " + target.substring(target.lastIndexOf('/') + 1) + (gone ? " no longer exists)" : kindFlipped ? " became an interface)" : " no longer extends " + mixinSuper.substring(mixinSuper.lastIndexOf('/') + 1) + ")"));
              break;
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
                emit = MixinNeutralizer.neutralize(emit);   // deregistered, so it must be loadable as a plain class if mod code references it
                relocated.put(slashClass, slashClass.contains("/mixin/") ? slashClass.replace("/mixin/", "/mixinfg/") : slashClass + "_fg");
              } else if (!scan2.strippableMethods.isEmpty()) {
                Set<String> names2 = new HashSet<>();
                for (var h : scan2.strippableMethods) names2.add(h.name + h.desc);
                MethodStripper.Result strip = MethodStripper.strip(emit, (mn, md) -> names2.contains(mn + md));
            if (strip.bytes != null) strippedByClass.computeIfAbsent(slashClass, k -> new HashSet<>()).addAll(strip.strippedKeys);
                if (strip.bytes != null) {
                  emit = strip.bytes; autoStripped += strip.strippedNames.size();
                  for (String n : strip.strippedNames) strippedNames.add(slashClass.substring(slashClass.lastIndexOf('/') + 1) + "#" + n + " (shadowed or accessed member is gone)");
                }
              }
            } catch (Exception ignore) { }
          }
          if (verifier.isActive()) { try { verifier.scan(emit); } catch (Exception ignore) { } }
        }
        buffered.put(name, emit);
      }
      tClasses = System.nanoTime();
      // Callback types 26.2's Fabric API dropped: synthesise them so registration is a no-op instead of a crash.
      for (var re : removedEvents.entrySet()) {
        String cls = re.getKey();
        if (buffered.containsKey(cls + ".class")) continue;
        buffered.put(cls + ".class", remapper.remap(RemovedEventSynth.synthesize(cls, re.getValue())));
        remapper.usedShims().add(RemovedEventSynth.DEAD_OWNER);
        strippedNames.add(cls.substring(cls.lastIndexOf('/') + 1) + " (event removed from Fabric API; its listeners never fire)");
      }
      // Cross-class closure: a mixin method that calls a member another mixin class lost (a subclass mixin using the
      // shadow its parent declared) can never resolve — Mixin refuses the whole target class. Strip it too; repeat
      // until nothing new falls out.
      for (int round = 0; round < 4 && !strippedByClass.isEmpty(); round++) {
        boolean changed = false;
        // callers live in mixin classes and their helpers (inner classes, accessors) — anything in a mixin package
        java.util.List<String> scan = new java.util.ArrayList<>();
        for (String k : buffered.keySet()) if (k.endsWith(".class") && (mixinClasses.contains(k.substring(0, k.length() - 6)) || k.toLowerCase(java.util.Locale.ROOT).contains("mixin"))) scan.add(k.substring(0, k.length() - 6));
        for (String cls : scan) {
          byte[] b = buffered.get(cls + ".class");
          if (b == null) continue;
          Set<String> callers = MethodStripper.callersOf(b, strippedByClass);
          if (callers.isEmpty()) continue;
          MethodStripper.Result strip = MethodStripper.strip(b, (mn, md) -> callers.contains(mn + md));
          if (strip.bytes == null) continue;
          buffered.put(cls + ".class", strip.bytes); changed = true; autoStripped += strip.strippedNames.size();
          strippedByClass.computeIfAbsent(cls, k -> new HashSet<>()).addAll(strip.strippedKeys);
          for (String nm : strip.strippedNames) strippedNames.add(cls.substring(cls.lastIndexOf('/') + 1) + "#" + nm + " (calls a member removed from another mixin)");
        }
        if (!changed) break;
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
      // A mixin may extend another mixin. Mixin requires that superclass to itself be a mixin on a supertype of the
      // target, so once a parent is neutralised the child can never apply either — it fails at apply time with
      // "Super class ... was not found in the hierarchy of target". Close the fatal set over subclasses (transitively)
      // and neutralise them the same way, so the whole broken branch comes out cleanly instead of at runtime.
      for (boolean grew = true; grew; ) {
        grew = false;
        for (var be : new java.util.ArrayList<>(buffered.entrySet())) {
          String entryName = be.getKey();
          if (!entryName.endsWith(".class")) continue;
          String cls = entryName.substring(0, entryName.length() - 6);
          if (fatalMixins.contains(cls) || !mixinClasses.contains(cls)) continue;
          String sup;
          try { sup = new org.objectweb.asm.ClassReader(be.getValue()).getSuperName(); } catch (Exception bad) { continue; }
          if (sup == null) continue;
          // the parent may already carry its relocated name at this point
          String parent = sup;
          for (var re : relocated.entrySet()) if (re.getValue().equals(sup)) { parent = re.getKey(); break; }
          if (!fatalMixins.contains(parent)) continue;
          fatalMixins.add(cls);
          be.setValue(MixinNeutralizer.neutralize(be.getValue()));
          relocated.put(cls, cls.contains("/mixin/") ? cls.replace("/mixin/", "/mixinfg/") : cls + "_fg");
          strippedNames.add(cls.substring(cls.lastIndexOf('/') + 1) + " (whole mixin: extends the neutralised "
              + parent.substring(parent.lastIndexOf('/') + 1) + ")");
          grew = true;
        }
      }
      // A mixin config that names a class the jar does not contain can only fail ("The specified mixin ... was not
      // found"), and it takes the whole config down with it. Mods ship these deliberately — optional compat mixins for
      // a mod that may not be installed, normally gated by a config plugin that does not survive the port. Drop the
      // entries that point at nothing; every real mixin in the config keeps applying.
      {
        java.util.Set<String> presentClasses = new HashSet<>();
        for (String k : buffered.keySet()) if (k.endsWith(".class")) presentClasses.add(k.substring(0, k.length() - 6));
        for (var entry : buffered.entrySet()) {
          String n = entry.getKey();
          if (!n.endsWith(".json") || !n.contains("mixin")) continue;
          try {
            JsonObject cfg = new Gson().fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
            if (cfg == null || !cfg.has("package")) continue;
            String pkg = cfg.get("package").getAsString().replace('.', '/');
            boolean changed = false;
            for (String listKey : new String[] {"mixins", "client", "server"}) {
              if (!cfg.has(listKey) || !cfg.get(listKey).isJsonArray()) continue;
              com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
              for (var el : cfg.getAsJsonArray(listKey)) {
                String cls = pkg + "/" + el.getAsString().replace('.', '/');
                if (!presentClasses.contains(cls)) {
                  changed = true;
                  strippedNames.add(el.getAsString() + " (listed in " + n + " but not in the jar)");
                  continue;
                }
                kept.add(el);
              }
              if (changed) cfg.add(listKey, kept);
            }
            if (changed) entry.setValue((GSON.toJson(cfg) + "\n").getBytes(StandardCharsets.UTF_8));
          } catch (Exception notAMixinConfig) { }
        }
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
            // Deregister ONLY the fatal mixins; every other mixin of the mod keeps applying. (Emptying the
            // whole config, as an earlier version did, silently disabled the mod and broke every accessor
            // its code referenced.)
            boolean changed = false;
            for (String listKey : new String[]{"mixins", "client", "server"}) {
              if (!cfg.has(listKey) || !cfg.get(listKey).isJsonArray()) continue;
              com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
              for (var el : cfg.getAsJsonArray(listKey)) {
                if (fatalMixins.contains(pkg + "/" + el.getAsString().replace('.', '/'))) { changed = true; continue; }
                kept.add(el);
              }
              if (changed) cfg.add(listKey, kept);
            }
            JsonObject inj = cfg.has("injectors") && cfg.get("injectors").isJsonObject() ? cfg.getAsJsonObject("injectors") : new JsonObject();
            if (!inj.has("defaultRequire") || inj.get("defaultRequire").getAsInt() != 0) { inj.addProperty("defaultRequire", 0); cfg.add("injectors", inj); changed = true; }
            if (changed) entry.setValue((GSON.toJson(cfg) + "\n").getBytes(StandardCharsets.UTF_8));
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
      java.util.LinkedHashSet<String> standIns = new java.util.LinkedHashSet<>();
      for (String missing : new java.util.ArrayList<>(verifier.missing())) {
        // A shim with no build for this target stays missing on purpose: the report says so, which a person can act
        // on, and that beats injecting a class compiled against a different Minecraft.
        if (ShimGenerator.SHIMS.containsKey(missing) && !ShimGenerator.unavailableHere(missing)) {
          wanted.add(missing); verifier.missing().remove(missing);
        } else if (apiBridges.standIns().containsKey(missing)) {
          // No reviewed shim, but the target deleted this type outright and its supertype survives, so an empty
          // stand-in lets the rest of the mod load with just this feature inert.
          wanted.add(missing); standIns.add(missing); verifier.missing().remove(missing);
          strippedNames.add(missing.substring(missing.lastIndexOf('/') + 1) + " (deleted in " + targetMc + "; stood in empty)");
        }
      }
      wanted.addAll(remapper.usedShims());
      // Stand-ins are found by looking, not by asking the verifier. The verifier compares against Minecraft's class
      // inventory, so it has no opinion about NeoForge's own API and never reports a deleted event class as missing.
      // The stand-in table is only ever built from classes the target definitely does not have, so a mod mentioning
      // one at all is a mod that will not load.
      if (!apiBridges.standIns().isEmpty()) {
        for (var entry : buffered.entrySet()) {
          if (!entry.getKey().endsWith(".class")) continue;
          String pool = new String(entry.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1);
          for (String deleted : apiBridges.standIns().keySet()) {
            if (!wanted.contains(deleted) && pool.contains(deleted)) {
              wanted.add(deleted); standIns.add(deleted);
              strippedNames.add(deleted.substring(deleted.lastIndexOf('/') + 1) + " (deleted in " + targetMc + "; stood in empty)");
            }
          }
        }
      }
      java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(wanted);
      while (!queue.isEmpty()) {
        String w = queue.poll();
        for (String dep : ShimGenerator.SHIM_DEPS.getOrDefault(w, java.util.List.of())) if (wanted.add(dep)) queue.add(dep);
        for (String k : ShimGenerator.SHIMS.keySet()) if (k.startsWith(w + "$") && wanted.add(k)) queue.add(k);   // inner shims travel with their outer
      }
      for (String d : remapper.droppedOverrides()) { strippedNames.add(d + " (final in 26.2)"); autoStripped++; }
      Map<String, String> shimMap = new HashMap<>();
      // On a Quilt host the loader's own API classes are the real thing; a port must not ship stand-ins for them.
      if (isQuiltHost()) wanted.removeIf((c) -> c.startsWith("org/quiltmc/loader/api/"));
      for (String shimCls : wanted) {
        String nsName = namespacedShim(shimCls, shimNs, standIns);
        if (!nsName.equals(shimCls)) shimMap.put(shimCls, nsName);
      }
      for (String shimCls : wanted) {
        var shim = ShimGenerator.SHIMS.get(shimCls);
        FabricApiBridges.StandIn si = shim == null ? apiBridges.standIns().get(shimCls) : null;
        if (shim == null && si == null) continue;
        String entryName = shimMap.getOrDefault(shimCls, shimCls) + ".class";
        if (buffered.containsKey(entryName)) continue;
        byte[] bytes;
        try {
          bytes = shim != null ? shim.get()
              : ShimGenerator.standIn(shimMap.getOrDefault(shimCls, shimCls), si.superName(), si.interfaces(), si.methods());
        } catch (RuntimeException noBuildForThisTarget) {
          // One shim that cannot be produced is a gap in the port, not a reason to abandon it.
          verifier.missing().add(shimCls);
          continue;
        }
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
      tShims = System.nanoTime();
      byte[] metaBytes = buffered.get("fabric.mod.json");
      if (metaBytes != null) {
        try {
          JsonObject meta = new Gson().fromJson(new String(metaBytes, StandardCharsets.UTF_8), JsonObject.class);
          JsonObject custom = meta.has("custom") && meta.get("custom").isJsonObject() ? meta.getAsJsonObject("custom") : new JsonObject();
          JsonObject fg = custom.has("foxgrade") && custom.get("foxgrade").isJsonObject() ? custom.getAsJsonObject("foxgrade") : new JsonObject();
          fg.addProperty("source", src.getFileName().toString());
          if (!droppedNested.isEmpty() && meta.has("jars") && meta.get("jars").isJsonArray()) {
            com.google.gson.JsonArray keep = new com.google.gson.JsonArray();
            for (JsonElement j : meta.getAsJsonArray("jars")) {
              String file = j.isJsonObject() && j.getAsJsonObject().has("file") ? j.getAsJsonObject().get("file").getAsString() : null;
              if (file == null || !droppedNested.contains(file)) keep.add(j);
            }
            meta.add("jars", keep);
          }
          byte[] quiltOrig = buffered.get("quilt.mod.json.original");
          if (quiltOrig != null) {   // Quilt-only source: ship a 26.2 quilt.mod.json as well, so Quilt Loader still sees a Quilt mod
            String qj = QuiltMeta.quiltJsonFor(new String(quiltOrig, StandardCharsets.UTF_8), meta);
            if (qj != null) { buffered.put("quilt.mod.json", qj.getBytes(StandardCharsets.UTF_8)); buffered.remove("quilt.mod.json.original"); }
          }
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
          buffered.put(PORT_REPORT, (GSON.toJson(fg) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
      } else {
        // A mod with no fabric.mod.json — every NeoForge mod — still gets a report. The panel's promise is that it
        // says what was changed, what was turned off and why; without this the promise simply is not kept on
        // NeoForge, where the report had nowhere to live because it was riding inside Fabric's manifest.
        try {
          JsonObject fg = new JsonObject();
          fg.addProperty("source", src.getFileName().toString());
          fg.addProperty("pastPort", targetMc);
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
          buffered.put(PORT_REPORT, (GSON.toJson(fg) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
      }
      if (!relocated.isEmpty()) {
        Map<String, byte[]> moved = new java.util.LinkedHashMap<>();
        for (var entry : buffered.entrySet()) {
          String n = entry.getKey(); byte[] b = entry.getValue();
          if (n.endsWith(".class")) {
            b = ShimGenerator.renameClasses(b, relocated);
            String cls = n.substring(0, n.length() - 6);
            if (relocated.containsKey(cls)) n = relocated.get(cls) + ".class";
          }
          moved.put(n, b);
        }
        buffered.clear(); buffered.putAll(moved);
      }
      for (var entry : buffered.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    for (String flip : remapper.flips()) if (flip.contains("cannot be")) verifier.missing().add(flip.substring(0, flip.indexOf(" (")));
    long tEnd = System.nanoTime();
    lastTimings = String.format("prescan %dms, classes %dms, post+shims %dms, meta+write %dms", (tPrescan - tStart) / 1_000_000, (tClasses - tPrescan) / 1_000_000, (tShims - tClasses) / 1_000_000, (tEnd - tShims) / 1_000_000);
    Outcome o = new Outcome(sink.toByteArray(), metaFixed, awFiles, awOwners, awDescs, refmapFiles, refmapHits, classesRemapped, mixinsStripped, autoStripped, verifier.missing());
    o.deregisteredMixins.addAll(fatalMixins);
    return o;
  }

  // Where an injected shim class is written inside the ported jar.
  //
  // foxgrade/shim/* always gets a per-port namespace so two ports never share one copy. A shim named for the class it
  // stands in for (net/minecraft/util/Tuple, com/mojang/blaze3d/vertex/Tesselator) normally keeps that exact name, so
  // the mod's existing references resolve with nothing rewritten.
  //
  // NeoForge cannot have that. Its loader puts every mod jar in its own JPMS module, and two modules may not both own
  // a package, so a jar carrying net/minecraft/... or com/mojang/... is rejected before any mod code runs:
  //   Module minecraft contains package com.mojang.blaze3d.vertex, module <mod> exports package ... to minecraft
  // On that host the MC-named shims move under the port's own namespace as well. Nothing is lost by it: the second
  // pass below rewrites the mod's call sites to the moved names, and every one of these shims stands in for a class
  // the target deleted, so no vanilla code exists that could still expect the original name.
  private static String namespacedShim(String shimCls, String ns, java.util.Set<String> standIns) {
    if (shimCls.startsWith("foxgrade/shim/")) return "foxgrade/shim/" + ns + "/" + shimCls.substring("foxgrade/shim/".length());
    if (!isNeoForgeHost()) return shimCls;
    // Only a class Fox-Grade actually writes into the jar may move. A redirect can also name a class that really
    // exists on the target — NeoForge's own FMLEnvironment is one — and relocating that would rewrite a working call
    // into a reference to a class nobody emits.
    if (!ShimGenerator.SHIMS.containsKey(shimCls) && !standIns.contains(shimCls)) return shimCls;
    return "foxgrade/shim/" + ns + "/host/" + shimCls;
  }


  /** True when both selectors are method selectors and the target now takes a different number of parameters. */
  static boolean arityChanged(String original, String resolved) {
    int po = original.indexOf('('), pr = resolved.indexOf('(');
    if (po < 0 || pr < 0) return false;
    try {
      return org.objectweb.asm.Type.getArgumentTypes(original.substring(po)).length
          != org.objectweb.asm.Type.getArgumentTypes(resolved.substring(pr)).length;
    } catch (RuntimeException malformed) { return false; }
  }

  private static byte[] readAll(ZipFile z, ZipEntry e) throws IOException {
    try (InputStream is = z.getInputStream(e)) { return is.readAllBytes(); }
  }
}
