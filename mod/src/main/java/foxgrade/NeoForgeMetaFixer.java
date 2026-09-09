package foxgrade;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The NeoForge counterpart of {@link FabricMetaFixer}: widen a mod manifest's version gates so a jar built for an
 *  older Minecraft is allowed to load on the target.
 *
 *  <p>NeoForge manifests are TOML ({@code META-INF/neoforge.mods.toml}, or {@code META-INF/mods.toml} on older
 *  builds). Fox-Grade carries no TOML library and does not need one: the only things worth touching are three
 *  quoted scalars, and rewriting them line by line leaves every comment, ordering and formatting choice intact,
 *  which matters because this file is also what a human reads when a port misbehaves.
 *
 *  <p>Only the gates move. The mod's identity, its dependencies on other mods, its mixin configs and its access
 *  transformers are left exactly as they were — widening a gate says "you may run here", which is Fox-Grade's
 *  claim to make, while rewriting a mod's declared relationships would not be. */
final class NeoForgeMetaFixer {
  private NeoForgeMetaFixer() { }

  static boolean isManifest(String entryName) {
    return entryName.equals("META-INF/neoforge.mods.toml") || entryName.equals("META-INF/mods.toml");
  }

  /** The same array back when nothing needed widening. */
  static byte[] widen(byte[] toml, String targetMc) {
    return widen(toml, targetMc, null);
  }

  /** As above, and when {@code present} is given, also drops an access-transformer declaration whose file is not
   *  actually in the jar.
   *
   *  <p>Mods ship that way. pridelib, bundled two levels deep inside LambDynamicLights, declares
   *  {@code [[accessTransformers]] file = "META-INF/accesstransformer.cfg"} and contains no such file. NeoForge 21.1
   *  shrugged; 26.2 refuses the mod outright with "Access transformer file ... does not exist!", and the whole tree
   *  of mods above it fails with it.
   *
   *  <p>Removing a claim the jar cannot back is the same kind of edit as widening a version gate, and safer: there is
   *  no transformer to lose, only a promise of one that was never kept. */
  static byte[] widen(byte[] toml, String targetMc, java.util.Set<String> present) {
    try {
      List<String> lines = new ArrayList<>(List.of(new String(toml, StandardCharsets.UTF_8).split("\n", -1)));
      boolean changed = false;
      String block = "";                 // the current [[...]] table, lowercased
      int depStart = -1;                 // first line of the dependency table being read

      if (present != null) changed |= dropMissingTransformers(lines, present);
      for (int i = 0; i < lines.size(); i++) {
        String trimmed = lines.get(i).trim();
        if (trimmed.startsWith("[")) {
          block = trimmed.toLowerCase(Locale.ROOT);
          depStart = block.startsWith("[[dependencies.") ? i : -1;
          continue;
        }
        // The loader gate: which javafml generation the mod was written against. Older mods pin a generation the
        // target no longer ships, and that is exactly the gate a port is entitled to open.
        if (block.isEmpty() && trimmed.startsWith("loaderVersion")) {
          String widened = replaceQuoted(lines.get(i), "[0,)");
          if (widened != null) { lines.set(i, widened); changed = true; }
          continue;
        }
        if (depStart < 0 || !trimmed.startsWith("versionRange")) continue;
        String depId = dependencyId(lines, depStart, i);
        String range = depId == null ? null
            : depId.equals("minecraft") ? "[" + targetMc + ",)"
            : depId.equals("neoforge") || depId.equals("forge") ? "[0,)"
            : null;                       // another mod's version gate is that mod's business, not ours
        if (range == null) continue;
        String widened = replaceQuoted(lines.get(i), range);
        if (widened != null) { lines.set(i, widened); changed = true; }
      }
      return changed ? String.join("\n", lines).getBytes(StandardCharsets.UTF_8) : toml;
    } catch (RuntimeException notOurShape) {
      return toml;
    }
  }

  /** The first access-transformer file the manifest declares, or null when it declares none.
   *
   *  <p>Which decides whether Fox-Grade may add a table of its own. NeoForge reads as many
   *  {@code [[accessTransformers]]} tables as a manifest carries; Forge reads exactly one and refuses the mod
   *  outright when it finds a second — "The configuration path [accessTransformers] is invalid. I wasn't expecting
   *  a multi-object list". Cloth Config already declares one, so a second took down every mod in the Forge lane. */
  static String declaredTransformer(byte[] toml) {
    String[] lines = new String(toml, StandardCharsets.UTF_8).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].trim().toLowerCase(Locale.ROOT).startsWith("[[accesstransformers]]")) continue;
      for (int j = i + 1; j < lines.length && !lines[j].trim().startsWith("["); j++) {
        String t = lines[j].trim();
        if (!t.startsWith("file")) continue;
        String v = quoted(t);
        if (v == null) return null;
        return v.startsWith("META-INF/") ? v : "META-INF/" + v;
      }
    }
    return null;
  }

  /** Adds an {@code [[accessTransformers]]} table for a file Fox-Grade wrote into the port.
   *
   *  <p>Appended rather than merged into whatever the mod already declares: NeoForge accepts several of these tables
   *  and reads them all, so leaving the mod's own untouched is both simpler and safer than editing a file the mod
   *  author owns. Returns the manifest unchanged if it already names this file, so re-porting is idempotent. */
  static byte[] declareTransformer(byte[] toml, String file, boolean forge) {
    String text = new String(toml, StandardCharsets.UTF_8);
    if (text.contains(file)) return toml;
    StringBuilder out = new StringBuilder(text);
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    out.append("\n# Added by Fox-Grade: members this target made private or final that the mod was compiled against.\n");
    // The two loaders spell this differently and neither tolerates the other's spelling. NeoForge reads an array of
    // tables, [[accessTransformers]], and reads as many as it finds. Forge 26.2 wants a single table,
    // [accessTransformers], and rejects the mod outright on the array form — "I wasn't expecting a multi-object
    // list - remove one of the [[ ]]" — which took down every mod in the Forge lane while the file itself was
    // perfectly valid and contained exactly one entry.
    out.append(forge ? "[accessTransformers]\n" : "[[accessTransformers]]\n");
    out.append("file = \"").append(file).append("\"\n");
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Removes every {@code [[accessTransformers]]} table naming a file the jar does not contain. */
  private static boolean dropMissingTransformers(List<String> lines, java.util.Set<String> present) {
    boolean changed = false;
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).trim().toLowerCase(Locale.ROOT).startsWith("[[accesstransformers]]")) continue;
      int end = i + 1;
      String file = null;
      while (end < lines.size() && !lines.get(end).trim().startsWith("[")) {
        String t = lines.get(end).trim();
        if (t.startsWith("file")) file = quoted(t);
        end++;
      }
      if (file == null) continue;
      String path = file.startsWith("META-INF/") ? file : "META-INF/" + file;
      if (present.contains(path) || present.contains(file)) continue;
      lines.subList(i, end).clear();
      i--;
      changed = true;
    }
    return changed;
  }

  /** The modId of the dependency table starting at {@code from}; the key may sit either side of the versionRange. */
  private static String dependencyId(List<String> lines, int from, int versionRangeLine) {
    for (int i = from; i < lines.size(); i++) {
      String t = lines.get(i).trim();
      if (i != from && t.startsWith("[")) break;                 // next table: this one is done
      if (!t.startsWith("modId")) continue;
      String v = quoted(t);
      if (v != null) return v.toLowerCase(Locale.ROOT);
    }
    return null;
  }

  /** The first double-quoted value on the line, or null. */
  private static String quoted(String line) {
    int a = line.indexOf('"');
    if (a < 0) return null;
    int b = line.indexOf('"', a + 1);
    return b < 0 ? null : line.substring(a + 1, b);
  }

  /** The line with its first quoted value swapped, keeping indentation, spacing and any trailing comment. */
  private static String replaceQuoted(String line, String value) {
    int a = line.indexOf('"');
    if (a < 0) return null;
    int b = line.indexOf('"', a + 1);
    if (b < 0) return null;
    if (line.substring(a + 1, b).equals(value)) return null;     // already what we want
    return line.substring(0, a + 1) + value + line.substring(b);
  }
}
