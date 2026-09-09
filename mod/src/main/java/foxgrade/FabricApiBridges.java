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
  // owner → name+desc → newDesc: the same method with a WIDER parameter type (joml Matrix4f →
  // Matrix4fc). Rewritten in place at every call site and method handle; the JVM accepts the
  // narrower value where the wider type is expected, so no shim is involved.
  private final Map<String, Map<String, String>> descWidenings;
  // Like callRedirects, but applied ONLY to method handles inside invokedynamic (method
  // references), and the lambda's instantiated type is rewritten to the shim's descriptor. For
  // APIs whose return type no longer exists as a class (GameRenderer::getPositionTexShader →
  // ShaderInstance): a direct call would leave a dead type in the caller's frames, a method
  // reference erases it away.
  private final Map<String, Map<String, String[]>> handleRedirects;
  // owner → name+desc → adapter: a call whose ARGUMENTS were repacked into an event object
  // (keyPressed(int,int,int) → keyPressed(KeyEvent)). `pack` is a static shim taking the first
  // K old arguments and returning the new object; remaining old arguments follow it; `extras`
  // are int constants appended (a trailing boolean the new signature grew).
  // `args`: full new-argument recipe (each entry a source: "oN" old arg N, "this", or a static call
  // ["static", owner, name, desc, sources…]); when present, `pack`/`extras` are ignored.
  public record CallAdapter(String newName, String newDesc, String[] pack, int[] extras, java.util.List<String[]> args, String[] convert) { }
  private final Map<String, Map<String, CallAdapter>> callAdapters;
  // A mod class OVERRIDING an old-signature callback (keyPressed(III)Z) gets the new-signature
  // method synthesised, delegating to the old one: each `unpack` entry is an accessor on the
  // first new parameter ([owner, name, desc]), a pass-through of new parameter N ("pN"), or an
  // int constant ("0"). Without this the game never calls the mod's handler again.
  // `convert`: a static call applied to the old method's return value to produce the new return type
  // (Item.use returning InteractionResultHolder → InteractionResult).
  public record OverrideAdapter(String oldName, String oldDesc, String newName, String newDesc, java.util.List<String[]> unpack, java.util.List<String[]> after, String[] convert) { }
  // name+desc → static hooks: synthesise the method (if absent) as super-call-then-hooks.
  public record SuperHook(String name, String desc, java.util.List<String[]> hooks) { }
  // name+desc → static call producing the return value; synthesised only when nothing in the
  // class's chain implements it (an abstract declaration up the chain does not count).
  public record Synth(String name, String desc, String[] call) { }
  private final java.util.List<OverrideAdapter> overrideAdapters;
  // name+desc → [shimOwner, shimName, shimDesc]: a static call inserted at the ENTRY of any mod
  // method with that signature, passing its first parameter. How a GUI shim learns which frame
  // is being drawn (render(GuiGraphicsExtractor,…) → GuiCompat.current(extractor)).
  private final Map<String, String[]> entryHooks;
  private final java.util.List<SuperHook> superHooks = new java.util.ArrayList<>();
  private final java.util.List<Synth> synths = new java.util.ArrayList<>();
  private final Map<String, Map<String, String>> samRenames = new HashMap<>();   // interface → old SAM name → new
  public java.util.List<SuperHook> superHooks() { return superHooks; }
  public java.util.List<Synth> synths() { return synths; }
  public Map<String, Map<String, String>> samRenames() { return samRenames; }
  private final Map<String, java.util.List<String[]>> inheritedRenamesByAncestor = new HashMap<>();
  public Map<String, java.util.List<String[]>> inheritedRenamesByAncestor() { return inheritedRenamesByAncestor; }
  private final Map<String, String> classRenames;    // third-party class renames (slash form)
  private final Map<String, StandIn> standIns = new HashMap<>();

  /** A deleted type Fox-Grade can supply an empty replacement for, because its supertype still exists. */
  public record StandIn(String superName, java.util.List<String> interfaces, java.util.List<String> methods) { }
  // owner → oldCtorDesc → adapter: same-arity constructor signature changes, adapted per-slot.
  public record CtorTransform(int slot, String viaOwner, String viaName, String viaDesc) { }
  // args: full argument recipes ("oN" / ["static",o,n,d,src…] / ["conv",src,op] / ["cast",src,type]) replacing the
  // per-slot transforms; factory: [owner,name,desc] of a static method that REPLACES the constructor
  // (new ResourceLocation(s) → Identifier.parse(s)) — the uninitialised refs are popped.
  public record CtorAdapter(String newDesc, java.util.List<CtorTransform> transforms, java.util.List<String[]> args, String[] factory) { }
  private final Map<String, Map<String, CtorAdapter>> ctorAdapters;
  // name+desc → newName, applied to MOD-owned classes: a mod class overriding a renamed MC
  // method must have its DECLARATION renamed too, or the JVM sees an abstract method un-overridden.
  private final Map<String, String> inheritedRenames;

  private FabricApiBridges(Map<String, Map<String, String>> renames, Map<String, Map<String, String[]>> callRedirects,
                           Map<String, String> classRenames, Map<String, Map<String, CtorAdapter>> ctorAdapters,
                           Map<String, String> inheritedRenames, Map<String, Map<String, String[]>> fieldRedirects,
                           Map<String, Map<String, String>> descWidenings, Map<String, Map<String, String[]>> handleRedirects,
                           Map<String, Map<String, CallAdapter>> callAdapters, java.util.List<OverrideAdapter> overrideAdapters,
                           Map<String, String[]> entryHooks, Map<String, java.util.List<String[]>> byAncestor) {
    this.entryHooks = entryHooks; this.inheritedRenamesByAncestor.putAll(byAncestor);
    this.renames = renames; this.callRedirects = callRedirects; this.classRenames = classRenames;
    this.ctorAdapters = ctorAdapters; this.inheritedRenames = inheritedRenames; this.fieldRedirects = fieldRedirects;
    this.descWidenings = descWidenings; this.handleRedirects = handleRedirects;
    this.callAdapters = callAdapters; this.overrideAdapters = overrideAdapters;
  }
  public Map<String, Map<String, String>> descWidenings() { return descWidenings; }
  public Map<String, Map<String, String[]>> handleRedirects() { return handleRedirects; }
  public Map<String, Map<String, CallAdapter>> callAdapters() { return callAdapters; }
  public java.util.List<OverrideAdapter> overrideAdapters() { return overrideAdapters; }
  public Map<String, String[]> entryHooks() { return entryHooks; }

  public Map<String, Map<String, CtorAdapter>> ctorAdapters() { return ctorAdapters; }
  public Map<String, String> inheritedRenames() { return inheritedRenames; }

  public Map<String, Map<String, String>> renames() { return renames; }
  public Map<String, Map<String, String[]>> callRedirects() { return callRedirects; }
  public Map<String, Map<String, String[]>> fieldRedirects() { return fieldRedirects; }
  public Map<String, String> classRenames() { return classRenames; }

  /** Deleted API types that get an empty stand-in so the rest of the mod can load: name -> {super, interfaces,
   *  methods}. See the {@code standIns} note in the NeoForge bridge table. */
  public Map<String, StandIn> standIns() { return standIns; }
  public int size() {
    int n = 0;
    for (var m : renames.values()) n += m.size();
    return n;
  }

  // Loads the shipped table plus, if present, a user extension at
  // <gamedir>/fox-grade.api-bridges.json (same shape). Entries in the user file win on collision.
  public static FabricApiBridges load(Path gameDir) throws IOException {
    return load(gameDir, null);
  }

  /** As above, and when {@code targetMc} is given, drops any redirect the target does not need.
   *
   *  <p>These tables were written for one version. A redirect exists because that version removed something — a
   *  class, a method — and the call has to go somewhere else instead. Aimed at a version that still has the thing,
   *  the redirect is not a repair but damage: it takes a call that would have worked and sends it to a stand-in.
   *
   *  <p>MultiBufferSource is the case. 26.2 deleted it, so the tables redirect its calls into shims; 26.1.2 still has
   *  it, complete with the immediateWithBuffers the mod is asking for, and redirecting anyway is what turned AppleSkin,
   *  Cloth Config and FerriteCore from passing on 26.1.2 into NoSuchMethodError.
   *
   *  <p>On the version the tables were written for this changes nothing, which is the point: every member a redirect
   *  names is one that version has already removed, so none of them survive the check there. */
  public static FabricApiBridges load(Path gameDir, String targetMc) throws IOException {
    Map<String, Map<String, String>> renames = new HashMap<>();
    Map<String, Map<String, String[]>> redirects = new HashMap<>();
    Map<String, String> classRenames = new HashMap<>();
    Map<String, Map<String, CtorAdapter>> ctorAdapters = new HashMap<>();
    Map<String, String> inheritedRenames = new HashMap<>();
    Map<String, Map<String, String[]>> fieldRedirects = new HashMap<>();
    Map<String, Map<String, String>> descWidenings = new HashMap<>();
    Map<String, Map<String, String[]>> handleRedirects = new HashMap<>();
    Map<String, Map<String, CallAdapter>> callAdapters = new HashMap<>();
    java.util.List<OverrideAdapter> overrideAdapters = new java.util.ArrayList<>();
    Map<String, String[]> entryHooks = new HashMap<>();
    Map<String, java.util.List<String[]>> byAncestor = new HashMap<>();
    Map<String, StandIn> standInsL = new HashMap<>();
    java.util.List<SuperHook> superHooksL = new java.util.ArrayList<>(); java.util.List<Synth> synthsL = new java.util.ArrayList<>(); Map<String, Map<String, String>> samL = new HashMap<>();
    Extra extra = new Extra(descWidenings, handleRedirects, callAdapters, overrideAdapters, entryHooks, byAncestor, superHooksL, synthsL, samL);
    try (InputStream shipped = FabricApiBridges.class.getResourceAsStream("/foxgrade/fabric-api-bridges.json")) {
      if (shipped != null) merge(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, extra, new String(shipped.readAllBytes()));
    }
    // NeoForge ships its loader and its modding API as one product and reshapes both between Minecraft versions, so a
    // NeoForge mod hits NeoForge's own moved classes before it reaches a Minecraft one. Its table merges on top.
    // Gated on the host, not merged everywhere. These entries are written in NeoForge's own naming and describe
    // NeoForge's own moves; loading them on Fabric would put a second, unrelated set of substitutions in front of a
    // Fabric port for no benefit. The Fabric results are measured, and nothing here is allowed to disturb them.
    if (TransformPipeline.isNeoForgeHost()) {
      try (InputStream neo = FabricApiBridges.class.getResourceAsStream("/foxgrade/neoforge-api-bridges.json")) {
        if (neo != null) merge(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, extra, new String(neo.readAllBytes()), standInsL);
      }
    }
    Path user = gameDir.resolve("fox-grade.api-bridges.json");
    if (Files.exists(user)) merge(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, extra, Files.readString(user));
    if (targetMc != null) {
      Map<String, java.util.Set<String>> present = TargetInventory.membersByClass(targetMc);
      if (!present.isEmpty()) {
        dropRedirectsTargetDoesNotNeed(redirects, present);
        dropRedirectsTargetDoesNotNeed(fieldRedirects, present);
      }
    }
    FabricApiBridges b = new FabricApiBridges(renames, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects,
        descWidenings, handleRedirects, callAdapters, overrideAdapters, entryHooks, byAncestor);
    b.superHooks.addAll(superHooksL); b.synths.addAll(synthsL); b.samRenames.putAll(samL);
    b.standIns.putAll(standInsL);
    return b;
  }

  /** Removes redirects whose owner still declares the member being redirected on this target. */
  private static void dropRedirectsTargetDoesNotNeed(Map<String, Map<String, String[]>> table,
                                                     Map<String, java.util.Set<String>> present) {
    for (var owner : new java.util.ArrayList<>(table.keySet())) {
      java.util.Set<String> declared = present.get(owner);
      if (declared == null) continue;                    // the owner itself is gone: every redirect on it is needed
      Map<String, String[]> members = table.get(owner);
      members.keySet().removeIf((key) -> declared.contains(memberSignatureOf(key)));
      if (members.isEmpty()) table.remove(owner);
    }
  }

  /** The member signature a redirect key names, in the inventory's own shape.
   *
   *  <p>The comparison has to be on the whole signature, not the name. A redirect is not only for a member that
   *  vanished — it is also for one whose shape changed, and those keep their name. Matching by name alone dropped
   *  a redirect that a 26.2 port needed and silently stopped shipping the shim behind it.
   *
   *  <p>Field keys carry a verb ("get noCulling:Z", "getstatic SUCCESS:Lx;"); method keys do not. Strip the verb and
   *  what is left is exactly what the inventory records. */
  private static String memberSignatureOf(String key) {
    int space = key.indexOf(' ');
    return space < 0 ? key : key.substring(space + 1);
  }

  private static void merge(Map<String, Map<String, String>> into, Map<String, Map<String, String[]>> redirects,
                            Map<String, String> classRenames, Map<String, Map<String, CtorAdapter>> ctorAdapters,
                            Map<String, String> inheritedRenames, Map<String, Map<String, String[]>> fieldRedirects, Extra extra, String json) {
    merge(into, redirects, classRenames, ctorAdapters, inheritedRenames, fieldRedirects, extra, json, new HashMap<>());
  }

  private static void merge(Map<String, Map<String, String>> into, Map<String, Map<String, String[]>> redirects,
                            Map<String, String> classRenames, Map<String, Map<String, CtorAdapter>> ctorAdapters,
                            Map<String, String> inheritedRenames, Map<String, Map<String, String[]>> fieldRedirects, Extra extra, String json,
                            Map<String, StandIn> standIns) {
    JsonObject o = new Gson().fromJson(json, JsonObject.class);
    if (o == null) return;
    if (o.has("standIns") && o.get("standIns").isJsonObject()) {
      for (var e : o.getAsJsonObject("standIns").entrySet()) {
        if (!e.getValue().isJsonObject()) continue;
        JsonObject v = e.getValue().getAsJsonObject();
        java.util.List<String> ifaces = new java.util.ArrayList<>(), methods = new java.util.ArrayList<>();
        if (v.has("interfaces")) for (com.google.gson.JsonElement x : v.getAsJsonArray("interfaces")) ifaces.add(x.getAsString());
        if (v.has("methods")) for (com.google.gson.JsonElement x : v.getAsJsonArray("methods")) methods.add(x.getAsString());
        standIns.put(e.getKey().replace('.', '/'), new StandIn(v.get("super").getAsString(), ifaces, methods));
      }
    }
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
          if (a.has("transforms")) for (var t : a.getAsJsonArray("transforms")) {
            JsonObject to = t.getAsJsonObject();
            var via = to.getAsJsonArray("via");
            ts.add(new CtorTransform(to.get("slot").getAsInt(),
                via.get(0).getAsString(), via.get(1).getAsString(), via.get(2).getAsString()));
          }
          java.util.List<String[]> cargs = null;
          if (a.has("args")) { cargs = new java.util.ArrayList<>(); for (var u : a.getAsJsonArray("args")) cargs.add(strs(u)); }
          String[] factory = a.has("factory") ? strs(a.get("factory")) : null;
          map.put(m.getKey(), new CtorAdapter(a.has("newDesc") ? a.get("newDesc").getAsString() : (factory != null ? factory[2] : m.getKey()), ts, cargs, factory));
        }
      }
    }
    readTriples(o, "handleRedirects", extra.handleRedirects());
    if (o.has("inheritedRenamesByAncestor") && o.get("inheritedRenamesByAncestor").isJsonObject()) {
      for (var e : o.getAsJsonObject("inheritedRenamesByAncestor").entrySet()) {
        java.util.List<String[]> l = extra.byAncestor().computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>());
        for (var pair : e.getValue().getAsJsonArray()) { var pa = pair.getAsJsonArray(); l.add(new String[]{pa.get(0).getAsString().replace('.', '/'), pa.get(1).getAsString()}); }
      }
    }
    if (o.has("superHooks") && o.get("superHooks").isJsonArray()) {
      for (var e : o.getAsJsonArray("superHooks")) {
        JsonObject a = e.getAsJsonObject(); java.util.List<String[]> hooks = new java.util.ArrayList<>();
        for (var u : a.getAsJsonArray("hooks")) hooks.add(strs(u));
        extra.superHooks().add(new SuperHook(a.get("name").getAsString(), a.get("desc").getAsString(), hooks));
      }
    }
    if (o.has("synthesizeIfMissing") && o.get("synthesizeIfMissing").isJsonArray()) {
      for (var e : o.getAsJsonArray("synthesizeIfMissing")) {
        JsonObject a = e.getAsJsonObject();
        extra.synths().add(new Synth(a.get("name").getAsString(), a.get("desc").getAsString(), strs(a.get("call"))));
      }
    }
    if (o.has("samRenames") && o.get("samRenames").isJsonObject()) {
      for (var e : o.getAsJsonObject("samRenames").entrySet()) {
        Map<String, String> m = extra.samRenames().computeIfAbsent(e.getKey().replace('.', '/'), k -> new HashMap<>());
        for (var x : e.getValue().getAsJsonObject().entrySet()) m.put(x.getKey(), x.getValue().getAsString());
      }
    }
    if (o.has("entryHooks") && o.get("entryHooks").isJsonObject()) {
      for (var e : o.getAsJsonObject("entryHooks").entrySet()) {
        var arr = e.getValue().getAsJsonArray();
        extra.entryHooks().put(e.getKey(), new String[]{arr.get(0).getAsString(), arr.get(1).getAsString(), arr.get(2).getAsString()});
      }
    }
    if (o.has("descWidenings") && o.get("descWidenings").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("descWidenings").entrySet()) {
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, String> map = extra.descWidenings().computeIfAbsent(ownerEntry.getKey().replace('.', '/'), k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) map.put(m.getKey(), m.getValue().getAsString());
      }
    }
    if (o.has("callAdapters") && o.get("callAdapters").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("callAdapters").entrySet()) {
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, CallAdapter> map = extra.callAdapters().computeIfAbsent(ownerEntry.getKey().replace('.', '/'), k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
          JsonObject a = m.getValue().getAsJsonObject();
          String[] pack = null;
          if (a.has("pack")) { var pa = a.getAsJsonArray("pack"); pack = new String[]{pa.get(0).getAsString(), pa.get(1).getAsString(), pa.get(2).getAsString()}; }
          int[] extras = new int[0];
          if (a.has("extras")) { var ea = a.getAsJsonArray("extras"); extras = new int[ea.size()]; for (int i = 0; i < extras.length; i++) extras[i] = ea.get(i).getAsInt(); }
          java.util.List<String[]> args = null;
          if (a.has("args")) { args = new java.util.ArrayList<>(); for (var u : a.getAsJsonArray("args")) args.add(strs(u)); }
          map.put(m.getKey(), new CallAdapter(a.get("newName").getAsString(), a.get("newDesc").getAsString(), pack, extras, args, a.has("convert") ? strs(a.get("convert")) : null));
        }
      }
    }
    if (o.has("overrideAdapters") && o.get("overrideAdapters").isJsonArray()) {
      for (var e : o.getAsJsonArray("overrideAdapters")) {
        JsonObject a = e.getAsJsonObject();
        java.util.List<String[]> unpack = new java.util.ArrayList<>();
        for (var u : a.getAsJsonArray("unpack")) unpack.add(strs(u));
        java.util.List<String[]> after = new java.util.ArrayList<>();
        if (a.has("after")) for (var u : a.getAsJsonArray("after")) after.add(strs(u));
        extra.overrideAdapters().add(new OverrideAdapter(a.get("oldName").getAsString(), a.get("oldDesc").getAsString(),
            a.get("newName").getAsString(), a.get("newDesc").getAsString(), unpack, after, a.has("convert") ? strs(a.get("convert")) : null));
      }
    }
    if (o.has("fieldRedirects") && o.get("fieldRedirects").isJsonObject()) {
      for (var ownerEntry : o.getAsJsonObject("fieldRedirects").entrySet()) {
        String owner = ownerEntry.getKey().replace('.', '/');
        if (!ownerEntry.getValue().isJsonObject()) continue;
        Map<String, String[]> map = fieldRedirects.computeIfAbsent(owner, k -> new HashMap<>());
        for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
          var arr = m.getValue().getAsJsonArray();
          map.put(m.getKey(), strs(arr));   // [shimOwner,name,desc] or ["holder", newFieldDesc, convOwner, convName, convDesc(, castType)]
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

  private record Extra(Map<String, Map<String, String>> descWidenings, Map<String, Map<String, String[]>> handleRedirects,
                       Map<String, Map<String, CallAdapter>> callAdapters, java.util.List<OverrideAdapter> overrideAdapters,
                       Map<String, String[]> entryHooks, Map<String, java.util.List<String[]>> byAncestor,
                       java.util.List<SuperHook> superHooks, java.util.List<Synth> synths, Map<String, Map<String, String>> samRenames) { }

  private static String[] strs(com.google.gson.JsonElement u) {
    if (!u.isJsonArray()) return new String[]{u.getAsString()};
    var ua = u.getAsJsonArray(); String[] arr = new String[ua.size()]; for (int i = 0; i < arr.length; i++) arr[i] = ua.get(i).getAsString(); return arr;
  }

  private static void readTriples(JsonObject o, String key, Map<String, Map<String, String[]>> into) {
    if (!o.has(key) || !o.get(key).isJsonObject()) return;
    for (var ownerEntry : o.getAsJsonObject(key).entrySet()) {
      if (!ownerEntry.getValue().isJsonObject()) continue;
      Map<String, String[]> map = into.computeIfAbsent(ownerEntry.getKey().replace('.', '/'), k -> new HashMap<>());
      for (var m : ownerEntry.getValue().getAsJsonObject().entrySet()) {
        var arr = m.getValue().getAsJsonArray();
        map.put(m.getKey(), new String[]{arr.get(0).getAsString(), arr.get(1).getAsString(), arr.get(2).getAsString()});
      }
    }
  }
}
