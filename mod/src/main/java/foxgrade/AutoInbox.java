// Zero-configuration porting: a jar dropped straight into mods/ that was built for an OLDER
// Minecraft version is moved into the inbox before Fox-Grade ports, so "put the old mod in
// mods/ like always" is all a person has to know. Only jars whose own `depends.minecraft` range
// clearly excludes the running version are taken — Fabric would refuse to load those anyway, so
// moving them is strictly an improvement — and anything with a range this parser cannot read is
// left exactly where it is.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AutoInbox {
  private AutoInbox() { }

  /** Moves qualifying jars from mods/ into the inbox; returns what was moved. */
  public static List<Path> sweep(Path modsDir, Path inbox, String currentMc, java.util.function.Consumer<String> log) {
    List<Path> moved = new ArrayList<>();
    try (var st = Files.list(modsDir)) {
      for (Path jar : st.filter((f) -> f.getFileName().toString().endsWith(".jar")).sorted().toList()) {
        String name = jar.getFileName().toString();
        if (name.contains("-fgport") || name.startsWith("foxgrade-")) continue;
        String range = minecraftRange(jar);
        boolean rangeExcludes = range != null && excludes(range, currentMc);
        // 26.x jars are compiled against Mojang names; anything that still references intermediary
        // (net/minecraft/class_NNNN) was built for an older game and cannot load here, whatever
        // its declared range says (BetterF3 says ">=1.21").
        if (!rangeExcludes && !speaksIntermediary(jar)) continue;
        if (range == null) range = "an intermediary-mapped version";
        try {
          Files.createDirectories(inbox);
          Path target = inbox.resolve(name);
          Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
          moved.add(target);
          log.accept("  AUTO-INBOX     " + name + " — built for Minecraft " + range + (rangeExcludes ? "" : " (still uses intermediary names)") + ", not " + currentMc + "; moved to the inbox to be ported");
        } catch (IOException e) {
          log.accept("  AUTO-INBOX     could not move " + name + ": " + e.getMessage());
        }
      }
    } catch (IOException ignore) { }
    return moved;
  }

  /** The jar's declared Minecraft dependency range, or null if it has none / cannot be read. */
  static String minecraftRange(Path jar) {
    try (ZipFile z = new ZipFile(jar.toFile())) {
      String text = QuiltMeta.fabricMeta(z);
      if (text == null) return null;
      JsonObject meta = new Gson().fromJson(text, JsonObject.class);
      if (meta == null || !meta.has("depends") || !meta.get("depends").isJsonObject()) return null;
      JsonElement mc = meta.getAsJsonObject("depends").get("minecraft");
      if (mc == null) return null;
      if (mc.isJsonPrimitive()) return mc.getAsString();
      if (mc.isJsonArray()) { StringBuilder sb = new StringBuilder(); for (var x : mc.getAsJsonArray()) { if (sb.length() > 0) sb.append(" || "); sb.append(x.getAsString()); } return sb.toString(); }
    } catch (Exception ignore) { }
    return null;
  }

  // ---- version range semantics (the subset Fabric mods actually use) ----
  /** True only when the range is understood AND no alternative in it accepts `version`. */
  static boolean excludes(String range, String version) {
    int[] v = parse(version);
    if (v == null) return false;
    for (String alt : range.split("\\|\\|")) {
      alt = alt.trim();
      if (alt.isEmpty() || alt.equals("*")) return false;
      Boolean ok = accepts(alt, v);
      if (ok == null || ok) return false;
    }
    return true;
  }

  /** null = not understood. */
  private static Boolean accepts(String alt, int[] v) {
    for (String term : alt.split("\\s+")) {
      if (term.isEmpty()) continue;
      Boolean r = acceptsTerm(term, v);
      if (r == null) return null;
      if (!r) return false;
    }
    return true;
  }

  private static Boolean acceptsTerm(String t, int[] v) {
    String op = ""; String rest = t;
    for (String o : new String[]{">=", "<=", ">", "<", "=", "~", "^"}) { if (t.startsWith(o)) { op = o; rest = t.substring(o.length()); break; } }
    boolean wildcard = rest.endsWith(".x") || rest.endsWith(".*");
    if (wildcard) rest = rest.substring(0, rest.length() - 2);
    int[] b = parse(rest);
    if (b == null) return null;
    int[] bound = pad(b, 3), ver = pad(v, 3);
    switch (op) {
      case ">=": return cmp(ver, bound) >= 0;
      case ">": return cmp(ver, bound) > 0;
      case "<=": return cmp(ver, bound) <= 0;
      case "<": return cmp(ver, bound) < 0;
      case "~": return cmp(ver, bound) >= 0 && ver[0] == bound[0] && (b.length < 2 || ver[1] == bound[1]);
      case "^": return cmp(ver, bound) >= 0 && ver[0] == bound[0];
      default:
        if (wildcard) { for (int i = 0; i < b.length; i++) if (ver[i] != bound[i]) return false; return true; }
        return cmp(ver, bound) == 0;
    }
  }
  private static int[] pad(int[] a, int n) { int[] r = new int[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
  private static int cmp(int[] a, int[] b) { for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]); return 0; }
  /** Numeric dotted version, ignoring a trailing prerelease/build tag; null if anything else. */
  static int[] parse(String s) {
    s = s.trim();
    int cut = s.indexOf('-'); if (cut > 0) s = s.substring(0, cut);
    cut = s.indexOf('+'); if (cut > 0) s = s.substring(0, cut);
    if (s.isEmpty()) return null;
    String[] parts = s.split("\\.");
    int[] r = new int[parts.length];
    for (int i = 0; i < parts.length; i++) { try { r[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { return null; } }
    return r;
  }

  /** True when any class in the jar references an intermediary-named game class. Cheap: constant pools only. */
  static boolean speaksIntermediary(Path jar) {
    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
      var en = zf.entries(); int seen = 0;
      while (en.hasMoreElements() && seen < 400) {
        java.util.zip.ZipEntry e = en.nextElement();
        if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
        seen++;
        try (java.io.InputStream in = zf.getInputStream(e)) {
          org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(in.readAllBytes());
          char[] buf = new char[cr.getMaxStringLength()];
          for (int i = 1; i < cr.getItemCount(); i++) {
            int off = cr.getItem(i);
            if (off == 0 || cr.readByte(off - 1) != 7) continue;   // CONSTANT_Class
            String cls = cr.readUTF8(off, buf);
            if (cls != null && cls.startsWith("net/minecraft/class_")) return true;
          }
        } catch (Exception ignore) { }
      }
    } catch (Exception ignore) { }
    return false;
  }
}
