// Tiny readers for a jar's fabric.mod.json identity — used by the inbox dependency pre-check.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class JarDeps {
  private JarDeps() { }

  /** The ids a jar's fabric.mod.json says it provides (cloth-config → cloth-config2); empty when none. */
  static java.util.List<String> providesOf(Path jar) {
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      String text = QuiltMeta.fabricMeta(zf);
      if (text == null) return java.util.List.of();
      JsonObject meta = new Gson().fromJson(text, JsonObject.class);
      java.util.List<String> out = new java.util.ArrayList<>();
      if (meta != null && meta.has("provides") && meta.get("provides").isJsonArray()) for (var x : meta.getAsJsonArray("provides")) out.add(x.getAsString());
      return out;
    } catch (Exception ex) { return java.util.List.of(); }
  }
  /** Ids (and provides) of the jars a mod bundles under META-INF/jars — a dependency satisfied by the mod itself. */
  static java.util.List<String> nestedIds(Path jar) {
    java.util.List<String> out = new java.util.ArrayList<>();
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      var en = zf.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
        try (var nested = new java.util.zip.ZipInputStream(zf.getInputStream(e))) {
          ZipEntry ne;
          while ((ne = nested.getNextEntry()) != null) {
            if (!ne.getName().equals("fabric.mod.json")) continue;
            JsonObject meta = new Gson().fromJson(new String(nested.readAllBytes()), JsonObject.class);
            if (meta != null && meta.has("id")) out.add(meta.get("id").getAsString());
            if (meta != null && meta.has("provides") && meta.get("provides").isJsonArray()) for (var x : meta.getAsJsonArray("provides")) out.add(x.getAsString());
            break;
          }
        } catch (Exception ignore) { }
      }
    } catch (Exception ex) { }
    return out;
  }
  static String idOf(Path jar) {
    try (ZipFile zf = new ZipFile(jar.toFile())) {
      String text = QuiltMeta.fabricMeta(zf);
      if (text == null) return null;
      JsonObject meta = new Gson().fromJson(text, JsonObject.class);
      return meta != null && meta.has("id") ? meta.get("id").getAsString() : null;
    } catch (Exception ex) { return null; }
  }

  // Hard-dep ids declared by jar BYTES (used on the freshly transformed output before install).
  static List<String> dependsOf(byte[] jarBytes) {
    List<String> out = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        if (!e.getName().equals("fabric.mod.json")) continue;
        JsonObject meta = new Gson().fromJson(new String(in.readAllBytes()), JsonObject.class);
        if (meta != null && meta.has("depends") && meta.get("depends").isJsonObject()) {
          for (var d : meta.getAsJsonObject("depends").entrySet()) out.add(d.getKey());
        }
        break;
      }
    } catch (Exception ignored) { }
    return out;
  }
}
