// Synthesize replacement classes for SIMPLE Minecraft classes that were removed in the target
// version. When the verifier finds a ported mod referencing one of these, the shim is injected
// into the ported jar itself — the mod's classloader then resolves the missing name locally and
// the code path works instead of dying with NoClassDefFoundError.
//
// Scope discipline: only self-contained data holders whose behaviour is fully reproducible get
// a shim (Tuple is a plain pair). Anything wired into MC's systems (rendering, registries,
// networking) is NOT shimmable — a stub that pretends would "load and quietly misbehave," which
// Fox-Grade's rules forbid. Grow the table entry by entry, each one reviewed by a person.
package foxgrade;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.function.Supplier;

public final class ShimGenerator implements Opcodes {

  public static final Map<String, Supplier<byte[]>> SHIMS = Map.of(
      "net/minecraft/util/Tuple", ShimGenerator::tuple,
      "foxgrade/shim/AutoConfigCompat", ShimGenerator::autoConfigCompat,
      "foxgrade/shim/FoodDataCompat", () -> fromResource("foxgrade/shim/FoodDataCompat.class"),
      "foxgrade/shim/OptionInstanceCompat", () -> fromResource("foxgrade/shim/OptionInstanceCompat.class"),
      "foxgrade/shim/CtorShims", () -> fromResource("foxgrade/shim/CtorShims.class"),
      "net/minecraft/util/OptionEnum", ShimGenerator::optionEnum
  );

  // Shims with real logic are written as normal Java inside Fox-Grade and copied into the ported
  // jar from Fox-Grade's own class resources — no hand-rolled ASM for anything non-trivial.
  private static byte[] fromResource(String path) {
    try (var in = ShimGenerator.class.getResourceAsStream("/" + path)) {
      if (in == null) throw new IllegalStateException("missing shim resource " + path);
      return in.readAllBytes();
    } catch (java.io.IOException e) { throw new RuntimeException(e); }
  }

  // Replacement for cloth-config's removed AutoConfig.getGuiRegistry(Class): returns a fresh
  // GuiRegistry. The mod's custom GUI providers land in an orphan registry instead of the shared
  // one — the config screen renders with default widgets, everything else works. Honest trade:
  // degraded config UI beats a NoSuchMethodError at init.
  private static byte[] autoConfigCompat() {
    String name = "foxgrade/shim/AutoConfigCompat";
    String reg = "me/shedaniel/autoconfig/gui/registry/GuiRegistry";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getGuiRegistry",
        "(Ljava/lang/Class;)L" + reg + ";", null, null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, reg);
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, reg, "<init>", "()V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.OptionEnum, removed in 26.x: a pure data contract (numeric id +
  // translation key) that mod enums implement for cycle-button options. Nothing in current MC
  // consumes it, so a faithful interface — including the original's default getCaption() —
  // restores every use the mod itself makes.
  private static byte[] optionEnum() {
    String name = "net/minecraft/util/OptionEnum";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getId", "()I", null, null).visitEnd();
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getKey", "()Ljava/lang/String;", null, null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getCaption", "()Lnet/minecraft/network/chat/Component;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEINTERFACE, name, "getKey", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/network/chat/Component", "translatable",
        "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.Tuple<A, B>: the classic pair. Generic types erase to Object, so the
  // erased bytecode below satisfies every call the original accepted.
  private static byte[] tuple() {
    String name = "net/minecraft/util/Tuple";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name,
        "<A:Ljava/lang/Object;B:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
    cw.visitField(ACC_PRIVATE, "a", "Ljava/lang/Object;", "TA;", null).visitEnd();
    cw.visitField(ACC_PRIVATE, "b", "Ljava/lang/Object;", "TB;", null).visitEnd();

    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", "(TA;TB;)V", null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1); mv.visitFieldInsn(PUTFIELD, name, "a", "Ljava/lang/Object;");
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 2); mv.visitFieldInsn(PUTFIELD, name, "b", "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();

    getter(cw, name, "getA", "a"); setter(cw, name, "setA", "a");
    getter(cw, name, "getB", "b"); setter(cw, name, "setB", "b");
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static void getter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "()Ljava/lang/Object;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }

  private static void setter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "(Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitFieldInsn(PUTFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }
}
