// Post-port verification: walk the ported bytecode and collect every reference that cannot be
// satisfied by the target Minecraft. These are the honest "this port is not finished" signals —
// the jar will load, but any code path reaching one of these references throws at runtime.
//
// Two levels. CLASS level: every Minecraft class name referenced must exist in the inventory.
// MEMBER level, for the reference kinds nothing but the class file itself can satisfy: fields,
// static methods and constructors. Instance methods are deliberately left out — Fabric's
// interface injection adds those to Minecraft classes at runtime, so a class-file check would
// flag legitimate calls, and a verifier that cries wolf teaches people to ignore it. Member
// resolution walks the real superclass/interface chain read from the running game's own class
// files, so an inherited member is never reported missing, and anything that cannot be read is
// treated as unknown rather than missing.
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
  private final Set<String> known;
  private final Set<String> missing = new TreeSet<>();

  public PortVerifier(AutoBlocklistFromRefmap inventory) { this.known = inventory.classNames(); }

  public boolean isActive() { return !known.isEmpty(); }

  // com/mojang covers several EXTERNAL libraries too (authlib, brigadier, serialization);
  // only the subpackages that ship inside the client jar are checkable here.
  private static boolean checkable(String internalName) {
    return internalName.startsWith("net/minecraft/")
        || internalName.startsWith("com/mojang/blaze3d/")
        || internalName.startsWith("com/mojang/math/")
        || internalName.startsWith("com/mojang/realmsclient/");
  }

  public void scan(byte[] classBytes) {
    if (known.isEmpty()) return;
    ClassReader r = new ClassReader(classBytes);
    r.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
      @Override public String map(String internalName) {
        if (checkable(internalName) && internalName.indexOf('$') < 0 && !known.contains(internalName)) {
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
          }
          @Override public void visitInvokeDynamicInsn(String iname, String idesc, Handle bsm, Object... args) {
            for (Object o : args) {
              if (!(o instanceof Handle h)) continue;
              switch (h.getTag()) {
                case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC ->
                    checkMember(h.getOwner(), h.getName(), h.getDesc(), true, false);
                case Opcodes.H_NEWINVOKESPECIAL -> checkMember(h.getOwner(), h.getName(), h.getDesc(), false, true);
                case Opcodes.H_INVOKESTATIC -> checkMember(h.getOwner(), h.getName(), h.getDesc(), false, h.isInterface());
                default -> { }
              }
            }
          }
        };
      }
    }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
  }

  public Set<String> missing() { return missing; }

  // ---- member resolution against the running game's class files ----

  private record Shape(String superName, String[] interfaces, Set<String> fields, Set<String> methods) { }
  private static final Shape UNKNOWN = new Shape(null, new String[0], Set.of(), Set.of());
  private final Map<String, Shape> shapes = new HashMap<>();

  private void checkMember(String owner, String name, String desc, boolean field, boolean direct) {
    if (!checkable(owner) || owner.startsWith("[") || missing.contains(owner)) return;
    String key = field ? name + ":" + desc : name + desc;
    if (has(owner, key, field, direct, 0) == Boolean.FALSE) missing.add(owner + "#" + pretty(name, desc, field));
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
    try (InputStream in = open(cls + ".class")) {
      if (in != null) {
        ClassReader r = new ClassReader(in.readAllBytes());
        Set<String> f = new HashSet<>(), m = new HashSet<>();
        r.accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public FieldVisitor visitField(int a, String n, String d, String sg, Object v) { f.add(n + ":" + d); return null; }
          @Override public MethodVisitor visitMethod(int a, String n, String d, String sg, String[] e) { m.add(n + d); return null; }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        s = new Shape(r.getSuperName(), r.getInterfaces(), f, m);
      }
    } catch (Throwable ignore) { }
    shapes.put(cls, s);
    return s;
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
