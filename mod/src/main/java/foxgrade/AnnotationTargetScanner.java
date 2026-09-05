// Find mixin handler methods whose annotations reference broken injection targets.
//
// A mixin handler's targets live in its ANNOTATIONS (@Inject method=..., @At target=...), stored
// as plain string constants. The refmap maps those raw strings to resolved selectors. When a
// resolved selector points at a method the target MC no longer has (removed, or its signature
// changed), the injection fatals at apply time and takes the whole game down.
//
// The refmap alone cannot say WHICH handler owns a broken selector — its keys are the raw
// annotation strings, not handler names. So this scanner walks each mixin class's method
// annotations with ASM, collects every string value (recursively through nested @At annotations
// and arrays), and reports methods whose annotation strings include any known-broken raw key.
//
// Used by TransformPipeline: refmap pre-scan computes the broken raw keys per mixin class; this
// scanner turns them into concrete (methodName, methodDesc) pairs for MethodStripper.
package foxgrade;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AnnotationTargetScanner {

  public static final class BrokenHandler {
    public final String name; public final String desc;
    BrokenHandler(String n, String d) { name = n; desc = d; }
  }

  // Returns the methods in this class whose annotations contain any of `brokenKeys`.
  public static List<BrokenHandler> scan(byte[] classBytes, Set<String> brokenKeys) {
    List<BrokenHandler> out = new ArrayList<>();
    if (brokenKeys.isEmpty()) return out;
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        Set<String> collected = new HashSet<>();
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitAnnotation(String annoDesc, boolean visible) {
            return collector(collected);
          }
          @Override public void visitEnd() {
            for (String s : collected) {
              if (brokenKeys.contains(s)) { out.add(new BrokenHandler(name, desc)); return; }
            }
          }
        };
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return out;
  }

  // AnnotationVisitor that pours every string value — at any nesting depth — into `sink`.
  private static AnnotationVisitor collector(Set<String> sink) {
    return new AnnotationVisitor(Opcodes.ASM9) {
      @Override public void visit(String name, Object value) {
        if (value instanceof String s) sink.add(s);
      }
      @Override public AnnotationVisitor visitAnnotation(String name, String desc) { return collector(sink); }
      @Override public AnnotationVisitor visitArray(String name) { return collector(sink); }
    };
  }
}
