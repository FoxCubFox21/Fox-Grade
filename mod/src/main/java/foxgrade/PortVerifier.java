// Post-port verification: walk the ported bytecode and collect every reference that cannot be
// satisfied by the target Minecraft. These are the honest "this port is not finished" signals —
// the jar will load, but any code path reaching one of these references throws at runtime.
//
// Two levels. CLASS level: every Minecraft class name referenced must exist in the inventory
// (inner classes, which the inventory lacks, are checked by reading the class file instead).
// MEMBER level: fields, constructors, static AND instance methods. Resolution walks the real
// superclass/interface chain read from the running game's own class files, so an inherited
// member is never reported missing, and anything that cannot be read is treated as unknown
// rather than missing. The one way a method can legitimately exist without being in any class
// file is Fabric's interface injection; the interfaces every loaded mod declares as injected are
// folded into the hierarchy, so those calls resolve too. A verifier that cries wolf teaches
// people to ignore it — that is the bar every rule here is held to.
//
// Class collection rides ASM's ClassRemapper with an identity Remapper that records every class
// name it is asked about — the same traversal the real remap uses, so coverage is identical.
package foxgrade;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class PortVerifier {
  private static java.util.Collection<net.fabricmc.loader.api.ModContainer> loadedMods() {
    try { return net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods(); } catch (Throwable standalone) { return java.util.List.of(); }
  }

  private final Set<String> known;
  private final Set<String> missing = new TreeSet<>();

  public PortVerifier(AutoBlocklistFromRefmap inventory) { this.known = inventory.classNames(); }

  public boolean isActive() { return !known.isEmpty(); }

  // com/mojang covers several EXTERNAL libraries too (authlib, brigadier, serialization);
  // only the subpackages that ship inside the client jar are checkable here.
  /** True when the target game has this class (only meaningful for game-namespace names). */
  public boolean knows(String cls) { return known.contains(cls); }
  public static boolean isGameClass(String internalName) { return checkable(internalName); }
  private static boolean checkable(String internalName) {
    return internalName.startsWith("net/minecraft/")
        || internalName.startsWith("com/mojang/blaze3d/")
        || internalName.startsWith("com/mojang/math/")
        || internalName.startsWith("com/mojang/realmsclient/")
        || internalName.startsWith("org/quiltmc/loader/api/") || internalName.startsWith("org/quiltmc/qsl/");   // Quilt-only mods: loader + QSL stand-ins
  }

  /** Pre-declare a class of the jar being ported (names in target form), so members reached
   *  through it resolve up its chain even before its own bytes are scanned. */
  public void declareModClass(String name, String superName, String[] interfaces) {
    if (!shapes.containsKey(name)) shapes.put(name, new Shape(superName, interfaces == null ? new String[0] : interfaces, new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), false, false));
  }

  /** Does this class itself declare the member (name+desc for methods, name:desc for fields)? */
  public boolean declares(String cls, String key) { Shape s = shape(cls); return s != UNKNOWN && (s.methods().contains(key) || s.fields().contains(key)); }
  /** Is the method final somewhere up the superclass chain starting AT cls? */
  public boolean finalInChain(String cls, String key) {
    for (String o = cls, g = ""; o != null && g.length() < 48; o = superOf(o), g += "x") { Shape s = shape(o); if (s != UNKNOWN && s.finals().contains(key)) return true; }
    return false;
  }
  /** Is the method declared (abstract or not) somewhere up the superclass chain starting AT cls? */
  public boolean declaredInChain(String cls, String key) { return chainHas(cls, key, false, 0); }
  /** name+desc of every abstract method the class itself declares (a callback interface's SAM among them); empty when unknown. */
  public java.util.Set<String> abstractsOf(String cls) { Shape s = shape(cls); return s == UNKNOWN ? java.util.Set.of() : s.abstracts(); }
  // Walks superclasses AND interfaces (a renderer may inherit its abstract methods from an
  // interface such as BlockEntityRenderer). implementedOnly: abstract declarations do not count.
  private boolean chainHas(String cls, String key, boolean implementedOnly, int depth) {
    if (cls == null || depth > 40) return false;
    Shape s = shape(cls);
    if (s == UNKNOWN) return false;
    if (s.methods().contains(key) && (!implementedOnly || !s.abstracts().contains(key))) return true;
    for (String itf : s.interfaces()) if (chainHas(itf, key, implementedOnly, depth + 1)) return true;
    return chainHas(s.superName(), key, implementedOnly, depth + 1);
  }
  /** Is the method implemented (declared non-abstract) somewhere up the superclass chain starting AT cls? */
  public boolean implementedInChain(String cls, String key) { return chainHas(cls, key, true, 0); }

  /** Direct interfaces of a class as far as this verifier can tell; empty if unknown. */
  public String[] interfacesOf(String cls) { Shape s = shape(cls); return s == UNKNOWN ? new String[0] : s.interfaces(); }

  /** Superclass of a class as far as this verifier can tell (mod classes seen, game classes read); null if unknown. */
  /** True/false when the target game has the class; null when it is unknown (a mod class, a missing class). */
  public boolean isFinalClass(String cls) { if (!checkable(cls) || !known.contains(cls)) return false; Shape s = shape(cls); return s != UNKNOWN && s.finalClass(); }
  public Boolean isInterface(String cls) { if (!checkable(cls) || !known.contains(cls)) return null; Shape s = shape(cls); return s == UNKNOWN ? null : s.isInterface(); }
  public String superOf(String cls) {
    Shape s = shape(cls);
    return s == UNKNOWN ? null : s.superName();
  }

  public void scan(byte[] classBytes) {
    if (known.isEmpty()) return;
    ClassReader r = new ClassReader(classBytes);
    // The class's own shape: a mod class is a link in the chain for members reached through it.
    Set<String> declF = new HashSet<>(), declM = new HashSet<>(), fin = new HashSet<>(), abs = new HashSet<>();
    r.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public FieldVisitor visitField(int a, String n, String d, String sg, Object v) { declF.add(n + ":" + d); return null; }
      @Override public MethodVisitor visitMethod(int a, String n, String d, String sg, String[] e) {
        declM.add(n + d); if ((a & Opcodes.ACC_FINAL) != 0) fin.add(n + d); if ((a & Opcodes.ACC_ABSTRACT) != 0) abs.add(n + d); return null;
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    shapes.put(r.getClassName(), new Shape(r.getSuperName(), r.getInterfaces(), declF, declM, fin, abs, (r.getAccess() & Opcodes.ACC_INTERFACE) != 0, (r.getAccess() & Opcodes.ACC_FINAL) != 0));
    settled = false;
    r.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
      @Override public String map(String internalName) {
        if (internalName.endsWith("NonnullByDefault")) return internalName;   // a missing annotation type is ignored by the JVM
        int dollar = internalName.indexOf('$');
        // A missing inner class counts when its outer is a real game class (ArmorMaterial$Layer vanished
        // while ArmorMaterial stayed); anonymous/synthetic inner refs of unknown outers stay noise.
        boolean innerOfKnown = dollar > 0 && known.contains(internalName.substring(0, dollar)) && !internalName.substring(dollar + 1).chars().allMatch(Character::isDigit);
        if (checkable(internalName) && (dollar < 0 || innerOfKnown || ShimGenerator.SHIMS.containsKey(internalName)) && !known.contains(internalName)) {
          // inner classes are skipped as noise, except the ones Fox-Grade re-creates (VillagerTrades$ItemListing, GameRules$Key)
          missing.add(internalName);
        } else if (!checkable(internalName) && internalName.startsWith("net/fabricmc/fabric/api/") && !ShimGenerator.SHIMS.containsKey(internalName) && shape(internalName) == UNKNOWN) {
          // Fabric API classes are judged by whether the installed Fabric API can load them: the
          // 1.21.x ItemGroupEvents / ExtendedScreenHandlerType are gone from the 26.2 modules.
          missing.add(internalName);
        } else if (!checkable(internalName) && ShimGenerator.SHIMS.containsKey(internalName) && !loadable(internalName)) {
          // A removed third-party class Fox-Grade re-creates (Fabric's WorldRenderEvents): not in
          // the game's inventory, so its absence is judged by whether the loader can read it.
          missing.add(internalName);
        }
        return internalName;
      }
    }), 0);
    r.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitFieldInsn(int op, String owner, String fname, String fdesc) {
            checkMember(owner, fname, fdesc, true, false);
          }
          @Override public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
            if (mname.equals("<init>")) checkMember(owner, mname, mdesc, false, true);
            else if (op == Opcodes.INVOKESTATIC) checkMember(owner, mname, mdesc, false, itf);   // static interface methods are not inherited
            else checkMember(owner, mname, mdesc, false, false);
          }
          @Override public void visitInvokeDynamicInsn(String iname, String idesc, Handle bsm, Object... args) {
            for (Object o : args) {
              if (!(o instanceof Handle h)) continue;
              switch (h.getTag()) {
                case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC ->
                    checkMember(h.getOwner(), h.getName(), h.getDesc(), true, false);
                case Opcodes.H_NEWINVOKESPECIAL -> checkMember(h.getOwner(), h.getName(), h.getDesc(), false, true);
                case Opcodes.H_INVOKESTATIC -> checkMember(h.getOwner(), h.getName(), h.getDesc(), false, h.isInterface());
                case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL ->
                    checkMember(h.getOwner(), h.getName(), h.getDesc(), false, false);
                default -> { }
              }
            }
          }
        };
      }
    }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
  }

  public Set<String> missing() { settle(); return missing; }

  // Member checks are deferred until every class of the jar has been scanned, so a reference
  // through a mod class that appears later in the jar still resolves up its real chain.
  private final java.util.List<String[]> pending = new java.util.ArrayList<>();
  private boolean settled = true;
  private void settle() {
    if (settled) return;
    settled = true;
    java.util.List<String[]> work = new java.util.ArrayList<>(pending);
    pending.clear();
    for (String[] p : work) {
      String owner = p[0], name = p[1], desc = p[2];
      boolean field = "f".equals(p[3]), direct = "d".equals(p[4]);
      boolean mc = checkable(owner);
      if (owner.startsWith("[") || missing.contains(owner)) continue;
      if (!mc && !shapes.containsKey(owner)) continue;      // neither ours nor the game's: cannot judge
      if (mc && owner.indexOf('$') > 0 && shape(owner) == UNKNOWN) {
        // The inventory has no inner classes; the class file is the authority. Outer exists,
        // inner does not: the inner class is what is missing (VertexFormat$Mode).
        if (shape(owner.substring(0, owner.indexOf('$'))) != UNKNOWN) missing.add(owner);
        continue;
      }
      String key = field ? name + ":" + desc : name + desc;
      if (has(owner, key, field, direct, 0) == Boolean.FALSE) missing.add(owner + "#" + pretty(name, desc, field));
    }
  }

  // ---- member resolution against the running game's class files ----

  private record Shape(String superName, String[] interfaces, Set<String> fields, Set<String> methods, Set<String> finals, Set<String> abstracts, boolean isInterface, boolean finalClass) { }
  private static final Shape UNKNOWN = new Shape(null, new String[0], Set.of(), Set.of(), Set.of(), Set.of(), false, false);
  private final Map<String, Shape> shapes = new HashMap<>();

  private void checkMember(String owner, String name, String desc, boolean field, boolean direct) {
    pending.add(new String[]{owner, name, desc, field ? "f" : "m", direct ? "d" : "i"});
    settled = false;
  }

  /** TRUE: declared somewhere in the chain. FALSE: the whole chain is readable and lacks it. null: cannot tell. */
  private Boolean has(String cls, String key, boolean field, boolean direct, int depth) {
    if (cls == null || depth > 40) return null;
    Shape s = shape(cls);
    if (s == UNKNOWN) return null;
    if ((field ? s.fields() : s.methods()).contains(key)) return Boolean.TRUE;
    if (direct) return Boolean.FALSE;
    boolean unknown = false;
    if (s.superName() != null) {
      Boolean up = has(s.superName(), key, field, false, depth + 1);
      if (up == Boolean.TRUE) return up;
      if (up == null) unknown = true;
    }
    for (String itf : s.interfaces()) {
      Boolean r = has(itf, key, field, false, depth + 1);
      if (r == Boolean.TRUE) return r;
      if (r == null) unknown = true;
    }
    return unknown ? null : Boolean.FALSE;
  }

  private Shape shape(String cls) {
    Shape s = shapes.get(cls);
    if (s != null) return s;
    s = UNKNOWN;
    // A class Fox-Grade re-creates under a game name (BlockEntityType$Builder, ArmorItem) lives in the
    // jar as foxgrade/shim/<Shim>.class: read that, with the injection renames applied, so member
    // checks judge the shim's real signatures instead of skipping them.
    String resource = cls;
    for (var e : ShimGenerator.SHIM_RENAMES.entrySet()) if (e.getValue().equals(cls)) { resource = e.getKey(); break; }
    try (InputStream in = open(resource + ".class")) {
      if (in != null) {
        byte[] bytes = in.readAllBytes();
        // Fox-Grade's own shims are read from its jar, whose copies still carry pre-injection names in
        // their descriptors; apply the same renames so members compare against what the port sees.
        if (resource.startsWith("foxgrade/shim/")) bytes = ShimGenerator.renameClasses(bytes, ShimGenerator.SHIM_RENAMES);
        ClassReader r = new ClassReader(bytes);
        Set<String> f = new HashSet<>(), m = new HashSet<>(), fin = new HashSet<>(), abs = new HashSet<>();
        r.accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public FieldVisitor visitField(int a, String n, String d, String sg, Object v) { f.add(n + ":" + d); return null; }
          @Override public MethodVisitor visitMethod(int a, String n, String d, String sg, String[] e) {
            m.add(n + d); if ((a & Opcodes.ACC_FINAL) != 0) fin.add(n + d); if ((a & Opcodes.ACC_ABSTRACT) != 0) abs.add(n + d); return null;
          }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String[] itfs = r.getInterfaces();
        java.util.List<String> extra = injected().get(cls);
        if (extra != null && !extra.isEmpty()) {
          String[] all = java.util.Arrays.copyOf(itfs, itfs.length + extra.size());
          for (int i = 0; i < extra.size(); i++) all[itfs.length + i] = extra.get(i);
          itfs = all;
        }
        s = new Shape(r.getSuperName(), itfs, f, m, fin, abs, (r.getAccess() & Opcodes.ACC_INTERFACE) != 0, (r.getAccess() & Opcodes.ACC_FINAL) != 0);
      }
    } catch (Throwable ignore) { }
    shapes.put(cls, s);
    return s;
  }

  // Interfaces that loaded mods inject into Minecraft classes at runtime (Loom's
  // `loom:injected_interfaces` custom value): the only legitimate source of a method that no
  // class file declares.
  private Map<String, java.util.List<String>> injected;
  /** Interfaces injected by Fabric API modules, supplied by a standalone run that has no loader (CheckMain). */
  public static final Map<String, java.util.List<String>> EXTRA_INJECTED = new HashMap<>();
  private Map<String, java.util.List<String>> injected() {
    if (injected != null) return injected;
    Map<String, java.util.List<String>> m = new HashMap<>();
    for (var e : EXTRA_INJECTED.entrySet()) m.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).addAll(e.getValue());
    try {
      for (var mod : loadedMods()) {
        var cv = mod.getMetadata().getCustomValue("loom:injected_interfaces");
        if (cv == null || cv.getType() != net.fabricmc.loader.api.metadata.CustomValue.CvType.OBJECT) continue;
        for (var e : cv.getAsObject()) {
          if (e.getValue().getType() != net.fabricmc.loader.api.metadata.CustomValue.CvType.ARRAY) continue;
          java.util.List<String> l = m.computeIfAbsent(e.getKey().replace('.', '/'), k -> new java.util.ArrayList<>());
          for (var i : e.getValue().getAsArray()) l.add(i.getAsString().replace('.', '/'));
        }
      }
    } catch (Throwable ignore) { }
    injected = m;
    return m;
  }

  /** Whether the target game / installed Fabric API can load this class by its own name (not via a shim). */
  private static boolean loadable(String cls) {
    try (InputStream in = open(cls + ".class")) { return in != null; } catch (java.io.IOException e) { return false; }
  }
  private static InputStream open(String resource) {
    InputStream in = PortVerifier.class.getClassLoader().getResourceAsStream(resource);
    return in != null ? in : ClassLoader.getSystemResourceAsStream(resource);
  }

  // "Entity#noCulling", "Vec3#new(Vector3f)": no slashes, so the one-line report's
  // strip-to-simple-name keeps the whole thing.
  private static String pretty(String name, String desc, boolean field) {
    if (field) return name;
    StringBuilder sb = new StringBuilder(name.equals("<init>") ? "new" : name).append('(');
    Type[] args = Type.getArgumentTypes(desc);
    for (int i = 0; i < args.length; i++) {
      String c = args[i].getClassName();
      sb.append(i > 0 ? "," : "").append(c.substring(c.lastIndexOf('.') + 1));
    }
    return sb.append(')').toString();
  }
}
