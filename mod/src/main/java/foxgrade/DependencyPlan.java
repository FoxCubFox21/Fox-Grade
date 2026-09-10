package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a set of mods needs from each other, worked out before any of them is ported.
 *
 *  <p>Fox-Grade ports a folder one jar at a time and never asks how the jars relate. That is fine until a mod
 *  depends on another one: the loader then refuses it outright, and the person reading the result sees a mod that
 *  "did not work" when what actually happened is that something it needs was never there. The same mistake in the
 *  test harness recorded Roughly Enough Items as a failure while Architectury and Cloth Config sat unused in the
 *  same directory — a porting verdict for a fact about the folder.
 *
 *  <p>So the folder is read as a set. Ports go out in dependency order, so a library is ported before whatever
 *  needs it, and anything asking for a mod that is nowhere to be found is named — with the mod that wants it, which
 *  is the part that makes it actionable. */
public final class DependencyPlan {
  private DependencyPlan() { }

  /** Ids the loader itself supplies, which are never "missing" in a way a user can act on. */
  private static final Set<String> AMBIENT =
      Set.of("minecraft", "java", "fabricloader", "fabric", "quilt_loader", "quilt_base", "neoforge", "forge");

  public record Plan(List<Path> order, Map<String, List<String>> missing) {
    /** One line per unmet dependency, phrased for someone who has to go and find it. */
    public List<String> messages() {
      List<String> out = new ArrayList<>();
      missing.forEach((mod, needs) -> out.add(mod + " needs " + String.join(", ", needs)
          + " — not in this folder and not installed, so the loader will refuse it"));
      return out;
    }
  }

  /** @param toPort    jars about to be ported
   *  @param installed jars already in mods/, which can satisfy a dependency without being ported */
  public static Plan of(List<Path> toPort, List<Path> installed) {
    Map<Path, Set<String>> provides = new LinkedHashMap<>();
    Map<Path, Set<String>> needs = new LinkedHashMap<>();
    Set<String> available = new LinkedHashSet<>();
    for (Path p : toPort) {
      Set<String> ids = idsOf(p);
      provides.put(p, ids);
      needs.put(p, dependsOf(p));
      available.addAll(ids);
    }
    for (Path p : installed) available.addAll(idsOf(p));

    Map<String, List<String>> missing = new LinkedHashMap<>();
    for (Path p : toPort) {
      List<String> absent = new ArrayList<>();
      for (String want : needs.get(p)) if (!available.contains(want)) absent.add(want);
      if (!absent.isEmpty()) missing.put(nameOf(p, provides.get(p)), absent);
    }

    // Dependency order, by repeatedly taking whatever has nothing left to wait for. A cycle — mods that declare
    // each other, which happens — stops the loop, and the remainder goes out in the order it arrived rather than
    // not at all: a cycle is a reason to stop sorting, not a reason to refuse to port.
    List<Path> order = new ArrayList<>();
    Set<Path> placed = new LinkedHashSet<>();
    boolean moved = true;
    while (moved && placed.size() < toPort.size()) {
      moved = false;
      for (Path p : toPort) {
        if (placed.contains(p)) continue;
        boolean ready = true;
        for (String want : needs.get(p)) {
          for (Path other : toPort) {
            if (other != p && !placed.contains(other) && provides.get(other).contains(want)) { ready = false; break; }
          }
          if (!ready) break;
        }
        if (ready) { order.add(p); placed.add(p); moved = true; }
      }
    }
    for (Path p : toPort) if (!placed.contains(p)) order.add(p);
    return new Plan(order, missing);
  }

  private static String nameOf(Path p, Set<String> ids) {
    return ids.isEmpty() ? p.getFileName().toString() : ids.iterator().next();
  }

  /** Every id this jar answers to — its own, and anything it declares it provides. */
  static Set<String> idsOf(Path jar) {
    Set<String> out = new LinkedHashSet<>();
    JsonObject m = manifest(jar);
    if (m == null) return out;
    if (m.has("id")) out.add(m.get("id").getAsString());
    if (m.has("provides") && m.get("provides").isJsonArray()) {
      for (var e : m.getAsJsonArray("provides")) out.add(e.getAsString());
    }
    return out;
  }

  /** Hard dependencies only. A recommendation is not a reason to call a port broken. */
  static Set<String> dependsOf(Path jar) {
    Set<String> out = new LinkedHashSet<>();
    JsonObject m = manifest(jar);
    if (m == null || !m.has("depends") || !m.get("depends").isJsonObject()) return out;
    for (String k : Json.keys(m.getAsJsonObject("depends"))) {
      // Fabric API ships as dozens of modules; a dependency on any one of them is a dependency on Fabric API, and
      // sending someone to look for "fabric-block-api-v1" by name helps nobody.
      if (AMBIENT.contains(k) || k.startsWith("fabric-")) continue;
      out.add(k);
    }
    return out;
  }

  private static JsonObject manifest(Path jar) {
    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
      var e = z.getEntry("fabric.mod.json");
      if (e == null) return null;
      return new Gson().fromJson(new String(z.getInputStream(e).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
    } catch (Exception unreadable) {
      return null;
    }
  }

  /** The jars in a directory, or an empty list. */
  public static List<Path> jarsIn(Path dir) {
    if (!Files.isDirectory(dir)) return List.of();
    try (var s = Files.list(dir)) {
      return s.filter((f) -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
    } catch (Exception unreadable) {
      return List.of();
    }
  }
}
