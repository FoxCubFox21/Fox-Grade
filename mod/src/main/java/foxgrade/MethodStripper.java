// Physically remove specific methods from a compiled class, by name.
//
// The escape hatch for mixin handlers that fatally crash on the target MC and can't be repaired
// by renaming — most often a MixinExtras @Local capture where the vanilla local-variable slot
// layout changed and the synthesised bridge fails class-load verification. Removing the one
// handler keeps the surrounding mixin (and the mod) alive; only that feature is inert.
//
// Java version uses ASM's ClassVisitor to skip the offending methods instead of hand-editing
// the constant pool the way the Node version does. Same net effect, half the code.
package foxgrade;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

public final class MethodStripper {

  public static final class Result {
    public final byte[] bytes;                       // null if nothing was stripped
    public final Set<String> strippedNames;
    Result(byte[] b, Set<String> s) { bytes = b; strippedNames = s; }
  }

  // predicate(name, desc) => true to strip
  public static Result strip(byte[] classBytes, BiPredicate<String, String> predicate) {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = new ClassWriter(reader, 0);
    Set<String> stripped = new HashSet<>();
    ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (predicate.test(name, desc)) { stripped.add(name); return null; }   // returning null tells ASM to skip
        return super.visitMethod(access, name, desc, signature, exceptions);
      }
    };
    reader.accept(cv, 0);
    return stripped.isEmpty() ? new Result(null, stripped) : new Result(writer.toByteArray(), stripped);
  }
}
