// Synthesize replacement classes for SIMPLE Minecraft classes that were removed in the target
// version. When the verifier finds a ported mod referencing one of these, the shim is injected
// into the ported jar itself — the mod's classloader then resolves the missing name locally and
// the code path works instead of dying with NoClassDefFoundError.
//
// Scope discipline: only self-contained data holders whose behaviour is fully reproducible get
// a shim (Tuple is a plain pair). Anything wired into MC's systems (rendering, registries,
// networking) is NOT shimmable — a stub that pretends would "load and quietly misbehave," which
// Fox-Grade's rules forbid. Grow the table entry by entry, each one reviewed by a person.
package foxgrade;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.function.Supplier;

public final class ShimGenerator implements Opcodes {

  public static final Map<String, Supplier<byte[]>> SHIMS = Map.ofEntries(
      Map.entry("net/minecraft/util/Tuple", ShimGenerator::tuple),
      Map.entry("foxgrade/shim/AutoConfigCompat", ShimGenerator::autoConfigCompat),
      Map.entry("foxgrade/shim/FoodDataCompat", () -> fromResource("foxgrade/shim/FoodDataCompat.class")),
      Map.entry("foxgrade/shim/OptionInstanceCompat", () -> fromResource("foxgrade/shim/OptionInstanceCompat.class")),
      Map.entry("foxgrade/shim/CtorShims", () -> fromResource("foxgrade/shim/CtorShims.class")),
      Map.entry("foxgrade/shim/HudRenderCallback", () -> fromResource("foxgrade/shim/HudRenderCallback.class")),
      Map.entry("foxgrade/shim/EntityCompat", () -> fromResource("foxgrade/shim/EntityCompat.class")),
      Map.entry("net/minecraft/util/OptionEnum", ShimGenerator::optionEnum),
      Map.entry("net/minecraft/util/LazyLoadedValue", ShimGenerator::lazyLoadedValue),
      Map.entry("foxgrade/shim/MinecraftCompat", () -> fromResource("foxgrade/shim/MinecraftCompat.class")),
      Map.entry("foxgrade/shim/UtilCompat", ShimGenerator::utilCompat),
      // --- GUI rendering bridges (26.2 GuiGraphicsExtractor / render pipelines) ---
      Map.entry("foxgrade/shim/GuiCompat", () -> fromResource("foxgrade/shim/GuiCompat.class")),
      Map.entry("foxgrade/shim/GuiPoseStack", () -> fromResource("foxgrade/shim/GuiPoseStack.class")),
      Map.entry("foxgrade/shim/GuiQuadElement", () -> fromResource("foxgrade/shim/GuiQuadElement.class")),
      Map.entry("foxgrade/shim/RenderSystemCompat", () -> fromResource("foxgrade/shim/RenderSystemCompat.class")),
      Map.entry("foxgrade/shim/ShaderCompat", () -> fromResource("foxgrade/shim/ShaderCompat.class")),
      Map.entry("foxgrade/shim/InputCompat", () -> fromResource("foxgrade/shim/InputCompat.class")),
      Map.entry("foxgrade/shim/FontCompat", () -> fromResource("foxgrade/shim/FontCompat.class")),
      Map.entry("foxgrade/shim/I18nCompat", () -> fromResource("foxgrade/shim/I18nCompat.class")),
      Map.entry("foxgrade/shim/ChatFormattingCompat", () -> fromResource("foxgrade/shim/ChatFormattingCompat.class")),
      Map.entry("foxgrade/shim/WindowCompat", () -> fromResource("foxgrade/shim/WindowCompat.class")),
      Map.entry("foxgrade/shim/SourceFactorShim", () -> fromResource("foxgrade/shim/SourceFactorShim.class")),
      Map.entry("foxgrade/shim/DestFactorShim", () -> fromResource("foxgrade/shim/DestFactorShim.class")),
      Map.entry("foxgrade/shim/CameraCompat", () -> fromResource("foxgrade/shim/CameraCompat.class")),
      Map.entry("foxgrade/shim/ListFieldCompat", () -> fromResource("foxgrade/shim/ListFieldCompat.class")),
      Map.entry("foxgrade/shim/ScreenCompat", () -> fromResource("foxgrade/shim/ScreenCompat.class")),
      Map.entry("foxgrade/shim/SkinCompat", () -> fromResource("foxgrade/shim/SkinCompat.class")),
      Map.entry("foxgrade/shim/FabricEventsCompat", () -> fromResource("foxgrade/shim/FabricEventsCompat.class")),
      Map.entry("foxgrade/shim/OptionsCompat", () -> fromResource("foxgrade/shim/OptionsCompat.class")),
      Map.entry("foxgrade/shim/PlayerCompat", () -> fromResource("foxgrade/shim/PlayerCompat.class")),
      Map.entry("foxgrade/shim/NbtCompat", () -> fromResource("foxgrade/shim/NbtCompat.class")),
      Map.entry("foxgrade/shim/InteractionCompat", () -> fromResource("foxgrade/shim/InteractionCompat.class")),
      Map.entry("foxgrade/shim/SoundCompat", () -> fromResource("foxgrade/shim/SoundCompat.class")),
      Map.entry("foxgrade/shim/ImageCompat", () -> fromResource("foxgrade/shim/ImageCompat.class")),
      Map.entry("foxgrade/shim/TextureCompat", () -> fromResource("foxgrade/shim/TextureCompat.class")),
      Map.entry("foxgrade/shim/ItemCompat", () -> fromResource("foxgrade/shim/ItemCompat.class")),
      Map.entry("foxgrade/shim/LevelCompat", () -> fromResource("foxgrade/shim/LevelCompat.class")),
      Map.entry("foxgrade/shim/ToastCompat", () -> fromResource("foxgrade/shim/ToastCompat.class")),
      Map.entry("foxgrade/shim/TooltipCompat", () -> fromResource("foxgrade/shim/TooltipCompat.class")),
      Map.entry("foxgrade/shim/WidgetCompat", () -> fromResource("foxgrade/shim/WidgetCompat.class")),
      Map.entry("foxgrade/shim/ListCompat", ShimGenerator::listCompat),
      Map.entry("foxgrade/shim/BlocksCompat", () -> fromResource("foxgrade/shim/BlocksCompat.class")),
      // Removed Minecraft classes re-created at their own names: written as ordinary Java under
      // foxgrade.shim and renamed on the way in, so Fox-Grade's own jar never carries a class in
      // a Mojang package.
      Map.entry("com/mojang/blaze3d/vertex/Tesselator", () -> fromResource("foxgrade/shim/TesselatorShim.class")),
      Map.entry("com/mojang/blaze3d/vertex/BufferUploader", () -> fromResource("foxgrade/shim/BufferUploaderShim.class")),
      Map.entry("com/mojang/blaze3d/vertex/VertexFormat$Mode", () -> fromResource("foxgrade/shim/VertexFormatModeShim.class"))
  );

  // Shims compiled under a foxgrade.shim name that must land under a Minecraft name.
  static final Map<String, String> SHIM_RENAMES = Map.of(
      "foxgrade/shim/TesselatorShim", "com/mojang/blaze3d/vertex/Tesselator",
      "foxgrade/shim/BufferUploaderShim", "com/mojang/blaze3d/vertex/BufferUploader",
      "foxgrade/shim/VertexFormatModeShim", "com/mojang/blaze3d/vertex/VertexFormat$Mode");

  // Shims that reference other shims; the pipeline injects the closure.
  static final Map<String, java.util.List<String>> SHIM_DEPS = Map.of(
      "foxgrade/shim/GuiCompat", java.util.List.of("foxgrade/shim/GuiPoseStack"),
      "foxgrade/shim/RenderSystemCompat", java.util.List.of("foxgrade/shim/GuiCompat", "com/mojang/blaze3d/vertex/Tesselator", "foxgrade/shim/SourceFactorShim", "foxgrade/shim/DestFactorShim"),
      "foxgrade/shim/HudRenderCallback", java.util.List.of("foxgrade/shim/GuiCompat"),
      "foxgrade/shim/TooltipCompat", java.util.List.of("foxgrade/shim/GuiCompat"),
      "foxgrade/shim/ScreenCompat", java.util.List.of("foxgrade/shim/GuiCompat"),
      "foxgrade/shim/ListCompat", java.util.List.of("foxgrade/shim/ListFieldCompat"),
      "com/mojang/blaze3d/vertex/Tesselator", java.util.List.of("com/mojang/blaze3d/vertex/VertexFormat$Mode"),
      "com/mojang/blaze3d/vertex/BufferUploader", java.util.List.of("foxgrade/shim/GuiCompat", "foxgrade/shim/RenderSystemCompat",
          "foxgrade/shim/GuiQuadElement", "com/mojang/blaze3d/vertex/Tesselator", "com/mojang/blaze3d/vertex/VertexFormat$Mode"));

  static byte[] renameClasses(byte[] bytes, Map<String, String> map) {
    org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(bytes);
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    r.accept(new org.objectweb.asm.commons.ClassRemapper(w, new org.objectweb.asm.commons.SimpleRemapper(map)), 0);
    return w.toByteArray();
  }

  // Shims with real logic are written as normal Java inside Fox-Grade and copied into the ported
  // jar from Fox-Grade's own class resources — no hand-rolled ASM for anything non-trivial.
  private static byte[] fromResource(String path) {
    try (var in = ShimGenerator.class.getResourceAsStream("/" + path)) {
      if (in == null) throw new IllegalStateException("missing shim resource " + path);
      return renameClasses(in.readAllBytes(), SHIM_RENAMES);
    } catch (java.io.IOException e) { throw new RuntimeException(e); }
  }

  // Replacement for cloth-config's removed AutoConfig.getGuiRegistry(Class): returns a fresh
  // GuiRegistry. The mod's custom GUI providers land in an orphan registry instead of the shared
  // one — the config screen renders with default widgets, everything else works. Honest trade:
  // degraded config UI beats a NoSuchMethodError at init.
  private static byte[] autoConfigCompat() {
    String name = "foxgrade/shim/AutoConfigCompat";
    String reg = "me/shedaniel/autoconfig/gui/registry/GuiRegistry";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getGuiRegistry",
        "(Ljava/lang/Class;)L" + reg + ";", null, null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, reg);
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, reg, "<init>", "()V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // Util.backgroundExecutor() kept its name but now returns TracingExecutor instead of
  // ExecutorService; TracingExecutor.service() hands back exactly what pre-26.x callers expect.
  // Generated as bytecode rather than written in Java because javac 25 silently refuses to read
  // 26.2's Util.class (it reports "cannot find symbol" with no further diagnostic, while javap
  // and the running game are both fine with it). ASM has no such opinion.
  private static byte[] utilCompat() {
    String name = "foxgrade/shim/UtilCompat";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "backgroundExecutor",
        "()Ljava/util/concurrent/ExecutorService;", null, null);
    mv.visitCode();
    mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Util", "backgroundExecutor",
        "()Lnet/minecraft/TracingExecutor;", false);
    mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/TracingExecutor", "service",
        "()Ljava/util/concurrent/ExecutorService;", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.OptionEnum, removed in 26.x: a pure data contract (numeric id +
  // translation key) that mod enums implement for cycle-button options. Nothing in current MC
  // consumes it, so a faithful interface — including the original's default getCaption() —
  // restores every use the mod itself makes.
  // net.minecraft.util.LazyLoadedValue<T>: a memoising Supplier wrapper Mojang dropped from the
  // 26.x utilities. Semantics preserved exactly: the factory runs once, on first get(), then is
  // released.
  private static byte[] lazyLoadedValue() {
    String name = "net/minecraft/util/LazyLoadedValue";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, "<T:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
    cw.visitField(ACC_PRIVATE, "factory", "Ljava/util/function/Supplier;", "Ljava/util/function/Supplier<TT;>;", null).visitEnd();
    cw.visitField(ACC_PRIVATE, "value", "Ljava/lang/Object;", "TT;", null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;)V", "(Ljava/util/function/Supplier<TT;>;)V", null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
    mv.visitFieldInsn(PUTFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", "()TT;", null);
    mv.visitCode();
    org.objectweb.asm.Label done = new org.objectweb.asm.Label();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitVarInsn(ASTORE, 1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, done);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
    mv.visitFieldInsn(PUTFIELD, name, "value", "Ljava/lang/Object;");
    mv.visitVarInsn(ALOAD, 0); mv.visitInsn(ACONST_NULL);
    mv.visitFieldInsn(PUTFIELD, name, "factory", "Ljava/util/function/Supplier;");
    mv.visitLabel(done);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, name, "value", "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // List helpers 1.21.x mods called on their own AbstractSelectionList subclasses. Written in
  // bytecode because Entry is a protected nested class javac will not let a shim name; the JVM
  // resolves it by its class file, which is public.
  private static byte[] listCompat() {
    String name = "foxgrade/shim/ListCompat", list = "net/minecraft/client/gui/components/AbstractSelectionList";
    String entry = "L" + list + "$Entry;", asl = "L" + list + ";";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getEntry", "(" + asl + "I)" + entry, null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ILOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
    mv.visitTypeInsn(CHECKCAST, list + "$Entry"); mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "remove", "(" + asl + "I)" + entry, null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ILOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "remove", "(I)Ljava/lang/Object;", true);
    mv.visitTypeInsn(CHECKCAST, list + "$Entry"); mv.visitInsn(ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "removeEntry", "(" + asl + entry + ")Z", null, null);
    mv.visitCode(); mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "foxgrade/shim/ListFieldCompat", "children", "(" + asl + ")Ljava/util/List;", false);
    mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z", true);
    mv.visitInsn(IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "updateScrollingState", "(" + asl + "DDI)V", null, null);
    mv.visitCode(); mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static byte[] optionEnum() {
    String name = "net/minecraft/util/OptionEnum";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getId", "()I", null, null).visitEnd();
    cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getKey", "()Ljava/lang/String;", null, null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getCaption", "()Lnet/minecraft/network/chat/Component;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEINTERFACE, name, "getKey", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/network/chat/Component", "translatable",
        "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  // net.minecraft.util.Tuple<A, B>: the classic pair. Generic types erase to Object, so the
  // erased bytecode below satisfies every call the original accepted.
  private static byte[] tuple() {
    String name = "net/minecraft/util/Tuple";
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name,
        "<A:Ljava/lang/Object;B:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
    cw.visitField(ACC_PRIVATE, "a", "Ljava/lang/Object;", "TA;", null).visitEnd();
    cw.visitField(ACC_PRIVATE, "b", "Ljava/lang/Object;", "TB;", null).visitEnd();

    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", "(TA;TB;)V", null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1); mv.visitFieldInsn(PUTFIELD, name, "a", "Ljava/lang/Object;");
    mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 2); mv.visitFieldInsn(PUTFIELD, name, "b", "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();

    getter(cw, name, "getA", "a"); setter(cw, name, "setA", "a");
    getter(cw, name, "getB", "b"); setter(cw, name, "setB", "b");
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static void getter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "()Ljava/lang/Object;", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }

  private static void setter(ClassWriter cw, String owner, String method, String field) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, "(Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitFieldInsn(PUTFIELD, owner, field, "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0); mv.visitEnd();
  }
}
