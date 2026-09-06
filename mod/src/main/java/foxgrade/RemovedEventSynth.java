package foxgrade;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Fabric API callback types that 26.2 dropped outright (ClientPickBlockApplyCallback …). A mod touching one would
 *  NoClassDefFoundError at the first {@code X.EVENT.register(...)}. Instead, the type is synthesised into the port:
 *  an interface with the same EVENT constant (a dead event — listeners register and never fire) and the callback method
 *  the mod's lambdas implement, taken from the invokedynamic sites that create them. The feature goes inert, the mod boots. */
public final class RemovedEventSynth {
  private RemovedEventSynth() {}
  public static final String EVENT_DESC = "Lnet/fabricmc/fabric/api/event/Event;";
  public static final String DEAD_OWNER = "foxgrade/shim/FabricEventsCompat";

  /** owner → (fields named on it with Event type, SAM name+desc pairs) */
  public static final class Missing { public final Set<String> eventFields = new LinkedHashSet<>(); public final Set<String> sams = new LinkedHashSet<>(); }

  /** Scan one (already remapped) class for uses of Fabric API types that {@code exists} rejects, and — for owners that do
   *  exist — for Event fields {@code fieldExists} rejects (EntitySleepEvents.ALLOW_SLEEP_TIME): those reads are rewritten
   *  in place to a dead event. Returns the class bytes, rewritten when a field read was redirected. */
  public static byte[] collect(byte[] classBytes, Predicate<String> exists, java.util.function.BiPredicate<String, String> fieldExists, Map<String, Missing> into) {
    Set<String> deadFields = new LinkedHashSet<>();
    collect(classBytes, exists, into, (owner, name) -> { if (!fieldExists.test(owner, name)) deadFields.add(owner + "." + name); });
    if (deadFields.isEmpty()) return classBytes;
    ClassReader r = new ClassReader(classBytes);
    ClassWriter w = new ClassWriter(r, 0);
    r.accept(new ClassVisitor(Opcodes.ASM9, w) {
      @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a, n, d, s, e)) {
          @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            if (op == Opcodes.GETSTATIC && desc.equals(EVENT_DESC) && deadFields.contains(owner + "." + name)) { super.visitMethodInsn(Opcodes.INVOKESTATIC, DEAD_OWNER, "dead", "()" + EVENT_DESC, false); return; }
            super.visitFieldInsn(op, owner, name, desc);
          }
        };
      }
    }, 0);
    return w.toByteArray();
  }
  public static void collect(byte[] classBytes, Predicate<String> exists, Map<String, Missing> into) { collect(classBytes, exists, into, (o, n) -> { }); }
  private static void collect(byte[] classBytes, Predicate<String> exists, Map<String, Missing> into, java.util.function.BiConsumer<String, String> existingOwnerField) {
    new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itfs) {
        // a listener CLASS implementing the removed callback type: the type must exist for the class to load at all
        if (itfs != null) for (String i : itfs) if (candidate(i) && !exists.test(i)) into.computeIfAbsent(i, k -> new Missing());
      }
      @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            if (op == Opcodes.GETSTATIC && desc.equals(EVENT_DESC) && candidate(owner)) {
              if (!exists.test(owner)) into.computeIfAbsent(owner, k -> new Missing()).eventFields.add(name);
              else existingOwnerField.accept(owner, name);
            }
          }
          @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            Type ret = Type.getReturnType(desc);
            if (ret.getSort() != Type.OBJECT) return;
            String owner = ret.getInternalName();
            if (!candidate(owner) || exists.test(owner)) return;
            String sam = null;
            for (Object o : args) if (o instanceof Type t && t.getSort() == Type.METHOD) { sam = name + t.getDescriptor(); break; }
            Missing m = into.computeIfAbsent(owner, k -> new Missing());
            if (sam != null) m.sams.add(sam);
          }
        };
      }
    }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
  }

  static boolean candidate(String owner) { return owner.startsWith("net/fabricmc/fabric/api/"); }

  /** Build the stand-in interface. */
  public static byte[] synthesize(String owner, Missing m) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, owner, null, "java/lang/Object", null);
    for (String f : m.eventFields) cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, f, EVENT_DESC, null, null).visitEnd();
    for (String sam : m.sams) {
      int p = sam.indexOf('(');
      cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, sam.substring(0, p), sam.substring(p), null, null).visitEnd();
    }
    if (!m.eventFields.isEmpty()) {
      MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      mv.visitCode();
      for (String f : m.eventFields) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DEAD_OWNER, "dead", "()" + EVENT_DESC, false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, f, EVENT_DESC);
      }
      mv.visitInsn(Opcodes.RETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }
    cw.visitEnd();
    return cw.toByteArray();
  }
}
