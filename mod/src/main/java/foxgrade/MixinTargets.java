package foxgrade;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** The classes a mixin targets: {@code @Mixin(value = {X.class}, targets = {"a.b.C"})}. Read from the remapped
 *  bytes so class targets are already in target-version names; string targets are returned as written. */
public final class MixinTargets {
  private MixinTargets() {}
  public static List<String> of(byte[] classBytes) {
    List<String> out = new ArrayList<>();
    try {
      new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) return null;
          return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitArray(String name) {
              if (!"value".equals(name) && !"targets".equals(name)) return null;
              return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object value) {
                  if (value instanceof Type t) out.add(t.getInternalName());
                  else if (value instanceof String s) out.add(s.replace('.', '/'));
                }
              };
            }
          };
        }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (Exception ignore) { }
    return out;
  }
}
