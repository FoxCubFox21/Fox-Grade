package foxgrade;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injectors that cannot apply on the target version must not be fatal: every {@code require = N} on an injector
 *  annotation becomes 0 (the config's {@code injectors.defaultRequire} is zeroed separately). Mixin then skips the
 *  failed injection and logs it; the rest of the mixin, and the mod, keep working. */
public final class MixinRequireZeroer {
  private MixinRequireZeroer() {}
  private static final java.util.Set<String> INJECTORS = java.util.Set.of(
      "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
      "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
      "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
      "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
      "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
  public static boolean isInjector(String annotationDesc) { return INJECTORS.contains(annotationDesc); }
  public static byte[] zero(byte[] classBytes) {
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
              @Override public void visitEnd() { if (!sawRequire) super.visit("require", 0); super.visitEnd(); }
            };
          }
        };
      }
    }, 0);
    return cw.toByteArray();
  }
}
