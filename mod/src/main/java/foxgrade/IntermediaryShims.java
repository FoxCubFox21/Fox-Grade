package foxgrade;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/** Shims for targets that load through intermediary.
 *
 *  <p>{@link ShimGenerator}'s shims are compiled Java written against Minecraft's Mojang names, which is right for
 *  26.x and unloadable on anything older — that is why {@code ShimGenerator.unavailableHere} reports them missing
 *  there. The classes here are different in the one way that matters: they are emitted as bytecode carrying
 *  intermediary names, so they are valid on the version they are built for.
 *
 *  <p>Only for a type the target deleted outright, where the deleted type is small and self-contained. That is a
 *  narrow set on purpose. InteractionResultHolder is the case worth having: 1.21.2 removed it and folded it into
 *  InteractionResult, it holds nothing but a result and an object, and without it Architectury and Trinkets both
 *  fail to load — and everything that depends on them fails with them. */
final class IntermediaryShims {
  private IntermediaryShims() { }

  private static final String HOLDER = "net/minecraft/class_1271";     // InteractionResultHolder
  private static final String RESULT = "net/minecraft/class_1269";     // InteractionResult

  /** Whether this target wants a Fox-Grade-built copy of {@code cls}: the version deleted it and we can rebuild it. */
  static boolean provides(String cls, String targetMc) {
    if (Targets.namespace(targetMc).equals("official")) return false;
    if (!cls.equals(HOLDER)) return false;
    Map<String, java.util.Set<String>> present = TargetInventory.membersByClass(targetMc);
    // The inventory is written in Mojang names even for a version that runs in intermediary, so ask it the question
    // in its own terms: does this version still have the class the mod is looking for?
    return !present.isEmpty() && !present.containsKey("net/minecraft/world/InteractionResultHolder");
  }

  /** The shim's bytes, or null when this target has no recipe for it. */
  static byte[] bytes(String cls, String targetMc, java.util.function.BiFunction<String, String, String> constant) {
    if (!cls.equals(HOLDER)) return null;
    String success = constant.apply(targetMc, "SUCCESS");
    String consume = constant.apply(targetMc, "CONSUME");
    String pass = constant.apply(targetMc, "PASS");
    String fail = constant.apply(targetMc, "FAIL");
    String server = constant.apply(targetMc, "SUCCESS_SERVER");
    if (success == null || consume == null || pass == null || fail == null) return null;

    // COMPUTE_FRAMES asks getCommonSuperClass whenever two branches meet, and ASM's default answers it by loading
    // both classes. Minecraft's classes are not loadable while a port is being written — this runs in preLaunch,
    // before the game is up — so the default would throw on any recipe whose branches push different subtypes.
    // Every reference type here is an InteractionResult, which is the answer, and Object is the safe fallback.
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
      @Override protected String getCommonSuperClass(String a, String b) {
        if (a.equals(b)) return a;
        boolean aResult = a.startsWith(RESULT), bResult = b.startsWith(RESULT);
        return aResult && bResult ? RESULT : "java/lang/Object";
      }
    };
    w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, HOLDER, null, "java/lang/Object", null);
    w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "field_5815", "L" + RESULT + ";", null, null).visitEnd();
    w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "field_5816", "Ljava/lang/Object;", null, null).visitEnd();

    MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + RESULT + ";Ljava/lang/Object;)V", null, null);
    c.visitCode();
    c.visitVarInsn(Opcodes.ALOAD, 0);
    c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ALOAD, 1);
    c.visitFieldInsn(Opcodes.PUTFIELD, HOLDER, "field_5815", "L" + RESULT + ";");
    c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ALOAD, 2);
    c.visitFieldInsn(Opcodes.PUTFIELD, HOLDER, "field_5816", "Ljava/lang/Object;");
    c.visitInsn(Opcodes.RETURN); c.visitMaxs(0, 0); c.visitEnd();

    getter(w, "method_5467", "()L" + RESULT + ";", "field_5815", "L" + RESULT + ";");   // getResult
    getter(w, "method_5466", "()Ljava/lang/Object;", "field_5816", "Ljava/lang/Object;");  // getObject
    factory(w, "method_22427", success);                                                // success
    factory(w, "method_22428", consume);                                                // consume
    factory(w, "method_22430", pass);                                                   // pass
    factory(w, "method_22431", fail);                                                    // fail

    // sidedSuccess(o, isClientSide): the client gets a swinging success, the server the silent one. 1.21.1 spelled
    // the second SUCCESS_NO_ITEM_USED; the version that deleted this class calls it SUCCESS_SERVER. Where a target
    // has no such constant, both sides get the plain success rather than the wrong one.
    MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "method_29237",
        "(Ljava/lang/Object;Z)L" + HOLDER + ";", null, null);
    s.visitCode();
    s.visitTypeInsn(Opcodes.NEW, HOLDER); s.visitInsn(Opcodes.DUP);
    org.objectweb.asm.Label serverSide = new org.objectweb.asm.Label();
    org.objectweb.asm.Label done = new org.objectweb.asm.Label();
    s.visitVarInsn(Opcodes.ILOAD, 1);
    s.visitJumpInsn(Opcodes.IFEQ, serverSide);
    pushConstant(s, success);
    s.visitJumpInsn(Opcodes.GOTO, done);
    s.visitLabel(serverSide);
    pushConstant(s, server != null ? server : success);
    s.visitLabel(done);
    s.visitVarInsn(Opcodes.ALOAD, 0);
    s.visitMethodInsn(Opcodes.INVOKESPECIAL, HOLDER, "<init>", "(L" + RESULT + ";Ljava/lang/Object;)V", false);
    s.visitInsn(Opcodes.ARETURN); s.visitMaxs(0, 0); s.visitEnd();

    w.visitEnd();
    return w.toByteArray();
  }

  private static void getter(ClassWriter w, String name, String desc, String field, String fieldDesc) {
    MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
    m.visitCode();
    m.visitVarInsn(Opcodes.ALOAD, 0);
    m.visitFieldInsn(Opcodes.GETFIELD, HOLDER, field, fieldDesc);
    m.visitInsn(Opcodes.ARETURN); m.visitMaxs(0, 0); m.visitEnd();
  }

  /** {@code static Holder f(Object o) { return new Holder(<constant>, o); }} */
  private static void factory(ClassWriter w, String name, String constant) {
    MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
        "(Ljava/lang/Object;)L" + HOLDER + ";", null, null);
    m.visitCode();
    m.visitTypeInsn(Opcodes.NEW, HOLDER); m.visitInsn(Opcodes.DUP);
    pushConstant(m, constant);
    m.visitVarInsn(Opcodes.ALOAD, 0);
    m.visitMethodInsn(Opcodes.INVOKESPECIAL, HOLDER, "<init>", "(L" + RESULT + ";Ljava/lang/Object;)V", false);
    m.visitInsn(Opcodes.ARETURN); m.visitMaxs(0, 0); m.visitEnd();
  }

  /** {@code <name>:<descriptor>} read at its real type; the field is a subtype of InteractionResult, so no cast. */
  private static void pushConstant(MethodVisitor m, String nameAndDesc) {
    int at = nameAndDesc.indexOf(':');
    m.visitFieldInsn(Opcodes.GETSTATIC, RESULT, nameAndDesc.substring(0, at), nameAndDesc.substring(at + 1));
  }
}
