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

import java.util.HashMap;
import java.util.Map;
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
    public final Set<String> strippedKeys;                // name+desc
    Result(byte[] b, Set<String> s) { this(b, s, new HashSet<>()); }
    Result(byte[] b, Set<String> s, Set<String> k) { bytes = b; strippedNames = s; strippedKeys = k; }
  }

  // predicate(name, desc) => true to strip
  /** Strips every method matching {@code predicate}, and — transitively — every method of the same class that
   *  calls a stripped one: a mixin handler that invokes a removed shadow or a stripped helper can never run, and
   *  Mixin refuses the whole mixin ("Error resolving INVOKEVIRTUAL") if the dangling call is left in place. */
  public static Result strip(byte[] classBytes, BiPredicate<String, String> predicate) {
    ClassReader reader = new ClassReader(classBytes);
    String[] self = {null};
    Map<String, Set<String>> selfCalls = new HashMap<>();
    Set<String> matched = new HashSet<>();
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public void visit(int v, int a, String name, String sig, String sup, String[] itf) { self[0] = name; }
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        String key = name + desc;
        if (predicate.test(name, desc)) matched.add(key);
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
            if (owner.equals(self[0])) selfCalls.computeIfAbsent(key, k -> new HashSet<>()).add(n + d);
          }
          @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... args) {
            for (Object o : args) if (o instanceof org.objectweb.asm.Handle h && h.getOwner().equals(self[0])) selfCalls.computeIfAbsent(key, k -> new HashSet<>()).add(h.getName() + h.getDesc());
          }
        };
      }
    }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    boolean grew = true;
    while (grew) {
      grew = false;
      for (var e : selfCalls.entrySet()) {
        String caller = e.getKey();
        if (matched.contains(caller) || caller.startsWith("<init>") || caller.startsWith("<clinit>")) continue;
        for (String callee : e.getValue()) if (matched.contains(callee)) { matched.add(caller); grew = true; break; }
      }
    }
    if (matched.isEmpty()) return new Result(null, new HashSet<>());
    return strip0(classBytes, (n, d) -> matched.contains(n + d));
  }

  /** Methods of this class that invoke a member listed in {@code strippedByClass} (owner → name+desc) on ANOTHER class —
   *  a subclass mixin calling a shadow its parent mixin lost. */
  public static Set<String> callersOf(byte[] classBytes, Map<String, Set<String>> strippedByClass) {
    Set<String> out = new HashSet<>();
    ClassReader reader = new ClassReader(classBytes);
    String[] self = {null};
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public void visit(int v, int a, String name, String sig, String sup, String[] itf) { self[0] = name; }
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        String key = name + desc;
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
            if (owner.equals(self[0])) return;
            Set<String> gone = strippedByClass.get(owner);
            if (gone != null && gone.contains(n + d)) out.add(key);
          }
          @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... args) {
            for (Object o : args) if (o instanceof org.objectweb.asm.Handle h && !h.getOwner().equals(self[0])) {
              Set<String> gone = strippedByClass.get(h.getOwner());
              if (gone != null && gone.contains(h.getName() + h.getDesc())) out.add(key);
            }
          }
        };
      }
    }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return out;
  }

  private static Result strip0(byte[] classBytes, BiPredicate<String, String> predicate) {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = new ClassWriter(reader, 0);
    Set<String> stripped = new HashSet<>(), keys = new HashSet<>();
    ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (predicate.test(name, desc)) { stripped.add(name); keys.add(name + desc); return null; }   // returning null tells ASM to skip
        return super.visitMethod(access, name, desc, signature, exceptions);
      }
    };
    reader.accept(cv, 0);
    return stripped.isEmpty() ? new Result(null, stripped, keys) : new Result(writer.toByteArray(), stripped, keys);
  }
}
