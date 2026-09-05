// Rewrite class + method + field references inside a .class file using ASM's Remapper API.
//
// Composes two rename sources: the intermediary bridge (class + method + field, sourced from
// Fabric intermediary × Mojang) and Fox-Grade's rules table (class-only, Mojang → target-Mojang
// for classes that moved between versions). The bridge translates intermediary to Mojang first;
// the rules pass then walks the same class references to project further moves onto the target.
//
// Two-stage application is done in one ASM pass by merging the class maps: rules override
// bridge on class collisions (a verified Fox-Grade rename is a deliberate override). Method
// and field names come from the bridge only — Fox-Grade's rules currently do not carry those.
//
// Important: `new ClassWriter(reader, ...)` copies bytecode chunks (including untouched
// constant-pool entries) VERBATIM from the reader. For ClassRemapper's rewrites to apply
// universally, ClassWriter must build a fresh constant pool from ClassRemapper's mapped values
// — so we skip the reader arg. COMPUTE_MAXS is enough (we don't change control flow) and avoids
// the classpath lookup COMPUTE_FRAMES would need for common-superclass computation.
package foxgrade;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BytecodeRemapper {
  private final BridgeRemapper remapper;
  private final Map<String, Map<String, String[]>> callRedirects;
  private final Map<String, Map<String, FabricApiBridges.CtorAdapter>> ctorAdapters;
  // Shim classes actually needed by redirects that fired — the pipeline injects these.
  private final Set<String> usedShims = new HashSet<>();

  public BytecodeRemapper(IntermediaryBridge bridge, Map<String, String> rulesClassTable, FabricApiBridges apiBridges) {
    Map<String, String> mergedClasses = new HashMap<>(bridge.size() + rulesClassTable.size());
    mergedClasses.putAll(bridge.classTable());
    mergedClasses.putAll(rulesClassTable);       // rules override bridge on class collisions
    mergedClasses.putAll(apiBridges.classRenames());   // curated third-party class renames
    // Compose rules onto bridge VALUES: the bridge maps intermediary → the class's 1.21-era
    // mojang home in ONE hop, so a later rules entry keyed on that home never fires for
    // intermediary-named mods. Rewriting each value through the rules table closes the chain
    // (class_1920 → world/level/BlockAndTintGetter → client/renderer/block/BlockAndTintGetter).
    mergedClasses.replaceAll((k, v) -> rulesClassTable.getOrDefault(v, v));
    this.remapper = new BridgeRemapper(mergedClasses, bridge.methodTable(), bridge.fieldTable(),
        bridge.globalMethodTable(), bridge.globalFieldTable(), apiBridges.renames(),
        bridge.mojangMethodTable(), bridge.mojangMethodGlobalTable(),
        bridge.yarnMethodTable(), bridge.yarnFieldTable(), apiBridges.inheritedRenames());
    this.callRedirects = apiBridges.callRedirects();
    this.ctorAdapters = apiBridges.ctorAdapters();
  }

  public Set<String> usedShims() { return usedShims; }

  public byte[] remap(byte[] classBytes) {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    // Redirect stage runs INSIDE the chain, before the remapper stage writes: call sites whose
    // (owner, name+desc) match a redirect entry are rewritten to the replacement target — a
    // Fox-Grade shim reimplementing an API that no longer exists.
    ClassVisitor redirect = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        MethodVisitor down = super.visitMethod(access, name, desc, sig, ex);
        return new MethodVisitor(Opcodes.ASM9, down) {
          @Override public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
            Map<String, String[]> byOwner = callRedirects.get(owner);
            String[] to = byOwner != null ? byOwner.get(mname + mdesc) : null;
            if (to != null) {
              usedShims.add(to[0]);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
              return;
            }
            // Constructor-signature adapter: an INVOKESPECIAL <init> whose (owner, desc) match a
            // table entry is rewritten to the target version's descriptor. Arguments sit on the
            // stack above the uninitialized ref — an uninitialized object can't be passed to any
            // helper, so the adaptation happens in place: spill every argument to high locals in
            // reverse, reload them in order routing the changed slots through their shim
            // suppliers, then call the new constructor. Same arity only, by design.
            if (opcode == Opcodes.INVOKESPECIAL && mname.equals("<init>")) {
              Map<String, FabricApiBridges.CtorAdapter> byCtor = ctorAdapters.get(owner);
              FabricApiBridges.CtorAdapter ad = byCtor != null ? byCtor.get(mdesc) : null;
              if (ad != null) {
                org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mdesc);
                int base = 400;   // far above any real method's locals; COMPUTE_MAXS sizes the frame
                int[] slotAt = new int[args.length];
                int idx = base;
                for (int i = 0; i < args.length; i++) { slotAt[i] = idx; idx += args[i].getSize(); }
                for (int i = args.length - 1; i >= 0; i--) {
                  super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slotAt[i]);
                }
                for (int i = 0; i < args.length; i++) {
                  super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slotAt[i]);
                  for (var t : ad.transforms()) {
                    if (t.slot() == i) {
                      usedShims.add(t.viaOwner());
                      super.visitMethodInsn(Opcodes.INVOKESTATIC, t.viaOwner(), t.viaName(), t.viaDesc(), false);
                    }
                  }
                }
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ad.newDesc(), false);
                return;
              }
            }
            super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
          }
        };
      }
    };
    ClassRemapper visitor = new ClassRemapper(redirect, remapper);
    reader.accept(visitor, 0);
    byte[] out = writer.toByteArray();
    // Same-bytes identity check keeps a spuriously-rewritten class from bloating the output jar
    // when the remapper had nothing to change.
    if (out.length == classBytes.length) {
      boolean same = true;
      for (int i = 0; i < out.length; i++) if (out[i] != classBytes[i]) { same = false; break; }
      if (same) return classBytes;
    }
    return out;
  }
}
