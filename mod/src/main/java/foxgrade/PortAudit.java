package foxgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks a finished port for the faults that make a jar unloadable without making it look wrong.
 *
 *  <p>Every one of these has already happened. Fox-Grade's shims were compiled by whatever JDK built it, which on a
 *  javac 25 machine meant class-file major 69; those shims are injected INTO someone else's mod, Forge's annotation
 *  scanner cannot parse a class that new, and it therefore found no {@code @Mod} class in any port and refused all
 *  twenty mods in the lane. Nothing was wrong with the ports. Nothing in the report said anything either — the
 *  failure only existed once a particular loader tried to read the result.
 *
 *  <p>So the port is inspected before it is handed over, and what is found goes in the port report where the panel
 *  shows it. This does not refuse to write a jar: a port that is 90% right is still worth having, and Fox-Grade's
 *  contract is to say what it could not do rather than to withhold. */
final class PortAudit {
  private PortAudit() { }

  /** Class-file major for Java 21, which is what Minecraft 26.2 itself requires. Anything above it is emitted by a
   *  newer build JDK than the game needs, and is a compatibility risk for no gain. */
  private static final int MAX_MAJOR = 65;

  /** Findings, each a sentence a person can act on. Empty when the port is structurally sound. */
  static List<String> audit(Map<String, byte[]> entries, String targetMc) {
    List<String> found = new ArrayList<>();
    tooNew(entries, found);
    danglingShims(entries, found);
    declaredModClasses(entries, found);
    mixinTargets(entries, found);
    return found;
  }

  /** A Forge or NeoForge manifest names mod ids; the loader then looks for a class annotated for each one and
   *  refuses the whole file if it cannot find it.
   *
   *  <p>This is the check that would have caught the worst bug of the lot directly rather than by its symptom.
   *  Every Forge port was refused with "the following classes are missing, but are reported in the mods.toml",
   *  because the shims were compiled too new for Forge's scanner to read and it therefore found no annotated class
   *  anywhere. The annotation was present the whole time; nothing in the port could see it. */
  private static void declaredModClasses(Map<String, byte[]> entries, List<String> found) {
    byte[] toml = entries.get("META-INF/neoforge.mods.toml");
    if (toml == null) toml = entries.get("META-INF/mods.toml");
    if (toml == null) return;
    Set<String> declared = new java.util.LinkedHashSet<>();
    // Only the ids under [[mods]]. A dependency table names modId too, and that id belongs to a DIFFERENT jar —
    // demanding an annotated class for it inside this one reports every mod with a dependency as broken. Entity
    // Model Features depends on Entity Texture Features and was failed for not containing it.
    boolean inMods = false;
    for (String line : new String(toml, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
      String t = line.trim();
      if (t.startsWith("[")) { inMods = t.toLowerCase(java.util.Locale.ROOT).startsWith("[[mods]]"); continue; }
      if (!inMods || !t.startsWith("modId")) continue;
      int a = t.indexOf('"');
      int b = a < 0 ? -1 : t.indexOf('"', a + 1);
      if (b > a) declared.add(t.substring(a + 1, b));
    }
    if (declared.isEmpty()) return;
    // The loader finds these by annotation, and an annotation's value sits in the constant pool as a plain string.
    Set<String> mentioned = new java.util.HashSet<>();
    boolean sawAnnotation = false;
    for (var e : entries.entrySet()) {
      if (!e.getKey().endsWith(".class")) continue;
      List<String> pool = ConstantPool.strings(e.getValue());
      if (pool.contains("Lnet/minecraftforge/fml/common/Mod;") || pool.contains("Lnet/neoforged/fml/common/Mod;")) {
        sawAnnotation = true;
        mentioned.addAll(pool);
      }
    }
    if (!sawAnnotation) return;              // a library with no @Mod class of its own is a normal, valid shape
    for (String id : declared) {
      if (id.equals("minecraft") || id.equals("forge") || id.equals("neoforge")) continue;
      if (!mentioned.contains(id)) {
        found.add("the manifest declares the mod id \"" + id + "\" but no annotated class in the port carries it; "
            + "this loader will refuse the whole file");
      }
    }
  }

  /** A mixin config naming a class the port does not contain, which Mixin reports as a hard error at apply time. */
  private static void mixinTargets(Map<String, byte[]> entries, List<String> found) {
    Set<String> classes = new java.util.HashSet<>();
    for (String n : entries.keySet()) {
      if (n.endsWith(".class")) classes.add(n.substring(0, n.length() - 6));
    }
    for (var e : entries.entrySet()) {
      String name = e.getKey();
      if (!name.endsWith(".json") || !name.contains("mixin") || name.startsWith("META-INF/")) continue;
      com.google.gson.JsonObject cfg;
      try {
        cfg = Json.parse(
            new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      } catch (RuntimeException notJson) {
        continue;
      }
      String pkg = cfg.has("package") ? cfg.get("package").getAsString().replace('.', '/') : "";
      List<String> missing = new ArrayList<>();
      for (String key : new String[]{"mixins", "client", "server"}) {
        if (!cfg.has(key) || !cfg.get(key).isJsonArray()) continue;
        for (var el : cfg.getAsJsonArray(key)) {
          String simple = el.getAsString().replace('.', '/');
          String full = pkg.isEmpty() ? simple : pkg + "/" + simple;
          if (!classes.contains(full)) missing.add(el.getAsString());
        }
      }
      if (!missing.isEmpty()) {
        found.add(name + " lists " + missing.size() + " mixin class(es) the port does not contain ("
            + String.join(", ", missing.subList(0, Math.min(3, missing.size())))
            + (missing.size() > 3 ? ", …" : "") + "); Mixin treats that as a hard error");
      }
    }
  }

  /** A class the runtime — or a loader's own scanner — may be unable to read at all. */
  private static void tooNew(Map<String, byte[]> entries, List<String> found) {
    Map<Integer, Integer> byVersion = new LinkedHashMap<>();
    String example = null;
    for (var e : entries.entrySet()) {
      byte[] b = e.getValue();
      if (!e.getKey().endsWith(".class") || b.length < 8) continue;
      if ((b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE) continue;
      int major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
      if (major <= MAX_MAJOR) continue;
      byVersion.merge(major, 1, Integer::sum);
      if (example == null) example = e.getKey();
    }
    for (var v : byVersion.entrySet()) {
      found.add(v.getValue() + " class(es) at class-file version " + v.getKey() + ", newer than Java 21 which is what "
          + "the game requires (e.g. " + example + "); a loader's annotation scanner may not be able to read them");
    }
  }

  /** A reference to a Fox-Grade shim the port does not actually contain, which is a NoClassDefFoundError waiting. */
  private static void danglingShims(Map<String, byte[]> entries, List<String> found) {
    Set<String> present = new java.util.HashSet<>();
    for (String name : entries.keySet()) {
      if (name.endsWith(".class")) present.add(name.substring(0, name.length() - 6));
    }
    Set<String> wanted = new java.util.TreeSet<>();
    for (var e : entries.entrySet()) {
      if (!e.getKey().endsWith(".class")) continue;
      for (String s : ConstantPool.strings(e.getValue())) {
        if (s.startsWith("foxgrade/shim/") && !s.endsWith(";") && !present.contains(s)) wanted.add(s);
      }
    }
    for (String w : wanted) {
      found.add("references the shim " + w + ", which is not in the port — it was named by a redirect but never "
          + "injected, usually because it is missing from ShimGenerator.SHIMS");
    }
  }
}
