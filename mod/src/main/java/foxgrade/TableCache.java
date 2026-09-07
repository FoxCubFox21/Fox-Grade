package foxgrade;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Parsed translation tables, kept as a compact binary file under {@code <gameDir>/.fox-grade/cache/} so a launch reads
 *  them in tens of milliseconds instead of parsing megabytes of JSON. A cache file starts with a stamp (format version,
 *  target version, byte size of the bundled resource it was built from); a mismatch means "parse again and rewrite". */
public final class TableCache {
  private TableCache() {}
  private static final int FORMAT = 1;

  public static String stamp(String resource, String targetMc) {
    long len = -1;
    try {
      URL u = TableCache.class.getResource(resource);
      if (u != null) len = u.openConnection().getContentLengthLong();
    } catch (IOException ignored) { }
    return FORMAT + "|" + targetMc + "|" + resource + "|" + len;
  }

  /** Opens the cache for reading when it exists and its stamp matches; null otherwise. */
  public static DataInputStream open(Path cacheDir, String name, String stamp) {
    if (cacheDir == null) return null;
    Path f = cacheDir.resolve(name);
    if (!Files.isRegularFile(f)) return null;
    try {
      DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(f)), 1 << 16));
      if (!stamp.equals(in.readUTF())) { in.close(); return null; }
      return in;
    } catch (IOException e) { return null; }
  }

  /** Opens the cache for writing (best effort: null when the directory cannot be created). */
  public static DataOutputStream create(Path cacheDir, String name, String stamp) {
    if (cacheDir == null) return null;
    try {
      Files.createDirectories(cacheDir);
      Path tmp = cacheDir.resolve(name + ".tmp");
      DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16)) {
        @Override public void close() throws IOException { super.close(); Files.move(tmp, cacheDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
      };
      out.writeUTF(stamp);
      return out;
    } catch (IOException e) { return null; }
  }

  public static void writeMap(DataOutputStream out, Map<String, String> m) throws IOException {
    out.writeInt(m.size());
    for (var e : m.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
  }
  public static Map<String, String> readMap(DataInputStream in) throws IOException {
    int n = in.readInt(); Map<String, String> m = new HashMap<>(Math.max(16, n * 4 / 3 + 1));
    for (int i = 0; i < n; i++) { String k = in.readUTF(); m.put(k, in.readUTF()); }
    return m;
  }
  public static void writeNested(DataOutputStream out, Map<String, Map<String, String>> m) throws IOException {
    out.writeInt(m.size());
    for (var e : m.entrySet()) { out.writeUTF(e.getKey()); writeMap(out, e.getValue()); }
  }
  public static Map<String, Map<String, String>> readNested(DataInputStream in) throws IOException {
    int n = in.readInt(); Map<String, Map<String, String>> m = new HashMap<>(Math.max(16, n * 4 / 3 + 1));
    for (int i = 0; i < n; i++) { String k = in.readUTF(); m.put(k, readMap(in)); }
    return m;
  }
}
