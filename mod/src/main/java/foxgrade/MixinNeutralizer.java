package foxgrade;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** A mixin deregistered because its target class is gone must still be loadable: accessor mixins are
 *  referenced directly by mod code (`ItemPropertiesAccessor.callRegister(...)`), and Mixin refuses to load an
 *  unregistered mixin. Strip the {@code @Mixin} annotation so it becomes a plain class, and give its static
 *  {@code @Invoker}/{@code @Accessor} methods default-returning bodies instead of the throwing placeholders. */
public final class MixinNeutralizer {
  private MixinNeutralizer() {}
  private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
  private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;", ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
  public static byte[] neutralize(byte[] classBytes) {
    ClassReader cr = new ClassReader(classBytes);
    ClassWriter cw = new ClassWriter(cr, 0);
    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
      @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) { return MIXIN.equals(desc) ? null : super.visitAnnotation(desc, visible); }
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
        if ((access & Opcodes.ACC_STATIC) == 0 || (access & Opcodes.ACC_ABSTRACT) != 0) return mv;
        return new MethodVisitor(Opcodes.ASM9, mv) {
          boolean generated;
          @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            if (INVOKER.equals(d) || ACCESSOR.equals(d)) generated = true;
            return super.visitAnnotation(d, visible);
          }
          @Override public void visitCode() {
            super.visitCode();
            if (!generated) return;
            // replace the whole body: return the type's default value
            Type ret = Type.getReturnType(desc);
            switch (ret.getSort()) {
              case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
              case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN); }
              case Type.LONG -> { mv.visitInsn(Opcodes.LCONST_0); mv.visitInsn(Opcodes.LRETURN); }
              case Type.FLOAT -> { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN); }
              case Type.DOUBLE -> { mv.visitInsn(Opcodes.DCONST_0); mv.visitInsn(Opcodes.DRETURN); }
              default -> { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); }
            }
            mv.visitMaxs(2, Math.max(1, Type.getArgumentsAndReturnSizes(desc) >> 2));
            mv.visitEnd();
            // swallow the original body
            mv = new MethodVisitor(Opcodes.ASM9) { };
          }
          @Override public void visitMaxs(int s, int l) { if (!generated) super.visitMaxs(s, l); }
          @Override public void visitEnd() { if (!generated) super.visitEnd(); }
        };
      }
    }, 0);
    return cw.toByteArray();
  }
}
