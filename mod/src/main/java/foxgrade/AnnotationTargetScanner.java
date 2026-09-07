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
import org.objectweb.asm.ClassWriter;
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

  // Injectors whose handler parameters mirror the TARGET METHOD's parameters (`method = ...`).
  private static final Set<String> METHOD_SENSITIVE = Set.of(
      "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
  // Injectors whose handler parameters mirror the INVOKED CALL's parameters (`@At(target = ...)`).
  private static final Set<String> TARGET_SENSITIVE = Set.of(
      "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
      "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
      "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;");

  public static List<BrokenHandler> scan(byte[] classBytes, Set<String> brokenKeys) { return scan(classBytes, brokenKeys, Set.of()); }

  // Returns the methods in this class whose annotations contain any of `brokenKeys` (target gone: always
  // stripped), plus those whose handler signature can no longer line up because a selector in `changedKeys`
  // (target still exists, parameter count changed) sits where this injector kind mirrors it.
  public static List<BrokenHandler> scan(byte[] classBytes, Set<String> brokenKeys, Set<String> changedKeys) {
    List<BrokenHandler> out = new ArrayList<>();
    if (brokenKeys.isEmpty() && changedKeys.isEmpty()) return out;
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        Set<String> collected = new HashSet<>();
        boolean[] mismatched = {false};
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitAnnotation(String annoDesc, boolean visible) {
            boolean methodSens = METHOD_SENSITIVE.contains(annoDesc), targetSens = TARGET_SENSITIVE.contains(annoDesc);
            if (!methodSens && !targetSens) return collector(collected);
            Set<String> methodKeys = new HashSet<>(), targetKeys = new HashSet<>();
            return new AnnotationVisitor(Opcodes.ASM9) {
              @Override public void visit(String n, Object value) { if (value instanceof String s) { collected.add(s); if ("method".equals(n) || "target".equals(n)) methodKeys.add(s); } }
              @Override public AnnotationVisitor visitArray(String n) {
                return ("method".equals(n) || "target".equals(n)) ? collector(methodKeys, collected) : collector(targetKeys, collected);
              }
              @Override public AnnotationVisitor visitAnnotation(String n, String d) { return collector(targetKeys, collected); }
              @Override public void visitEnd() {
                if (methodSens) for (String k : methodKeys) if (changedKeys.contains(k)) mismatched[0] = true;
                if (targetSens) for (String k : targetKeys) if (changedKeys.contains(k)) mismatched[0] = true;
              }
            };
          }
          @Override public void visitEnd() {
            if (mismatched[0]) { out.add(new BrokenHandler(name, desc)); return; }
            for (String s : collected) {
              if (brokenKeys.contains(s)) { out.add(new BrokenHandler(name, desc)); return; }
            }
          }
        };
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return out;
  }

  /** Injectors selected by BARE method name ({@code method = "actuallyHurt"}, no descriptor) are matched by Mixin
   *  against whatever the target class has under that name in 26.2. A handler mirrors part of the target's parameter
   *  list — an @Inject mirrors all of it before the CallbackInfo, the other injectors mirror it after their own first
   *  parameter when they capture arguments — and when the target's parameters changed, that mirror no longer lines up and
   *  the merged method fails verification. {@code candidates(target, name)} lists the 26.2 descriptors under that name
   *  (empty = unknown, leave it: require=0 turns a miss into a warning). Returns the handlers that fit none of them. */
  public static List<BrokenHandler> bareInjectMismatches(byte[] classBytes, List<String> targets,
      java.util.function.BiFunction<String, String, Set<String>> candidates, java.util.function.Function<String, String> nameMapper) {
    List<BrokenHandler> out = new ArrayList<>();
    if (targets.isEmpty()) return out;
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        Set<Integer> sugarParams = new HashSet<>();
        String[] kind = {null};
        Set<String> methodKeys = new HashSet<>();
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
            if (d.startsWith("Lcom/llamalad7/mixinextras/sugar/")) sugarParams.add(parameter);
            return null;
          }
          @Override public AnnotationVisitor visitAnnotation(String annoDesc, boolean visible) {
            if (!MixinRequireZeroer.isInjector(annoDesc)) return null;
            kind[0] = annoDesc;
            return new AnnotationVisitor(Opcodes.ASM9) {
              @Override public void visit(String n, Object value) { if ("method".equals(n) && value instanceof String s) methodKeys.add(s); }
              @Override public AnnotationVisitor visitArray(String n) { return "method".equals(n) ? collector(methodKeys, new HashSet<>()) : null; }
            };
          }
          @Override public void visitEnd() {
            if (kind[0] == null) return;
            String mirrored = mirroredArgs(kind[0], desc, sugarParams);
            String retMirror = null;                                          // @ModifyReturnValue: the first parameter IS the target's return value
            if (kind[0].endsWith("/ModifyReturnValue;")) { try { org.objectweb.asm.Type[] ps = org.objectweb.asm.Type.getArgumentTypes(desc); if (ps.length > 0) retMirror = ps[0].getDescriptor(); } catch (RuntimeException e) { } }
            if ((mirrored == null || mirrored.isEmpty()) && retMirror == null) return;   // nothing mirrored: always fits
            for (String sel : methodKeys) {
              if (sel.indexOf('(') >= 0) continue;                            // descriptor given: Mixin matches exactly
              String bare = sel.startsWith("L") && sel.indexOf(';') > 0 ? sel.substring(sel.indexOf(';') + 1) : sel;
              String mapped = nameMapper.apply(bare);
              for (String target : targets) {
                Set<String> cands = candidates.apply(target, mapped);
                if (cands == null || cands.isEmpty()) continue;
                boolean fits = false;
                for (String cd : cands) {
                  int po = cd.indexOf('('); if (po < 0) continue;
                  boolean argsOk = mirrored == null || mirrored.isEmpty() || argsOf(cd.substring(po)).equals(mirrored);
                  boolean retOk = retMirror == null || org.objectweb.asm.Type.getReturnType(cd.substring(po)).getDescriptor().equals(retMirror);
                  if (argsOk && retOk) { fits = true; break; }
                }
                if (!fits) { out.add(new BrokenHandler(name, desc)); return; }
              }
            }
          }
        };
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return out;
  }
  private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo";
  private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";
  private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
  /** The part of the handler's parameter list that mirrors the target method's parameters; null when unknown. */
  static String mirroredArgs(String injector, String handlerDesc, Set<Integer> sugarParams) {
    org.objectweb.asm.Type[] ps;
    try { ps = org.objectweb.asm.Type.getArgumentTypes(handlerDesc); } catch (RuntimeException malformed) { return null; }
    boolean inject = injector.endsWith("/Inject;");
    boolean wrapMethod = injector.endsWith("/WrapMethod;");
    if (injector.endsWith("/Redirect;") || injector.endsWith("/WrapOperation;") || injector.endsWith("/ModifyReceiver;") || injector.endsWith("/WrapWithCondition;")) return null;  // mirror a CALL, not the method
    StringBuilder sb = new StringBuilder();
    int first = inject || wrapMethod ? 0 : 1;                                  // the others carry their own value first
    for (int i = first; i < ps.length; i++) {
      String d = ps[i].getDescriptor();
      if (d.startsWith(CI)) break;                                             // Inject: everything after is sugar
      if (d.startsWith(SUGAR) || d.equals(OPERATION) || sugarParams.contains(i)) continue;
      sb.append(d);
    }
    return sb.toString();
  }
  static String argsOf(String desc) {
    StringBuilder sb = new StringBuilder();
    try { for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(desc)) sb.append(t.getDescriptor()); } catch (RuntimeException e) { return "?"; }
    return sb.toString();
  }

  /** Every {@code method} / {@code target} string under an injector annotation (including nested @At/@Slice). */
  public static Set<String> selectorStrings(byte[] classBytes) {
    Set<String> out = new HashSet<>();
    new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            return MixinRequireZeroer.isInjector(d) ? selectorCollector(out) : null;
          }
        };
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return out;
  }
  private static AnnotationVisitor selectorCollector(Set<String> sink) {
    return new AnnotationVisitor(Opcodes.ASM9) {
      @Override public void visit(String n, Object value) { if (("method".equals(n) || "target".equals(n)) && value instanceof String s) sink.add(s); }
      @Override public AnnotationVisitor visitArray(String n) {
        if ("method".equals(n) || "target".equals(n)) return new AnnotationVisitor(Opcodes.ASM9) { @Override public void visit(String nn, Object v) { if (v instanceof String s) sink.add(s); } };
        return selectorCollector(sink);   // at = [...], slice = [...]
      }
      @Override public AnnotationVisitor visitAnnotation(String n, String d) { return selectorCollector(sink); }
    };
  }
  /** Rewrite injector selector strings through {@code map} (raw → translated). */
  public static byte[] rewriteSelectors(byte[] classBytes, java.util.Map<String, String> map) {
    ClassReader r = new ClassReader(classBytes);
    ClassWriter w = new ClassWriter(r, 0);
    r.accept(new ClassVisitor(Opcodes.ASM9, w) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
        return new MethodVisitor(Opcodes.ASM9, mv) {
          @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(d, visible);
            return MixinRequireZeroer.isInjector(d) && av != null ? selectorRewriter(av, map) : av;
          }
        };
      }
    }, 0);
    return w.toByteArray();
  }
  private static AnnotationVisitor selectorRewriter(AnnotationVisitor av, java.util.Map<String, String> map) {
    return new AnnotationVisitor(Opcodes.ASM9, av) {
      @Override public void visit(String n, Object value) {
        if (("method".equals(n) || "target".equals(n)) && value instanceof String s && map.containsKey(s)) super.visit(n, map.get(s)); else super.visit(n, value);
      }
      @Override public AnnotationVisitor visitArray(String n) {
        AnnotationVisitor inner = super.visitArray(n);
        if (inner == null) return null;
        if ("method".equals(n) || "target".equals(n)) return new AnnotationVisitor(Opcodes.ASM9, inner) { @Override public void visit(String nn, Object v) { if (v instanceof String s && map.containsKey(s)) super.visit(nn, map.get(s)); else super.visit(nn, v); } };
        return selectorRewriter(inner, map);
      }
      @Override public AnnotationVisitor visitAnnotation(String n, String d) { AnnotationVisitor inner = super.visitAnnotation(n, d); return inner == null ? null : selectorRewriter(inner, map); }
    };
  }

  private static AnnotationVisitor collector(Set<String> sink, Set<String> also) {
    return new AnnotationVisitor(Opcodes.ASM9) {
      @Override public void visit(String name, Object value) { if (value instanceof String s) { sink.add(s); also.add(s); } }
      @Override public AnnotationVisitor visitAnnotation(String name, String desc) { return collector(sink, also); }
      @Override public AnnotationVisitor visitArray(String name) { return collector(sink, also); }
    };
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
