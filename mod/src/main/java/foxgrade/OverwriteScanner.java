// Find @Overwrite methods whose target member no longer exists in the target MC.
//
// @Overwrite (and @Shadow) members carry no refmap selector — Mixin resolves them by the
// DECLARED method name against the mixin's target class. When the target MC renamed or removed
// that member, the apply fails with "@Overwrite method X was not located in the target class"
// and the whole game goes down. The refmap pre-scan can't see these, so this scanner reads the
// emitted (already-remapped) mixin class directly:
//
//   · the @Mixin annotation's `value` (Type[]) and `targets` (String[]) name the target classes
//     — already in 26.2 form, because ClassRemapper rewrites Type annotation values
//   · each method carrying an @Overwrite annotation must exist (name + descriptor) on at least
//     one target class; if no target has it, the method is stripped
//
// @Shadow methods get the same treatment. Shadow FIELDS with missing targets still fatal — field
// stripping isn't implemented; those stay visible in the report as unresolved warnings.
package foxgrade;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OverwriteScanner {

  public static final class Broken { public final String name, desc; Broken(String n, String d) { name = n; desc = d; } }

  public static final class Result {
    public final List<Broken> strippableMethods = new ArrayList<>();
    public final boolean fatallyBroken;                 // a @Shadow FIELD is missing — can't repair surgically
    Result(boolean f) { fatallyBroken = f; }
  }

  private static final String INJECTION_PKG = "Lorg/spongepowered/asm/mixin/injection/";

  // inventory: target-MC member sets. Methods with missing targets are strippable; a missing
  // @Shadow FIELD marks the whole mixin class fatally broken (field declarations can't be
  // stripped without breaking every handler that reads them) — the caller deregisters the
  // class from its mixin config instead. refmapForClass translates raw annotation selector
  // strings; may be empty.
  public static Result scan(byte[] classBytes, AutoBlocklistFromRefmap inventory) {
    return scan(classBytes, inventory, Map.of());
  }

  public static Result scan(byte[] classBytes, AutoBlocklistFromRefmap inventory, Map<String, String> refmapForClass) {
    ClassNode cn = new ClassNode();
    new ClassReader(classBytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    List<String> targets = mixinTargets(cn);
    if (targets.isEmpty()) return new Result(false);
    boolean fatal = false;
    if (cn.fields != null) {
      for (var fn : cn.fields) {
        boolean isShadow = false;
        if (fn.invisibleAnnotations != null)
          for (AnnotationNode an : fn.invisibleAnnotations) if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(an.desc)) isShadow = true;
        if (fn.visibleAnnotations != null)
          for (AnnotationNode an : fn.visibleAnnotations) if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(an.desc)) isShadow = true;
        if (!isShadow) continue;
        boolean found = false, judgeable = false;
        for (String t : targets) {
          Set<String> fs = inventory.fieldSet(t);
          if (fs == null) continue;
          judgeable = true;
          if (fs.contains(fn.name + ":" + fn.desc)) { found = true; break; }
        }
        if (judgeable && !found) { fatal = true; break; }
      }
    }
    Result r = new Result(fatal);
    if (fatal) return r;                                 // whole class goes — no need for surgery
    // target member-name sets (names only), for judging bare-name injector selectors
    Set<String> targetMethodNames = new HashSet<>();
    boolean anyTargetKnown = false;
    for (String t : targets) {
      Set<String> ms = inventory.methodSet(t);
      if (ms == null) continue;
      anyTargetKnown = true;
      for (String sig : ms) {
        int p = sig.indexOf('(');
        if (p > 0) { targetMethodNames.add(sig.substring(0, p)); targetMethodNames.add(sig); }
      }
    }
    for (MethodNode mn : cn.methods) {
      if (hasAnno(mn, "Lorg/spongepowered/asm/mixin/Overwrite;")
          || hasAnno(mn, "Lorg/spongepowered/asm/mixin/Shadow;")) {
        boolean found = false, judgeable = false;
        for (String t : targets) {
          Set<String> ms = inventory.methodSet(t);
          if (ms == null) continue;
          judgeable = true;
          if (ms.contains(mn.name + mn.desc)) { found = true; break; }
        }
        if (judgeable && !found) r.strippableMethods.add(new Broken(mn.name, mn.desc));
        continue;
      }
      // @Invoker/@Accessor (generated accessor mixins): the target member is derived from the
      // annotation value or the method's own name (invokeParseCommand → parseCommand). When the
      // target is gone in 26.2, Mixin fatals with InvalidAccessorException — strip the accessor
      // declaration; only code paths that USED it are lost.
      AnnotationNode gen = firstGenAnno(mn);
      if (gen != null && anyTargetKnown) {
        String explicit = annoString(gen, "value");
        boolean invoker = gen.desc.endsWith("/Invoker;");
        String derived = explicit != null && !explicit.isEmpty() ? explicit : deriveTargetName(mn.name);
        if (derived != null) {
          boolean ok;
          if (invoker) {
            ok = targetMethodNames.contains(derived + mn.desc) || targetMethodNames.contains(derived);
          } else {
            // @Accessor → field. Getter desc "()T" → T; setter "(T)V" → T.
            String t = mn.desc.startsWith("()") ? mn.desc.substring(2)
                : mn.desc.endsWith(")V") ? mn.desc.substring(1, mn.desc.length() - 2) : null;
            ok = false;
            if (t != null) {
              for (String tc : targets) {
                Set<String> fs = inventory.fieldSet(tc);
                if (fs != null && fs.contains(derived + ":" + t)) { ok = true; break; }
              }
            } else ok = true;   // can't derive — don't judge
          }
          if (!ok) { r.strippableMethods.add(new Broken(mn.name, mn.desc)); continue; }
        }
      }
      // Injector handlers (@Inject/@Redirect/@ModifyArg/…): their `method` list names target
      // methods on the mixin's target class, sometimes BARE ("realmsButtonClicked",
      // "lambda$createNormalMenuOptions$8") — no owner, so the refmap pre-scan can't judge
      // them. Here the targets are known: if every name in the method list is absent from
      // every known target, the injection can only fatal — strip the handler.
      AnnotationNode inj = firstInjectionAnno(mn);
      if (inj == null || !anyTargetKnown) continue;
      List<String> methodSelectors = annoStringList(inj, "method");
      if (methodSelectors.isEmpty()) continue;
      boolean anyResolvable = false;
      for (String raw : methodSelectors) {
        String sel = refmapForClass.getOrDefault(raw, raw);
        if (sel.startsWith("L") && sel.contains(";")) {
          // owner-qualified — the refmap pre-scan path already judges these; treat as resolvable
          if (inventory.targetExists(sel)) { anyResolvable = true; break; }
          continue;
        }
        int p = sel.indexOf('(');
        if (p > 0) {
          // Descriptor given → Mixin requires an exact signature match; a name-only hit still
          // fatals with "Scanned 0 target(s)". Only the exact form counts as resolvable.
          if (targetMethodNames.contains(sel)) { anyResolvable = true; break; }
          continue;
        }
        if (targetMethodNames.contains(sel)) { anyResolvable = true; break; }
      }
      if (!anyResolvable) {
        if (System.getenv("FOXGRADE_DEBUG_STRIP") != null) System.err.println("[strip-debug] " + cn.name + "#" + mn.name + " selectors=" + methodSelectors + " targets=" + targets + " known=" + anyTargetKnown + " sample=" + targetMethodNames.stream().filter(x -> x.startsWith("<init>")).limit(3).toList());
        r.strippableMethods.add(new Broken(mn.name, mn.desc));
      }
    }
    return r;
  }

  private static final String GEN_PKG = "Lorg/spongepowered/asm/mixin/gen/";

  private static AnnotationNode firstGenAnno(MethodNode mn) {
    if (mn.visibleAnnotations != null)
      for (AnnotationNode an : mn.visibleAnnotations) if (an.desc.startsWith(GEN_PKG)) return an;
    if (mn.invisibleAnnotations != null)
      for (AnnotationNode an : mn.invisibleAnnotations) if (an.desc.startsWith(GEN_PKG)) return an;
    return null;
  }

  private static String annoString(AnnotationNode an, String key) {
    if (an.values == null) return null;
    for (int i = 0; i + 1 < an.values.size(); i += 2)
      if (key.equals(an.values.get(i)) && an.values.get(i + 1) instanceof String s) return s;
    return null;
  }

  // Mixin's accessor-name derivation: invokeFoo/callFoo → foo, getFoo/isFoo/setFoo → foo.
  private static String deriveTargetName(String accessor) {
    for (String prefix : new String[]{"invoke", "call", "get", "is", "set"}) {
      if (accessor.length() > prefix.length() && accessor.startsWith(prefix)
          && Character.isUpperCase(accessor.charAt(prefix.length()))) {
        String rest = accessor.substring(prefix.length());
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
      }
    }
    return null;
  }

  private static AnnotationNode firstInjectionAnno(MethodNode mn) {
    if (mn.visibleAnnotations != null)
      for (AnnotationNode an : mn.visibleAnnotations) if (an.desc.startsWith(INJECTION_PKG)) return an;
    if (mn.invisibleAnnotations != null)
      for (AnnotationNode an : mn.invisibleAnnotations) if (an.desc.startsWith(INJECTION_PKG)) return an;
    return null;
  }

  private static List<String> annoStringList(AnnotationNode an, String key) {
    List<String> out = new ArrayList<>();
    if (an.values == null) return out;
    for (int i = 0; i + 1 < an.values.size(); i += 2) {
      if (!key.equals(an.values.get(i))) continue;
      Object val = an.values.get(i + 1);
      if (val instanceof String s) out.add(s);
      else if (val instanceof List<?> list) for (Object o : list) if (o instanceof String s2) out.add(s2);
    }
    return out;
  }

  private static List<String> mixinTargets(ClassNode cn) {
    List<String> targets = new ArrayList<>();
    if (cn.invisibleAnnotations == null) return targets;
    for (AnnotationNode an : cn.invisibleAnnotations) {
      if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(an.desc) || an.values == null) continue;
      for (int i = 0; i + 1 < an.values.size(); i += 2) {
        String key = (String) an.values.get(i);
        Object val = an.values.get(i + 1);
        if ("value".equals(key) && val instanceof List<?> list) {
          for (Object o : list) if (o instanceof Type t) targets.add(t.getInternalName());
        } else if ("targets".equals(key) && val instanceof List<?> list) {
          for (Object o : list) if (o instanceof String s) targets.add(s.replace('.', '/'));
        }
      }
    }
    return targets;
  }

  private static boolean hasAnno(MethodNode mn, String desc) {
    if (mn.invisibleAnnotations != null)
      for (AnnotationNode an : mn.invisibleAnnotations) if (desc.equals(an.desc)) return true;
    if (mn.visibleAnnotations != null)
      for (AnnotationNode an : mn.visibleAnnotations) if (desc.equals(an.desc)) return true;
    return false;
  }
}
