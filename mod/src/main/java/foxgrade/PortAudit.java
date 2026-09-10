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
    return found;
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
