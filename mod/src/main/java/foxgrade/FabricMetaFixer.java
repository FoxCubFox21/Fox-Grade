// Rewrite the minecraft dep in a mod's fabric.mod.json so Fabric stops blocking it at version
// check. Only touches metadata — the mod's bytecode is untouched. This is the safest transform
// Fox-Grade does; if the mod's actual API usage happens to be compatible with the new MC, this
// alone unblocks it. If it isn't, the mod will still crash on class-load somewhere else and
// we'll catch that in the bytecode remap stage.
//
// The dep is narrowed to EXACTLY the target version — not widened to "*". This jar has been
// blessed for one specific MC and should not claim to run on others.
//
// Signature files under META-INF are removed, since a rewritten jar cannot satisfy the original
// signature anyway.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class FabricMetaFixer {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  // Signature files a modified jar can no longer satisfy — these get dropped on rewrite.
  private static final Pattern SIG = Pattern.compile("META-INF/.*\\.(SF|RSA|DSA|EC)$", Pattern.CASE_INSENSITIVE);

  // Rewrite one jar: read from src, write to dst, updating fabric.mod.json's minecraft dep to
  // `targetMc`. Returns true if the fabric.mod.json was actually changed.
  public static boolean widenMcRange(Path src, Path dst, String targetMc) throws IOException {
    boolean touched = false;
    try (ZipFile in = new ZipFile(src.toFile());
         ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dst))) {
      var entries = in.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (SIG.matcher(name).matches()) continue;               // strip stale signatures
        if (e.isDirectory()) { out.putNextEntry(new ZipEntry(name)); out.closeEntry(); continue; }
        if (name.equals("fabric.mod.json")) {
          String text;
          try (InputStream is = in.getInputStream(e)) { text = new String(is.readAllBytes(), StandardCharsets.UTF_8); }
          JsonObject meta = GSON.fromJson(text, JsonObject.class);
          if (rewriteMeta(meta, targetMc)) touched = true;
          byte[] bytes = (GSON.toJson(meta) + "\n").getBytes(StandardCharsets.UTF_8);
          writeEntry(out, name, bytes);
          continue;
        }
        // Everything else: passthrough
        try (InputStream is = in.getInputStream(e)) {
          out.putNextEntry(new ZipEntry(name));
          is.transferTo(out);
          out.closeEntry();
        }
      }
    }
    return touched;
  }

  // The rule: `depends.minecraft` is pinned to the exact target; other version-facing fields
  // (recommends/suggests/breaks/conflicts) have their minecraft claim removed — a jar we've
  // ported to 26.2 has no informed opinion on what breaks on 26.3.
  public static boolean rewriteMeta(JsonObject meta, String targetMc) {
    boolean touched = false;
    for (String field : new String[]{"depends", "recommends", "suggests", "breaks", "conflicts"}) {
      if (!meta.has(field) || !meta.get(field).isJsonObject()) continue;
      JsonObject o = meta.getAsJsonObject(field);
      if (field.equals("depends") && o.has("fabric")) {
        // Legacy Fabric-API id: mods built before ~1.19.2 depend on "fabric"; the modern
        // fabric-api jar no longer provides that alias, so the dep can never resolve. Swap it
        // for the current id, any version — the bytecode bridge handles actual API drift.
        o.remove("fabric");
        if (!o.has("fabric-api")) o.addProperty("fabric-api", "*");
        touched = true;
      }
      if (!o.has("minecraft")) continue;
      if (field.equals("depends")) { o.addProperty("minecraft", targetMc); touched = true; }
      else { o.remove("minecraft"); touched = true; }
    }
    return touched;
  }

  private static void writeEntry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
    ZipEntry e = new ZipEntry(name);
    out.putNextEntry(e);
    out.write(bytes);
    out.closeEntry();
  }
}
