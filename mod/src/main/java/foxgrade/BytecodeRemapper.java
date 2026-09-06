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
  private final Map<String, Map<String, String[]>> fieldRedirects;
  private final Map<String, Map<String, String>> descWidenings;
  private final Map<String, Map<String, String[]>> handleRedirects;
  private final Map<String, Map<String, FabricApiBridges.CallAdapter>> callAdapters;
  private final java.util.List<FabricApiBridges.OverrideAdapter> overrideAdapters;
  private final Map<String, String[]> entryHooks;
  // Superclass oracle (mod classes from the jar being ported, Minecraft classes from the game):
  // a call whose owner is a SUBCLASS of the class a table names still gets the rewrite, the way
  // the JVM itself resolves the member upward. Constructors excluded — they are not inherited.
  private java.util.function.UnaryOperator<String> superOf = (c) -> null;
  public void setSuperOf(java.util.function.UnaryOperator<String> f) { this.superOf = f; remapper.setSuperOf(f); }
  // Chain oracles from the verifier: does a class declare a member; is a method final / implemented /
  // declared somewhere up a chain. They keep chain lookups honest (a subclass's own declaration
  // shadows an ancestor's rewrite) and drive override dropping and method synthesis.
  private java.util.function.BiPredicate<String, String> declares = (c, k) -> false, finalInChain = (c, k) -> false,
      implementedInChain = (c, k) -> false, declaredInChain = (c, k) -> false;
  public void setOracles(java.util.function.BiPredicate<String, String> declares, java.util.function.BiPredicate<String, String> finalInChain,
                         java.util.function.BiPredicate<String, String> implementedInChain, java.util.function.BiPredicate<String, String> declaredInChain) {
    this.declares = declares; this.finalInChain = finalInChain; this.implementedInChain = implementedInChain; this.declaredInChain = declaredInChain;
  }
  private final java.util.List<String> droppedOverrides = new java.util.ArrayList<>();
  public java.util.List<String> droppedOverrides() { return droppedOverrides; }
  private final java.util.List<FabricApiBridges.SuperHook> superHooks;
  private final java.util.List<FabricApiBridges.Synth> synths;
  private final Map<String, Map<String, String>> samRenames;
  public String mapClass(String internalName) { return remapper.map(internalName); }
  public String mapMethodName(String owner, String name, String desc) { return remapper.mapMethodName(owner, name, desc); }
  public String mapFieldName(String owner, String name, String desc) { return remapper.mapFieldName(owner, name, desc); }
  private <T> T lookup(Map<String, Map<String, T>> table, String owner, String key) {
    String shapeKey = key.replaceFirst("^(getstatic|putstatic|get|put) ", "");
    String o = owner;
    for (int guard = 0; o != null && guard < 48; guard++) {
      Map<String, T> m = table.get(o);
      if (m != null) { T v = m.get(key); if (v != null) return v; }
      if (declares.test(o, shapeKey)) return null;   // resolves here; an ancestor's rewrite does not apply
      o = superOf.apply(o);
    }
    return null;
  }
  // Pushes one value described by a recipe entry, in a synthesized method whose new parameters
  // start at `slot`: "pN" a parameter, "this", an int literal, or ["static", owner, name, desc, sources…].
  private void pushSource(MethodVisitor mv, String[] u, org.objectweb.asm.Type[] args, int[] slot, String className) {
    if (u.length >= 4 && u[0].equals("static")) {
      for (int k = 4; k < u.length; k++) pushSource(mv, new String[]{u[k]}, args, slot, className);
      usedShims.add(u[1]);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, u[1], u[2], u[3], false);
    } else if (u.length == 4 && u[0].equals("field")) {
      mv.visitVarInsn(Opcodes.ALOAD, slot[0]); mv.visitTypeInsn(Opcodes.CHECKCAST, u[1]); mv.visitFieldInsn(Opcodes.GETFIELD, u[1], u[2], u[3]);
    } else if (u.length == 3 && u[0].equals("this")) {
      mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, u[1], u[2], false);
    } else if (u.length == 3) {
      mv.visitVarInsn(Opcodes.ALOAD, slot[0]); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, u[0], u[1], u[2], false);
    } else if (u[0].equals("this")) {
      mv.visitVarInsn(Opcodes.ALOAD, 0);
    } else if (u[0].startsWith("p")) {
      int pi = Integer.parseInt(u[0].substring(1)) - 1; mv.visitVarInsn(args[pi].getOpcode(Opcodes.ILOAD), slot[pi]);
    } else {
      mv.visitLdcInsn(Integer.parseInt(u[0]));
    }
  }
  // Same as pushSource, but for call-site recipes: "oN" is the N-th OLD argument (spilled to
  // locals), "this" the receiver's caller instance, static entries may nest these.
  private void pushOldSource(MethodVisitor mv, String[] u, org.objectweb.asm.Type[] oldArgs, int[] slotAt) {
    if (u.length >= 4 && u[0].equals("static")) {
      for (int k = 4; k < u.length; k++) pushOldSource(mv, new String[]{u[k]}, oldArgs, slotAt);
      usedShims.add(u[1]);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, u[1], u[2], u[3], false);
    } else if (u[0].equals("this")) {
      mv.visitVarInsn(Opcodes.ALOAD, 0);
    } else if (u[0].startsWith("o")) {
      int oi = Integer.parseInt(u[0].substring(1)) - 1; mv.visitVarInsn(oldArgs[oi].getOpcode(Opcodes.ILOAD), slotAt[oi]);
    } else {
      mv.visitLdcInsn(Integer.parseInt(u[0]));
    }
  }
  private static int[] slots(org.objectweb.asm.Type[] args) {
    int[] slot = new int[args.length + 1]; slot[0] = 1;
    for (int i = 0; i < args.length; i++) slot[i + 1] = slot[i] + args[i].getSize();
    return slot;
  }
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
    this.fieldRedirects = apiBridges.fieldRedirects();
    this.descWidenings = apiBridges.descWidenings();
    this.handleRedirects = apiBridges.handleRedirects();
    this.callAdapters = apiBridges.callAdapters();
    this.overrideAdapters = apiBridges.overrideAdapters();
    this.entryHooks = apiBridges.entryHooks();
    this.superHooks = apiBridges.superHooks(); this.synths = apiBridges.synths(); this.samRenames = apiBridges.samRenames();
    this.remapper.setInheritedRenamesByAncestor(apiBridges.inheritedRenamesByAncestor());
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
      String className; int classAccess;
      final Set<String> declared = new HashSet<>();
      @Override public void visit(int version, int access, String name, String sig, String superName, String[] itfs) {
        className = name; classAccess = access; this.superName = superName;
        super.visit(version, access, name, sig, superName, itfs);
      }
      String superName;
      @Override public void visitEnd() {
        if ((classAccess & Opcodes.ACC_INTERFACE) == 0) {
          // Override adapters: the mod overrides an old-signature callback; synthesise the
          // new-signature one so the game keeps calling into it (see FabricApiBridges).
          for (var oa : overrideAdapters) {
            if (!declared.contains(oa.oldName() + oa.oldDesc()) || declared.contains(oa.newName() + oa.newDesc())) continue;
            org.objectweb.asm.Type[] newArgs = org.objectweb.asm.Type.getArgumentTypes(oa.newDesc());
            org.objectweb.asm.Type[] oldArgs = org.objectweb.asm.Type.getArgumentTypes(oa.oldDesc());
            int[] slot = slots(newArgs);
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, oa.newName(), oa.newDesc(), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            for (int i = 0; i < oa.unpack().size() && i < oldArgs.length; i++) {
              String[] u = oa.unpack().get(i);
              pushSource(mv, u, newArgs, slot, className);
              if (u.length == 3 && !u[0].equals("this") && oldArgs[i].getSort() == org.objectweb.asm.Type.CHAR
                  && org.objectweb.asm.Type.getReturnType(u[2]).getSort() == org.objectweb.asm.Type.INT) mv.visitInsn(Opcodes.I2C);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, oa.oldName(), oa.oldDesc(), false);
            for (String[] h : oa.after()) pushSource(mv, h, newArgs, slot, className);
            mv.visitInsn(org.objectweb.asm.Type.getReturnType(oa.newDesc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0); mv.visitEnd();
          }
          // Super hooks: a method the class does not declare, synthesised as "call the superclass
          // version, then these static hooks" — how a renderer's state extraction gets recorded.
          for (var sh : superHooks) {
            String key = sh.name() + sh.desc();
            if (declared.contains(key) || superName == null || !declaredInChain.test(className, key)) continue;
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(sh.desc());
            int[] slot = slots(args);
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, sh.name(), sh.desc(), null, null);
            mv.visitCode();
            if (implementedInChain.test(superName, key)) {
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              for (int i = 0; i < args.length; i++) mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot[i]);
              mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, sh.name(), sh.desc(), false);
              if (org.objectweb.asm.Type.getReturnType(sh.desc()).getSize() == 1) mv.visitInsn(Opcodes.POP);
              else if (org.objectweb.asm.Type.getReturnType(sh.desc()).getSize() == 2) mv.visitInsn(Opcodes.POP2);
            }
            for (String[] h : sh.hooks()) pushSource(mv, h, args, slot, className);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0); mv.visitEnd();
          }
          // Synthesised implementations of abstract methods the class inherited but no ancestor
          // implements (createRenderState): the body is one static call.
          for (var sy : synths) {
            String key = sy.name() + sy.desc();
            if (declared.contains(key) || superName == null || !declaredInChain.test(className, key) || implementedInChain.test(className, key)) continue;
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(sy.desc());
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, sy.name(), sy.desc(), null, null);
            mv.visitCode();
            pushSource(mv, sy.call(), args, slots(args), className);
            mv.visitInsn(org.objectweb.asm.Type.getReturnType(sy.desc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0); mv.visitEnd();
          }
        }
        super.visitEnd();
      }
      @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.startsWith("<") && (classAccess & Opcodes.ACC_INTERFACE) == 0
            && superName != null && finalInChain.test(superName, name + desc)) {
          // The game made this method final (Model.renderToBuffer, AbstractWidget.extractRenderState);
          // keeping the override would fail class loading. The final version does the equivalent job.
          droppedOverrides.add(className.substring(className.lastIndexOf('/') + 1) + "#" + name);
          return null;
        }
        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) declared.add(name + desc);
        MethodVisitor down = super.visitMethod(access, name, desc, sig, ex);
        final String[] hook = (access & Opcodes.ACC_STATIC) == 0 ? entryHooks.get(name + desc) : null;
        return new MethodVisitor(Opcodes.ASM9, down) {
          @Override public void visitCode() {
            super.visitCode();
            if (hook != null) {
              usedShims.add(hook[0]);
              super.visitVarInsn(Opcodes.ALOAD, 1);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, hook[0], hook[1], hook[2], false);
            }
          }
          @Override public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
            String[] to = mname.equals("<init>") ? null : lookup(callRedirects, owner, mname + mdesc);
            if (to != null) {
              usedShims.add(to[0]);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
              return;
            }
            FabricApiBridges.CallAdapter ca = mname.equals("<init>") ? null : lookup(callAdapters, owner, mname + mdesc);
            if (ca != null) {
              // Repack arguments: spill them all, then either fold the first K into an object by
              // the pack shim and reload the rest, or (with `args`) build every new argument from a
              // recipe of old arguments, `this`, and static helper calls.
              org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mdesc);
              int base = 400, idx = base; int[] slotAt = new int[args.length];
              for (int i = 0; i < args.length; i++) { slotAt[i] = idx; idx += args[i].getSize(); }
              for (int i = args.length - 1; i >= 0; i--) super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slotAt[i]);
              if (ca.args() != null) {
                for (String[] u : ca.args()) pushOldSource(this, u, args, slotAt);
              } else {
                int k = 0;
                if (ca.pack() != null) {
                  k = org.objectweb.asm.Type.getArgumentTypes(ca.pack()[2]).length;
                  for (int i = 0; i < k; i++) super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slotAt[i]);
                  usedShims.add(ca.pack()[0]);
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, ca.pack()[0], ca.pack()[1], ca.pack()[2], false);
                }
                for (int i = k; i < args.length; i++) super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slotAt[i]);
                for (int c : ca.extras()) super.visitLdcInsn(c);
              }
              super.visitMethodInsn(opcode, owner, ca.newName(), ca.newDesc(), itf);
              return;
            }
            String wide = lookup(descWidenings, owner, mname + mdesc);
            if (wide != null) { super.visitMethodInsn(opcode, owner, mname, wide, itf); return; }
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
                // slot -1: a NEW leading argument the constructor grew (DynamicTexture's name
                // supplier), produced by a no-arg shim; slot -2: the same, trailing.
                boolean inserted = false;
                for (var t : ad.transforms()) {
                  if (t.slot() != -1 || inserted) continue;
                  if (t.viaOwner().startsWith("p") && t.viaName().isEmpty()) {
                    // "pN": the enclosing constructor's own N-th parameter, when it has that type
                    // (a 1.21.x model constructor takes the root ModelPart 26.2's base wants).
                    org.objectweb.asm.Type[] encl = org.objectweb.asm.Type.getArgumentTypes(desc);
                    int pi = Integer.parseInt(t.viaOwner().substring(1)) - 1;
                    org.objectweb.asm.Type want = org.objectweb.asm.Type.getArgumentTypes(ad.newDesc())[0];
                    if (name.equals("<init>") && (access & Opcodes.ACC_STATIC) == 0 && pi < encl.length && encl[pi].equals(want)) {
                      int[] sl = slots(encl); super.visitVarInsn(encl[pi].getOpcode(Opcodes.ILOAD), sl[pi]); inserted = true;
                    }
                    continue;
                  }
                  usedShims.add(t.viaOwner()); super.visitMethodInsn(Opcodes.INVOKESTATIC, t.viaOwner(), t.viaName(), t.viaDesc(), false); inserted = true;
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
                for (var t : ad.transforms()) {
                  if (t.slot() == -2) { usedShims.add(t.viaOwner()); super.visitMethodInsn(Opcodes.INVOKESTATIC, t.viaOwner(), t.viaName(), t.viaDesc(), false); }
                }
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ad.newDesc(), false);
                return;
              }
            }
            super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
          }
          // Method-reference forms of the same two rewrites. `Vec3::new` or `Helper::method`
          // compiles to an invokedynamic whose bootstrap arguments carry a method handle rather
          // than a call instruction, so the handle itself is retargeted. A constructor handle can
          // only be widened when the adapter has no per-slot transforms — there is no call site
          // at which to spill and reroute arguments — which is exactly the descriptor-only case
          // (Vec3(Vector3f) -> Vec3(Vector3fc)); LambdaMetafactory accepts the reference widening.
          @Override public void visitInvokeDynamicInsn(String iname, String idesc, org.objectweb.asm.Handle bsm, Object... bsmArgs) {
            org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(idesc);
            if (ret.getSort() == org.objectweb.asm.Type.OBJECT) {
              Map<String, String> sam = samRenames.get(ret.getInternalName());
              if (sam != null && sam.containsKey(iname)) iname = sam.get(iname);
            }
            Object[] out = bsmArgs.clone();
            for (int i = 0; i < out.length; i++) {
              if (!(out[i] instanceof org.objectweb.asm.Handle h)) continue;
              String[] to = h.getName().equals("<init>") ? null : lookup(callRedirects, h.getOwner(), h.getName() + h.getDesc());
              if (to != null) {
                usedShims.add(to[0]);
                out[i] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, to[0], to[1], to[2], false);
                continue;
              }
              String[] toH = lookup(handleRedirects, h.getOwner(), h.getName() + h.getDesc());
              if (toH != null) {
                usedShims.add(toH[0]);
                out[i] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, toH[0], toH[1], toH[2], false);
                // The lambda's instantiated type still names the dead return type; align it with
                // the shim so LambdaMetafactory never resolves it.
                if (out.length > 2 && out[2] instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.METHOD
                    && t.getArgumentTypes().length == org.objectweb.asm.Type.getArgumentTypes(toH[2]).length) {
                  out[2] = org.objectweb.asm.Type.getMethodType(toH[2]);
                }
                continue;
              }
              String wideH = lookup(descWidenings, h.getOwner(), h.getName() + h.getDesc());
              if (wideH != null) { out[i] = new org.objectweb.asm.Handle(h.getTag(), h.getOwner(), h.getName(), wideH, h.isInterface()); continue; }
              if (h.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                Map<String, FabricApiBridges.CtorAdapter> byCtor = ctorAdapters.get(h.getOwner());
                FabricApiBridges.CtorAdapter ad = byCtor != null ? byCtor.get(h.getDesc()) : null;
                if (ad != null && ad.transforms().isEmpty()) {
                  out[i] = new org.objectweb.asm.Handle(h.getTag(), h.getOwner(), "<init>", ad.newDesc(), false);
                }
              }
            }
            super.visitInvokeDynamicInsn(iname, idesc, bsm, out);
          }
          // Field redirects: a field that stopped existing becomes a static call on a shim.
          // GETFIELD leaves the receiver on the stack and PUTFIELD the receiver then the value —
          // exactly the argument lists the shim's getter and setter take, so nothing is shuffled.
          @Override public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
            {
              String kind = opcode == Opcodes.GETFIELD ? "get " : opcode == Opcodes.PUTFIELD ? "put "
                  : opcode == Opcodes.GETSTATIC ? "getstatic " : "putstatic ";
              String[] to = lookup(fieldRedirects, owner, kind + fname + ":" + fdesc);
              if (to != null) {
                usedShims.add(to[0]);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
                return;
              }
            }
            super.visitFieldInsn(opcode, owner, fname, fdesc);
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
