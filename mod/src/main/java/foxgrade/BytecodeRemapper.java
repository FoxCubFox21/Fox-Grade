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
  private java.util.function.Function<String, String[]> interfacesOf = (c) -> new String[0];
  public void setSuperOf(java.util.function.UnaryOperator<String> f) { this.superOf = f; remapper.setSuperOf(f); }
  public void setInterfacesOf(java.util.function.Function<String, String[]> f) { this.interfacesOf = f; remapper.setInterfacesOf(f); }
  private java.util.function.Function<String, Boolean> isInterface = (c) -> null;
  private java.util.function.Predicate<String> isFinalClass = (c) -> false;
  public void setIsFinalClass(java.util.function.Predicate<String> f) { this.isFinalClass = f; }
  public void setIsInterface(java.util.function.Function<String, Boolean> f) { this.isInterface = f; }
  private final java.util.List<String> flips = new java.util.ArrayList<>();
  /** Final classes Fox-Grade's access widener makes extendable, with the constructor a flipped subclass must call. */
  static final Map<String, String> EXTENDABLE = Map.of("net/minecraft/world/item/crafting/RecipeSerializer", "(Lcom/mojang/serialization/MapCodec;Lnet/minecraft/network/codec/StreamCodec;)V");
  /** Interfaces that became classes (or the reverse) in the target, per class rewritten. */
  public java.util.List<String> flips() { return flips; }
  // Chain oracles from the verifier: does a class declare a member; is a method final / implemented /
  // declared somewhere up a chain. They keep chain lookups honest (a subclass's own declaration
  // shadows an ancestor's rewrite) and drive override dropping and method synthesis.
  private java.util.function.BiPredicate<String, String> declares = (c, k) -> false, finalInChain = (c, k) -> false,
      implementedInChain = (c, k) -> false, declaredInChain = (c, k) -> false;
  private java.util.function.Function<String, java.util.Set<String>> abstractsOf = c -> java.util.Set.of();
  public void setAbstractsOf(java.util.function.Function<String, java.util.Set<String>> f) { this.abstractsOf = f; }
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
    // JVM resolution order, roughly: the class, its superclasses, then the interfaces of each;
    // a class that declares the member itself ends the search (an ancestor's rewrite does not apply).
    java.util.Set<String> seen = new HashSet<>();
    String o = owner;
    for (int guard = 0; o != null && guard < 48; guard++) {
      Map<String, T> m = table.get(o);
      if (m != null) { T v = m.get(key); if (v != null) return v; }
      if (declares.test(o, shapeKey)) return null;
      seen.add(o);
      o = superOf.apply(o);
    }
    java.util.ArrayDeque<String> itfs = new java.util.ArrayDeque<>();
    for (String c : seen) for (String i : interfacesOf.apply(c)) itfs.add(i);
    for (int guard = 0; !itfs.isEmpty() && guard < 200; guard++) {
      String i = itfs.poll();
      if (!seen.add(i)) continue;
      Map<String, T> m = table.get(i);
      if (m != null) { T v = m.get(key); if (v != null) return v; }
      for (String j : interfacesOf.apply(i)) itfs.add(j);
    }
    return null;
  }
  // Pushes one value described by a recipe entry, in a synthesized method whose new parameters
  // start at `slot`: "pN" a parameter, "this", an int literal, or ["static", owner, name, desc, sources…].
  private static int convOpcode(String name) {
    return switch (name) { case "F2D" -> Opcodes.F2D; case "D2F" -> Opcodes.D2F; case "I2L" -> Opcodes.I2L; case "L2I" -> Opcodes.L2I; case "I2F" -> Opcodes.I2F; case "F2I" -> Opcodes.F2I; case "I2D" -> Opcodes.I2D; case "D2I" -> Opcodes.D2I; case "I2C" -> Opcodes.I2C; default -> throw new IllegalArgumentException(name); };
  }
  private void pushSource(MethodVisitor mv, String[] u, org.objectweb.asm.Type[] args, int[] slot, String className) {
    if (u.length == 3 && u[0].equals("conv")) { pushSource(mv, new String[]{u[1]}, args, slot, className); mv.visitInsn(convOpcode(u[2])); return; }
    if (u.length == 3 && u[0].equals("cast")) { pushSource(mv, new String[]{u[1]}, args, slot, className); mv.visitTypeInsn(Opcodes.CHECKCAST, u[2]); return; }
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
  private int recvSlot = -1;   // set by a call adapter that spilled the receiver ("recv" source)
  private void pushOldSource(MethodVisitor mv, String[] u, org.objectweb.asm.Type[] oldArgs, int[] slotAt) {
    if (u.length == 3 && u[0].equals("conv")) { pushOldSource(mv, new String[]{u[1]}, oldArgs, slotAt); mv.visitInsn(convOpcode(u[2])); return; }
    if (u.length == 3 && u[0].equals("cast")) { pushOldSource(mv, new String[]{u[1]}, oldArgs, slotAt); mv.visitTypeInsn(Opcodes.CHECKCAST, u[2]); return; }
    if (u.length == 1 && u[0].equals("recv")) { mv.visitVarInsn(Opcodes.ALOAD, recvSlot); return; }
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
    this(bridge, rulesClassTable, apiBridges, true);
  }

  /** {@code toMojang} false when the target loads classes through intermediary, which is every version before 26.x:
   *  the mod already speaks that namespace and rewriting it into Mojang names makes the port unloadable. */
  public BytecodeRemapper(IntermediaryBridge bridge, Map<String, String> rulesClassTable, FabricApiBridges apiBridges,
                          boolean toMojang) {
    Map<String, String> mergedClasses = new HashMap<>(bridge.size() + rulesClassTable.size());
    // On a target whose runtime namespace is intermediary, the bridge's intermediary-to-Mojang tables are not a
    // translation the game wants — the mod already speaks the runtime's language. Everything else still applies:
    // the rules table, the curated renames, the shape fixes.
    if (toMojang) mergedClasses.putAll(bridge.classTable());
    mergedClasses.putAll(rulesClassTable);       // rules override bridge on class collisions
    mergedClasses.putAll(apiBridges.classRenames());   // curated third-party class renames
    // Compose rules onto bridge VALUES: the bridge maps intermediary → the class's 1.21-era
    // mojang home in ONE hop, so a later rules entry keyed on that home never fires for
    // intermediary-named mods. Rewriting each value through the rules table closes the chain
    // (class_1920 → world/level/BlockAndTintGetter → client/renderer/block/BlockAndTintGetter).
    mergedClasses.replaceAll((k, v) -> rulesClassTable.getOrDefault(v, v));
    mergedClasses.replaceAll((k, v) -> apiBridges.classRenames().getOrDefault(v, v));   // curated moves too
    Map<String, Map<String, String>> noPerClass = java.util.Map.of();
    Map<String, String> noGlobal = java.util.Map.of();
    this.remapper = new BridgeRemapper(mergedClasses,
        toMojang ? bridge.methodTable() : noPerClass, toMojang ? bridge.fieldTable() : noPerClass,
        toMojang ? bridge.globalMethodTable() : noGlobal, toMojang ? bridge.globalFieldTable() : noGlobal,
        apiBridges.renames(),
        toMojang ? bridge.mojangMethodTable() : noPerClass, toMojang ? bridge.mojangMethodGlobalTable() : noGlobal,
        toMojang ? bridge.yarnMethodTable() : noPerClass, toMojang ? bridge.yarnFieldTable() : noPerClass,
        apiBridges.inheritedRenames());
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

  /** Rebuild every StackMapTable from scratch. The verifier's frames must match the rewritten stack shapes, and the
   *  frames copied from the input describe the pre-rewrite ones. Common-superclass lookups go through the hierarchy
   *  oracles (no classloading); unknown pairs fall back to Object, which the verifier accepts for reference merges. */
  private byte[] recomputeFrames(byte[] bytes) {
    ClassReader r = new ClassReader(bytes);
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      @Override protected String getCommonSuperClass(String a, String b) {
        if (a.equals(b)) return a;
        Boolean ai = isInterface.apply(a), bi = isInterface.apply(b);
        if ((ai != null && ai) || (bi != null && bi)) return "java/lang/Object";
        java.util.List<String> ca = superChain(a);
        for (String x : superChain(b)) if (ca.contains(x)) return x;
        return "java/lang/Object";
      }
    };
    r.accept(w, ClassReader.SKIP_FRAMES);
    return w.toByteArray();
  }
  private java.util.List<String> superChain(String c) {
    java.util.List<String> l = new java.util.ArrayList<>();
    for (int g = 0; c != null && g < 48 && !l.contains(c); g++) { l.add(c); c = superOf.apply(c); }
    if (!l.contains("java/lang/Object")) l.add("java/lang/Object");
    return l;
  }

  /** Interfaces whose single abstract method vanished in 26.2: lambdas for them are wrapped (interface → shim owner, name). */
  private static final Map<String, String[]> SAMLESS_WRAP = Map.of(
      "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType", new String[] {"foxgrade/shim/ProcessorTypeCodec", "of"});

  public String mapDescriptor(String desc) { return remapper.mapDesc(desc); }
  public Set<String> usedShims() { return usedShims; }

  public byte[] remap(byte[] classBytes) {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    boolean[] frameDirty = {false};   // set when a rewrite changes the operand-stack shape across a branch (withheld NEW/DUP)
    // Redirect stage runs INSIDE the chain, before the remapper stage writes: call sites whose
    // (owner, name+desc) match a redirect entry are rewritten to the replacement target — a
    // Fox-Grade shim reimplementing an API that no longer exists.
    ClassVisitor redirect = new ClassVisitor(Opcodes.ASM9, writer) {
      String className; int classAccess;
      final Set<String> declared = new HashSet<>();
      final Set<String> declaredConcrete = new HashSet<>();   // declared with a body (interface default methods included)
      final java.util.List<String[]> ctorBridges = new java.util.ArrayList<>();   // [bridgeName, bridgeDesc, ctorOwner, ctorDesc]
      final java.util.List<Object[]> samBridges = new java.util.ArrayList<>();   // [bridgeName, bridgeDesc, implHandle, capturedCount, oldSamArgCount]
      @Override public void visit(int version, int access, String name, String sig, String superName, String[] itfs) {
        // 26.2 turned some interfaces into classes (RecipeSerializer). A mod class implementing one
        // must extend it instead — possible when it has no other superclass.
        if (itfs != null && itfs.length > 0 && (access & Opcodes.ACC_INTERFACE) == 0) {
          java.util.List<String> keep = new java.util.ArrayList<>();
          for (String i : itfs) {
            Boolean isI = isInterface.apply(i);
            if (isI != null && !isI) {
              if (isFinalClass.test(i) && !EXTENDABLE.containsKey(i)) { flips.add(name + "#implements " + i.substring(i.lastIndexOf('/') + 1) + " (a final class in the target; cannot be implemented or extended)"); keep.add(i); }   // kept: dropping it breaks every field typed with it
              else if (superName == null || superName.equals("java/lang/Object")) { superName = i; flips.add(name + ": implements " + i + " → extends (it is a class in the target)"); }
              else { flips.add(name + ": implements " + i + " dropped (a class in the target; this class already extends " + superName + ")"); }
            } else keep.add(i);
          }
          itfs = keep.toArray(new String[0]);
        }
        // 26.2 turned some abstract classes into interfaces (StructureProcessor): 'extends X' must become 'implements X'.
        Boolean superIsItf = superName == null ? null : isInterface.apply(superName);
        if (superIsItf != null && superIsItf && (access & Opcodes.ACC_INTERFACE) == 0) {
          java.util.List<String> l = new java.util.ArrayList<>(itfs == null ? java.util.List.of() : java.util.Arrays.asList(itfs)); l.add(superName);
          flips.add(name + ": extends " + superName + " → implements (it is an interface in the target)");
          demotedSuper = superName; superName = "java/lang/Object"; itfs = l.toArray(new String[0]);
        }
        flippedSuper = EXTENDABLE.containsKey(superName == null ? "" : superName) && (itfs == null || java.util.Arrays.asList(itfs).stream().noneMatch(superName::equals)) ? superName : null;
        className = name; classAccess = access; this.superName = superName;
        super.visit(version, access, name, sig, superName, itfs);
      }
      String superName; String flippedSuper; String demotedSuper;   // demotedSuper: old superclass that is an interface now   // set when 'implements X' became 'extends X' for an EXTENDABLE record
      @Override public void visitEnd() {
        // Bridges for callbacks whose interface gained trailing parameters: drop them, call the original implementation.
        for (Object[] b : samBridges) {
          String bdesc = (String) b[1]; org.objectweb.asm.Handle impl = (org.objectweb.asm.Handle) b[2]; int captured = (Integer) b[3], oldN = (Integer) b[4];
          org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(bdesc);
          MethodVisitor mv = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, (String) b[0], bdesc, null, null);
          mv.visitCode();
          int slot = 0;
          for (int i = 0; i < args.length; i++) { if (i < captured + oldN) mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot); slot += args[i].getSize(); }
          int op = switch (impl.getTag()) { case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC; case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE; case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL; default -> Opcodes.INVOKEVIRTUAL; };
          mv.visitMethodInsn(op, impl.getOwner(), impl.getName(), impl.getDesc(), impl.isInterface());
          org.objectweb.asm.Type r = org.objectweb.asm.Type.getReturnType(bdesc);
          mv.visitInsn(r.getSort() == org.objectweb.asm.Type.VOID ? Opcodes.RETURN : r.getOpcode(Opcodes.IRETURN));
          mv.visitMaxs(0, 0); mv.visitEnd();
        }
        for (String[] b : ctorBridges) {
          org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(b[1]);
          MethodVisitor mv = visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, b[0], b[1], null, null);
          mv.visitCode();
          mv.visitTypeInsn(Opcodes.NEW, b[2]); mv.visitInsn(Opcodes.DUP);
          int slot = 0;
          for (org.objectweb.asm.Type t : args) { mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot); slot += t.getSize(); }
          mv.visitMethodInsn(Opcodes.INVOKESPECIAL, b[2], "<init>", b[3], false);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitMaxs(0, 0); mv.visitEnd();
        }
        // Override adapters also apply to INTERFACES whose old-signature method is a default method (a reload
        // listener mix-in that implements the 1.21 reload() by default): the new signature is synthesised as a default.
        boolean itfClass = (classAccess & Opcodes.ACC_INTERFACE) != 0;
        if (itfClass) {
          for (var oa : overrideAdapters) {
            if (!declaredConcrete.contains(oa.oldName() + oa.oldDesc()) || declared.contains(oa.newName() + oa.newDesc())) continue;
            declared.add(oa.newName() + oa.newDesc());
            org.objectweb.asm.Type[] newArgs = org.objectweb.asm.Type.getArgumentTypes(oa.newDesc());
            org.objectweb.asm.Type[] oldArgs = org.objectweb.asm.Type.getArgumentTypes(oa.oldDesc());
            int[] slot = slots(newArgs);
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, oa.newName(), oa.newDesc(), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            for (int i = 0; i < oa.unpack().size() && i < oldArgs.length; i++) pushSource(mv, oa.unpack().get(i), newArgs, slot, className);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, className, oa.oldName(), oa.oldDesc(), true);
            if (oa.convert() != null) { usedShims.add(oa.convert()[1]); mv.visitMethodInsn(Opcodes.INVOKESTATIC, oa.convert()[1], oa.convert()[2], oa.convert()[3], false); }
            for (String[] h : oa.after()) pushSource(mv, h, newArgs, slot, className);
            mv.visitInsn(org.objectweb.asm.Type.getReturnType(oa.newDesc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0); mv.visitEnd();
          }
        }
        if ((classAccess & Opcodes.ACC_INTERFACE) == 0) {
          // Override adapters: the mod overrides an old-signature callback; synthesise the
          // new-signature one so the game keeps calling into it (see FabricApiBridges).
          for (var oa : overrideAdapters) {
            if (!declared.contains(oa.oldName() + oa.oldDesc()) || declared.contains(oa.newName() + oa.newDesc())) continue;
            declared.add(oa.newName() + oa.newDesc());   // a second adapter (the erased bridge signature) must not emit it again
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
            if (oa.convert() != null) { usedShims.add(oa.convert()[1]); mv.visitMethodInsn(Opcodes.INVOKESTATIC, oa.convert()[1], oa.convert()[2], oa.convert()[3], false); }
            for (String[] h : oa.after()) pushSource(mv, h, newArgs, slot, className);
            mv.visitInsn(org.objectweb.asm.Type.getReturnType(oa.newDesc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0); mv.visitEnd();
          }
          // Super hooks: a method the class does not declare, synthesised as "call the superclass
          // version, then these static hooks" — how a renderer's state extraction gets recorded.
          for (var sh : superHooks) {
            String key = sh.name() + sh.desc();
            if (declared.contains(key) || superName == null || !declaredInChain.test(className, key)) continue;
            declared.add(key);
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
            declared.add(key);
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
        if ((access & Opcodes.ACC_ABSTRACT) == 0) declaredConcrete.add(name + desc);
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
          // `new X(...)` where X became an interface (ClickEvent) or a record with a factory: the NEW+DUP pair is
          // withheld (a NEW of an interface throws before the factory can run) and the factory call replaces
          // the constructor call outright.
          String pendingNew; boolean withheld;
          private boolean factoryOnly(String type) {
            Map<String, FabricApiBridges.CtorAdapter> byCtor = ctorAdapters.get(type);
            if (byCtor == null || byCtor.isEmpty()) return false;
            for (var ad : byCtor.values()) if (ad.factory() == null) return false;
            Boolean isI = isInterface.apply(type);
            return isI != null && isI;
          }
          @Override public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && factoryOnly(type)) { pendingNew = type; return; }
            super.visitTypeInsn(opcode, type);
          }
          @Override public void visitInsn(int opcode) {
            if (pendingNew != null && opcode == Opcodes.DUP) { withheld = true; frameDirty[0] = true; pendingNew = null; return; }
            if (pendingNew != null) { super.visitTypeInsn(Opcodes.NEW, pendingNew); pendingNew = null; }
            super.visitInsn(opcode);
          }
          @Override public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
            if (demotedSuper != null && name.equals("<init>") && opcode == Opcodes.INVOKESPECIAL && owner.equals(demotedSuper) && mname.equals("<init>")) {
              // super(...) of a demoted class: drop its arguments and call Object's constructor
              for (org.objectweb.asm.Type at : org.objectweb.asm.Type.getArgumentTypes(mdesc)) super.visitInsn(at.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
              super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
              return;
            }
            // A class that now extends an EXTENDABLE record must call that record's constructor instead of Object's.
            // The record's fields stay null; the game reaches the values through the mod's own accessor overrides.
            if (flippedSuper != null && name.equals("<init>") && opcode == Opcodes.INVOKESPECIAL && owner.equals("java/lang/Object") && mname.equals("<init>")) {
              String ctor = EXTENDABLE.get(flippedSuper);
              for (org.objectweb.asm.Type at : org.objectweb.asm.Type.getArgumentTypes(ctor)) {
                switch (at.getSort()) { case org.objectweb.asm.Type.LONG -> super.visitInsn(Opcodes.LCONST_0); case org.objectweb.asm.Type.DOUBLE -> super.visitInsn(Opcodes.DCONST_0); case org.objectweb.asm.Type.FLOAT -> super.visitInsn(Opcodes.FCONST_0);
                  case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> super.visitInsn(Opcodes.ACONST_NULL); default -> super.visitInsn(Opcodes.ICONST_0); }
              }
              super.visitMethodInsn(Opcodes.INVOKESPECIAL, flippedSuper, "<init>", ctor, false);
              return;
            }
            // Call kind follows the target's class-vs-interface truth (INVOKEINTERFACE on a class is a link error).
            if (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL) {
              Boolean isI = isInterface.apply(owner);
              if (isI != null && isI && opcode == Opcodes.INVOKEVIRTUAL) { opcode = Opcodes.INVOKEINTERFACE; itf = true; }
              else if (isI != null && !isI && opcode == Opcodes.INVOKEINTERFACE) { opcode = Opcodes.INVOKEVIRTUAL; itf = false; }
            } else if (opcode == Opcodes.INVOKESTATIC) {
              Boolean isI = isInterface.apply(owner);
              if (isI != null) itf = isI;
            }
            String[] to = mname.equals("<init>") ? null : lookup(callRedirects, owner, mname + mdesc);
            if (to != null && ShimGenerator.unavailableHere(to[0])) to = null;   // no shim, no redirect to it
            if (to != null) {
              usedShims.add(to[0]);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
              return;
            }
            // A Fabric API accessor returning an Event that the target dropped (ModelLoadingPlugin.Context.modifyModelBeforeBake()):
            // hand back a dead event, so the mod's registrations become no-ops instead of a NoSuchMethodError at init.
            // Only where the dead-event shim can actually link. It is compiled against Mojang names, so on a target
            // that loads through intermediary it resolves nothing — and this substitution fires whenever the
            // target's Fabric API cannot be confirmed to declare the method, which on those versions is every such
            // call. Architectury came out of a 1.21.1 port with working event registrations replaced by a class
            // that could not load. Left alone, the mod's own call stands a good chance of being right already.
            if (opcode != Opcodes.INVOKESTATIC && !mname.equals("<init>") && owner.startsWith("net/fabricmc/fabric/api/")
                && mdesc.endsWith(")Lnet/fabricmc/fabric/api/event/Event;") && mdesc.startsWith("()")
                && !ShimGenerator.unavailableHere("foxgrade/shim/FabricEventsCompat")
                && !ShimGenerator.SHIMS.containsKey(owner) && !declaredInChain.test(owner, mname + mdesc)) {
              super.visitInsn(Opcodes.POP);
              usedShims.add("foxgrade/shim/FabricEventsCompat");
              super.visitMethodInsn(Opcodes.INVOKESTATIC, "foxgrade/shim/FabricEventsCompat", "dead", "()Lnet/fabricmc/fabric/api/event/Event;", false);
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
                // A recipe may need the receiver (the entity whose level supplies a ServerLevel):
                // spill it too and put it back before the new arguments.
                boolean needsRecv = false;
                for (String[] u : ca.args()) for (String x : u) if (x.equals("recv")) needsRecv = true;
                if (needsRecv && opcode != Opcodes.INVOKESTATIC) { recvSlot = idx; idx++; super.visitVarInsn(Opcodes.ASTORE, recvSlot); super.visitVarInsn(Opcodes.ALOAD, recvSlot); }
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
              frameDirty[0] = true;   // argument recipes and conversions reshape the stack at this call
              super.visitMethodInsn(opcode, owner, ca.newName(), ca.newDesc(), itf);
              if (ca.convert() != null) { usedShims.add(ca.convert()[1]); super.visitMethodInsn(Opcodes.INVOKESTATIC, ca.convert()[1], ca.convert()[2], ca.convert()[3], false); }
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
                frameDirty[0] = true;   // arguments re-routed through locals, or NEW/DUP dropped for a factory
                org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mdesc);
                int base = 400;   // far above any real method's locals; COMPUTE_MAXS sizes the frame
                int[] slotAt = new int[args.length];
                int idx = base;
                for (int i = 0; i < args.length; i++) { slotAt[i] = idx; idx += args[i].getSize(); }
                for (int i = args.length - 1; i >= 0; i--) {
                  super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slotAt[i]);
                }
                boolean superCall = name.equals("<init>") && owner.equals(superName);   // a subclass constructor chaining up: no NEW/DUP to drop
                if (ad.factory() != null && !(superCall && (!ad.transforms().isEmpty() || ad.newDesc() != null))) {
                  // The constructor is gone: drop the two uninitialised refs NEW+DUP left and call
                  // the static factory that builds the replacement value.
                  if (withheld) withheld = false; else { super.visitInsn(Opcodes.POP); super.visitInsn(Opcodes.POP); }
                  if (ad.args() != null) for (String[] u : ad.args()) pushOldSource(this, u, args, slotAt);
                  else for (int i = 0; i < args.length; i++) super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slotAt[i]);
                  usedShims.add(ad.factory()[0]);
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, ad.factory()[0], ad.factory()[1], ad.factory()[2], false);
                  return;
                }
                if (ad.args() != null) {
                  for (String[] u : ad.args()) pushOldSource(this, u, args, slotAt);
                  super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ad.newDesc(), false);
                  return;
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
              // The lambda's method name is the interface's SAM name. ASM's Remapper never maps it (no owner in the
              // call), so an intermediary name (method_14453) would survive into the port and the lambda would
              // implement a method nothing calls: translate it as a method of the interface being implemented.
              if (bsmArgs.length > 0 && bsmArgs[0] instanceof org.objectweb.asm.Type samType && samType.getSort() == org.objectweb.asm.Type.METHOD) {
                String mapped = remapper.mapMethodName(ret.getInternalName(), iname, samType.getDescriptor());
                if (mapped != null && !mapped.equals(iname) && !mapped.startsWith("<")) iname = mapped;
              }
              Map<String, String> sam = samRenames.get(ret.getInternalName());
              if (sam != null && sam.containsKey(iname)) iname = sam.get(iname);
              Map<String, String> sam2 = samRenames.get(remapper.map(ret.getInternalName()));
              if (sam2 != null && sam2.containsKey(iname)) iname = sam2.get(iname);
            }
            // An interface that lost its only abstract method (StructureProcessorType: 26.2 registers MapCodecs instead):
            // build the lambda as a Supplier and hand it to a wrapper that is both the interface and the codec.
            if (ret.getSort() == org.objectweb.asm.Type.OBJECT && SAMLESS_WRAP.containsKey(remapper.map(ret.getInternalName())) && bsmArgs.length >= 3
                && bsmArgs[0] instanceof org.objectweb.asm.Type samT && samT.getArgumentTypes().length == 0 && org.objectweb.asm.Type.getArgumentTypes(idesc).length == 0) {
              String[] w = SAMLESS_WRAP.get(remapper.map(ret.getInternalName()));
              Object[] sup = bsmArgs.clone();
              sup[0] = org.objectweb.asm.Type.getMethodType("()Ljava/lang/Object;");
              super.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm, sup);
              usedShims.add(w[0]);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, w[0], w[1], "(Ljava/util/function/Supplier;)L" + remapper.map(ret.getInternalName()) + ";", false);
              frameDirty[0] = true;
              return;
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
                if (ad != null && ad.factory() != null) {
                  // Item.Properties::new — the constructor reference becomes a reference to the factory
                  usedShims.add(ad.factory()[0]);
                  out[i] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, ad.factory()[0], ad.factory()[1], ad.factory()[2], false);
                  continue;
                }
                if (ad != null && ad.transforms().isEmpty() && ad.args() == null && ad.factory() == null) {
                  out[i] = new org.objectweb.asm.Handle(h.getTag(), h.getOwner(), "<init>", ad.newDesc(), false);
                } else if (ad != null) {
                  // LeavesBlock::new where the constructor grew or reshaped: point the reference at a synthesised static
                  // bridge in this class whose body is the plain `new` — the constructor adapter rewrites that body.
                  String bridge = "fg$new$" + ctorBridges.size();
                  String bridgeDesc = h.getDesc().substring(0, h.getDesc().lastIndexOf(')') + 1) + "L" + h.getOwner() + ";";
                  ctorBridges.add(new String[] {bridge, bridgeDesc, h.getOwner(), h.getDesc()});
                  out[i] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, className, bridge, bridgeDesc, (classAccess & Opcodes.ACC_INTERFACE) != 0);
                  frameDirty[0] = true;
                }
              }
            }
            // A Fabric API callback that gained trailing parameters (ServerChunkEvents.Load.onChunkLoad(level, chunk[, newlyGenerated])):
            // the lambda still has the 1.21 shape, so the interface's new SAM is routed through a synthesised static bridge
            // in this class that drops the extra arguments and calls the original implementation.
            if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && out.length >= 3
                && out[0] instanceof org.objectweb.asm.Type samT && out[1] instanceof org.objectweb.asm.Handle impl && out[2] instanceof org.objectweb.asm.Type instT
                && ret.getSort() == org.objectweb.asm.Type.OBJECT && ret.getInternalName().startsWith("net/fabricmc/fabric/api/")
                && impl.getTag() != Opcodes.H_NEWINVOKESPECIAL) {
              String want = null;
              for (String m : abstractsOf.apply(ret.getInternalName())) if (m.startsWith(iname + "(")) { want = m.substring(iname.length()); break; }
              if (want != null && !want.equals(samT.getDescriptor())) {
                org.objectweb.asm.Type[] newArgs = org.objectweb.asm.Type.getArgumentTypes(want), oldArgs = samT.getArgumentTypes(), instArgs = instT.getArgumentTypes();
                boolean prefix = newArgs.length > oldArgs.length && instArgs.length == oldArgs.length
                    && org.objectweb.asm.Type.getReturnType(want).equals(samT.getReturnType())
                    && org.objectweb.asm.Type.getReturnType(impl.getDesc()).equals(instT.getReturnType());
                for (int i = 0; prefix && i < oldArgs.length; i++) if (!newArgs[i].equals(oldArgs[i])) prefix = false;
                if (prefix) {
                  org.objectweb.asm.Type[] captured = org.objectweb.asm.Type.getArgumentTypes(idesc);
                  org.objectweb.asm.Type[] samArgs = new org.objectweb.asm.Type[newArgs.length];
                  for (int i = 0; i < newArgs.length; i++) samArgs[i] = i < oldArgs.length ? instArgs[i] : newArgs[i];
                  org.objectweb.asm.Type[] bridgeArgs = new org.objectweb.asm.Type[captured.length + samArgs.length];
                  System.arraycopy(captured, 0, bridgeArgs, 0, captured.length);
                  System.arraycopy(samArgs, 0, bridgeArgs, captured.length, samArgs.length);
                  String bridge = "fg$sam$" + samBridges.size();
                  String bridgeDesc = org.objectweb.asm.Type.getMethodDescriptor(instT.getReturnType(), bridgeArgs);
                  samBridges.add(new Object[] {bridge, bridgeDesc, impl, captured.length, oldArgs.length});
                  out[0] = org.objectweb.asm.Type.getMethodType(want);
                  out[1] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, className, bridge, bridgeDesc, (classAccess & Opcodes.ACC_INTERFACE) != 0);
                  out[2] = org.objectweb.asm.Type.getMethodType(instT.getReturnType(), samArgs);
                  frameDirty[0] = true;
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
              if (to != null && to[0].equals("retype")) {
                frameDirty[0] = true;
                // The field's declared type widened (SimpleParticleType → ParticleType): read with the
                // new type and cast back to what the 1.21.x code expects.
                super.visitFieldInsn(opcode, owner, fname, to[1]);
                super.visitTypeInsn(Opcodes.CHECKCAST, to[2]);
                return;
              }
              if (to != null && to[0].equals("move")) {
                // The constant moved to a holder class (EntityType.FOX → EntityTypes.FOX), or was renamed (a 4th element).
                super.visitFieldInsn(opcode, to[1], to.length > 3 ? to[3] : fname, to[2]);
                return;
              }
              if (to != null && to[0].equals("holder")) {
                frameDirty[0] = true;
                // The field still exists but became a Holder: read it with its new type, unwrap.
                super.visitFieldInsn(opcode, owner, fname, to[1]);
                usedShims.add(to[2]);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, to[2], to[3], to[4], false);
                if (to.length > 5) super.visitTypeInsn(Opcodes.CHECKCAST, to[5]);
                return;
              }
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
    if (frameDirty[0]) out = recomputeFrames(out);   // the copied StackMapTable still describes the old NEW+DUP stack
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
