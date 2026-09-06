// ASM Remapper that translates intermediary names to Mojang names, and Mojang-to-Mojang class
// renames from Fox-Grade's own rules table, in a single pass.
//
// Extends ASM's Remapper (not SimpleRemapper) because SimpleRemapper's class-only map cannot see
// method or field renames. This subclass overrides map(), mapMethodName(), and mapFieldName() so
// that a call like `Minecraft.method_1574()V` becomes `Minecraft.tick()V` and a field access
// like `Minecraft.field_1729:Lclass_1937;` becomes `Minecraft.level:Llevel/Level;`.
//
// The class table also handles inner-class names by splitting on '$' when a full-name lookup
// misses — a common shape in the constant pool (`net/minecraft/class_8710$class_9154`).
package foxgrade;

import org.objectweb.asm.commons.Remapper;

import java.util.Map;

public final class BridgeRemapper extends Remapper {
  private final Map<String, String> inheritedRenames;
  // name+desc → [[ancestorClass, newName], …]: an inherited rename that depends on what the
  // declaring class extends (renderWidget is extractContents under AbstractButton, whose own
  // extractWidgetRenderState is final, and extractWidgetRenderState elsewhere).
  private Map<String, java.util.List<String[]>> inheritedRenamesByAncestor = Map.of();
  private static final String TRACE = System.getenv("FOXGRADE_TRACE");
  private java.util.function.UnaryOperator<String> superOf = (c) -> null;
  public void setSuperOf(java.util.function.UnaryOperator<String> f) { this.superOf = f; }
  public void setInheritedRenamesByAncestor(Map<String, java.util.List<String[]>> m) { this.inheritedRenamesByAncestor = m; }
  private boolean descendsFrom(String cls, String ancestor) {
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    String o = cls;
    for (int guard = 0; o != null && guard < 48; guard++) { if (o.equals(ancestor)) return true; seen.add(o); o = superOf.apply(o); }
    // interfaces count as ancestors too: a class implementing Fabric's ModelLoadingPlugin inherits its renamed callback
    java.util.ArrayDeque<String> itfs = new java.util.ArrayDeque<>();
    for (String c : seen) for (String i : interfacesOf.apply(c)) itfs.add(i);
    for (int guard = 0; !itfs.isEmpty() && guard < 200; guard++) {
      String i = itfs.poll(); if (!seen.add(i)) continue;
      if (i.equals(ancestor)) return true;
      for (String k : interfacesOf.apply(i)) itfs.add(k);
    }
    return false;
  }
  private final Map<String, String> classes;
  private final Map<String, Map<String, String>> methods;
  private final Map<String, Map<String, String>> fields;
  // Global fallback tables: intermediary name → mojang name, regardless of owner. Safe because
  // Fabric intermediary method/field IDs are unique per DECLARATION (a `method_XXXX` is one
  // specific declared method across the entire jar). This closes the inherited-member gap:
  // a call to `class_3222.method_5773()` — where the method is inherited from Entity — resolves
  // to `tick` even though method_5773 isn't in class_3222's own member table.
  private final Map<String, String> globalMethods;
  private final Map<String, String> globalFields;
  private final Map<String, Map<String, String>> apiRenames;    // third-party bridge (fabric-api etc)
  // Mojang-source member renames (getLocation → location): keyed "name+descriptor" per owner,
  // with a global fallback for unique entries so inherited calls resolve too.
  private final Map<String, Map<String, String>> mojangMethods;
  private final Map<String, String> mojangMethodsGlobal;
  private final Map<String, Map<String, String>> yarnMethods;
  private final Map<String, Map<String, String>> yarnFields;

  public BridgeRemapper(Map<String, String> classes,
                        Map<String, Map<String, String>> methods,
                        Map<String, Map<String, String>> fields,
                        Map<String, String> globalMethods,
                        Map<String, String> globalFields,
                        Map<String, Map<String, String>> apiRenames,
                        Map<String, Map<String, String>> mojangMethods,
                        Map<String, String> mojangMethodsGlobal,
                        Map<String, Map<String, String>> yarnMethods,
                        Map<String, Map<String, String>> yarnFields,
                        Map<String, String> inheritedRenames) {
    this.classes = classes; this.methods = methods; this.fields = fields;
    this.globalMethods = globalMethods; this.globalFields = globalFields;
    this.apiRenames = apiRenames;
    this.mojangMethods = mojangMethods; this.mojangMethodsGlobal = mojangMethodsGlobal;
    this.yarnMethods = yarnMethods; this.yarnFields = yarnFields;
    this.inheritedRenames = inheritedRenames;
  }

  @Override public String map(String internalName) {
    String direct = classes.get(internalName);
    if (direct != null) return direct;
    // Inner-class fallback: net/minecraft/class_8710$class_9154 — try the outer, keep the tail.
    // The bridge usually carries the composite form directly, but not every intermediate table
    // has the inner name, and a bare-outer fallback beats leaving `class_XXXX$class_YYYY` intact.
    int dollar = internalName.indexOf('$');
    if (dollar >= 0) {
      String outer = internalName.substring(0, dollar);
      String outerMapped = classes.get(outer);
      if (outerMapped != null) return outerMapped + internalName.substring(dollar);
    }
    return internalName;
  }

  // Global fallback tables may ONLY apply to Minecraft-owned classes. Applying them to a mod's
  // own classes renamed an internal config method once and produced a VerifyError — a name that
  // happens to collide with an MC rename is not evidence the mod meant the MC method.
  private static boolean isMinecraftOwner(String owner) {
    return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/");
  }

  @Override public String mapMethodName(String owner, String name, String descriptor) {
    String to = mapMethodName0(owner, name, descriptor);
    // A rename can never land on a constructor or class initialiser; a table that says so is wrong (ListTag.clear → <init>).
    return (to.equals("<init>") || to.equals("<clinit>")) && !name.equals(to) ? name : to;
  }
  private String mapMethodName0(String owner, String name, String descriptor) {
    // Curated API bridge FIRST — its entries are hand-verified overrides for cases the mined
    // tables get wrong (cross-class inheritance renames like Registry.get → DefaultedRegistry.
    // getValue). It's keyed by mojang OR intermediary owner; try both the raw owner and its
    // translated form so both compile styles resolve.
    String mappedOwner = map(owner);
    Map<String, String> api = apiRenames.get(owner);
    if (api == null && !mappedOwner.equals(owner)) api = apiRenames.get(mappedOwner);
    if (api != null) { String to = api.get(name); if (to != null) return to; }
    // Curated renames apply to subclasses too, nearest ancestor first, the way the JVM resolves
    // the member (Button.renderWidget is AbstractWidget's method).
    if (TRACE != null && name.equals(TRACE)) System.err.println("[trace] " + owner + " -> " + mappedOwner + " super=" + superOf.apply(mappedOwner) + " api=" + (api != null));
    for (String anc = superOf.apply(mappedOwner), g0 = ""; anc != null && g0.length() < 48; anc = superOf.apply(anc), g0 += "x") {
      Map<String, String> a = apiRenames.get(anc);
      if (a != null) { String to = a.get(name); if (to != null) return to; }
    }
    Map<String, String> m = methods.get(owner);
    if (m != null) { String to = m.get(name); if (to != null) return apiChain(mappedOwner, to); }
    int dollar = owner.indexOf('$');
    if (dollar >= 0) {
      Map<String, String> m2 = methods.get(owner.substring(0, dollar));
      if (m2 != null) { String to = m2.get(name); if (to != null) return apiChain(mappedOwner, to); }
    }
    // Mojang-source member renames: keyed name+descriptor with the ORIGINAL (1.21.1) descriptor.
    Map<String, String> moj = mojangMethods.get(owner);
    if (moj == null && !mappedOwner.equals(owner)) moj = mojangMethods.get(mappedOwner);
    if (moj != null) { String to = moj.get(name + descriptor); if (to != null) return apiChain(mappedOwner, to); }
    Map<String, String> ym = yarnMethods.get(owner);
    if (ym != null) { String to = ym.get(name + descriptor); if (to != null) return apiChain(mappedOwner, to); }
    // The mojang-global table holds real-word names (reload, save, …) that can collide with a
    // mod's own methods — MC owners only. The intermediary global table's keys are method_XXXX
    // ids that only ever mean the MC member, so it applies everywhere — including to @Shadow
    // members DECLARED on mixin classes, whose owner is the mod's own mixin class.
    if (isMinecraftOwner(owner)) {
      String mg = mojangMethodsGlobal.get(name + descriptor);
      if (mg != null) return apiChain(mappedOwner, mg);
    }
    String g = globalMethods.get(name);
    if (g != null) return finishInherited(owner, apiChain(mappedOwner, g), descriptor);
    return finishInherited(owner, name, descriptor);
  }

  // Curated overriding-method renames for MOD-owned classes: a class implementing an MC
  // interface whose method was renamed must rename its own DECLARATION too, or the JVM throws
  // AbstractMethodError when MC calls the new name. Keys are name+desc with descriptor-simple
  // signatures (no class names in the desc), so pre/post-remap descriptor form is identical.
  private String finishInherited(String owner, String resolved, String descriptor) {
    if (!isMinecraftOwner(owner)) {
      String mapped = mapMethodDesc(descriptor);
      java.util.List<String[]> byAnc = inheritedRenamesByAncestor.get(resolved + mapped);
      if (byAnc != null) for (String[] a : byAnc) if (descendsFrom(map(owner), a[0])) return a[1];
      String ir = inheritedRenames.get(resolved + descriptor);
      if (ir == null) ir = inheritedRenames.get(resolved + mapped);   // keys written in target names
      if (ir != null) return ir;
    }
    return resolved;
  }

  // Curated renames on the TRANSLATED name, nearest owner first, then up the superclass chain and
  // across the interfaces: a Fabric mod calls Mob.method_5808, which translates to Mob.moveTo, whose
  // rename to snapTo is recorded on Entity.
  private java.util.function.Function<String, String[]> interfacesOf = (c) -> new String[0];
  public void setInterfacesOf(java.util.function.Function<String, String[]> f) { this.interfacesOf = f; }
  private String apiChain(String mappedOwner, String resolved) {
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (String o = mappedOwner, g = ""; o != null && g.length() < 48; o = superOf.apply(o), g += "x") {
      seen.add(o);
      Map<String, String> api = apiRenames.get(o);
      if (api != null) { String to = api.get(resolved); if (to != null) return to; }
    }
    java.util.ArrayDeque<String> itfs = new java.util.ArrayDeque<>();
    for (String c : seen) for (String i : interfacesOf.apply(c)) itfs.add(i);
    for (int guard = 0; !itfs.isEmpty() && guard < 200; guard++) {
      String i = itfs.poll(); if (!seen.add(i)) continue;
      Map<String, String> api = apiRenames.get(i);
      if (api != null) { String to = api.get(resolved); if (to != null) return to; }
      for (String j : interfacesOf.apply(i)) itfs.add(j);
    }
    return resolved;
  }

  @Override public String mapFieldName(String owner, String name, String descriptor) {
    Map<String, String> m = fields.get(owner);
    if (m != null) { String to = m.get(name); if (to != null) return to; }
    int dollar = owner.indexOf('$');
    if (dollar >= 0) {
      Map<String, String> m2 = fields.get(owner.substring(0, dollar));
      if (m2 != null) { String to = m2.get(name); if (to != null) return to; }
    }
    String g = globalFields.get(name);
    if (g != null) return g;
    return name;
  }

  // Invoke-dynamic method-handle constants pass through here — same rule as static method calls.
  @Override public String mapInvokeDynamicMethodName(String name, String descriptor) { return name; }

  // Reflection support: mods look members up by NAME STRING (Class.getDeclaredField("field_23166")).
  // Those literals ride the constant pool as plain strings, which the standard remap never touches —
  // the lookup then fails at runtime on the renamed member. Intermediary ids are unmistakable
  // (`method_\d+` / `field_\d+` / `class_\d+`), so remapping ONLY those string shapes is safe;
  // ordinary prose can't collide with them.
  @Override public Object mapValue(Object value) {
    if (value instanceof String s && !s.isEmpty()) {
      if (s.startsWith("method_") && s.length() > 7 && digits(s, 7)) {
        String g = globalMethods.get(s);
        if (g != null) return g;
      } else if (s.startsWith("field_") && s.length() > 6 && digits(s, 6)) {
        String g = globalFields.get(s);
        if (g != null) return g;
      } else if (s.contains("class_")) {
        String slash = s.replace('.', '/');
        String c = classes.get(slash);
        if (c != null) return s.indexOf('.') >= 0 ? c.replace('/', '.') : c;
      }
    }
    return super.mapValue(value);
  }

  private static boolean digits(String s, int from) {
    for (int i = from; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    return true;
  }
}
