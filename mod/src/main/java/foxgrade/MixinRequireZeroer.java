package foxgrade;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injectors that cannot apply on the target version must not be fatal: every {@code require = N} on an injector
 *  annotation becomes 0 (the config's {@code injectors.defaultRequire} is zeroed separately). Mixin then skips the
 *  failed injection and logs it; the rest of the mixin, and the mod, keep working.
 *
 *  <p>Local capture is the same problem wearing a different hat, and {@code require} does not reach it. An
 *  {@code @Inject} handler that takes extra parameters is asking Mixin to hand it the target method's local
 *  variables, and the target's locals are exactly what changes when Minecraft rewrites a method body:
 *
 *  <pre>Critical injection failure: LVT in FallingBlockEntity::tick()V has incompatible changes at opcode 399</pre>
 *
 *  <p>That is thrown, not logged, because the default capture mode is {@code CAPTURE_FAILHARD} — the right default
 *  for a mod being developed against a fixed game, and the wrong one for a mod being run against a newer one.
 *  {@code CAPTURE_FAILSOFT} asks for exactly the same capture and skips the injection when the locals no longer
 *  line up. Nothing that works today changes: a capture that succeeds is identical under either mode. */
public final class MixinRequireZeroer {
  private MixinRequireZeroer() {}
  private static final java.util.Set<String> INJECTORS = java.util.Set.of(
      "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
      "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
      "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
      "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
      "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
  private static final String LOCAL_CAPTURE = "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;";
  /** Capture modes that throw rather than skip when the target's locals have moved. */
  private static final java.util.Set<String> FATAL_CAPTURE = java.util.Set.of("CAPTURE_FAILHARD", "CAPTURE_FAILEXCEPTION");

  public static boolean isInjector(String annotationDesc) { return INJECTORS.contains(annotationDesc); }

  /** Handlers whose local capture was softened by the last {@link #zero} call, for the port report. */
  public static byte[] zero(byte[] classBytes) { return zero(classBytes, new java.util.ArrayList<>()); }

  public static byte[] zero(byte[] classBytes, java.util.List<String> softened) {
    ClassReader cr = new ClassReader(classBytes);
    ClassWriter cw = new ClassWriter(cr, 0);
    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
        return new MethodVisitor(Opcodes.ASM9, mv) {
          @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(d, visible);
            if (!INJECTORS.contains(d) || av == null) return av;
            return new AnnotationVisitor(Opcodes.ASM9, av) {
              boolean sawRequire;
              @Override public void visit(String n, Object value) { if ("require".equals(n)) { sawRequire = true; super.visit(n, 0); } else if ("expect".equals(n)) { super.visit(n, 0); } else super.visit(n, value); }
              @Override public void visitEnum(String n, String enumDesc, String value) {
                if ("locals".equals(n) && LOCAL_CAPTURE.equals(enumDesc) && FATAL_CAPTURE.contains(value)) {
                  softened.add(name + " (locals no longer line up)");
                  super.visitEnum(n, enumDesc, "CAPTURE_FAILSOFT");
                  return;
                }
                super.visitEnum(n, enumDesc, value);
              }
              @Override public void visitEnd() { if (!sawRequire) super.visit("require", 0); super.visitEnd(); }
            };
          }
        };
      }
    }, 0);
    return cw.toByteArray();
  }
}
