package foxgrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Standalone "will it port?" checker: runs the real Fox-Grade pipeline on jars without launching
 *  the game and prints what 26.2 no longer has. Exit 0 = nothing unresolved, 2 = something is. */
public final class CheckMain {
  public static void main(String[] argv) throws Exception {
    if (argv.length < 3) {
      System.err.println("usage: CheckMain <mcVersion> <gameDir> [--out DIR] [--json] <mod.jar>...");
      System.exit(64);
    }
    String mc = argv[0]; Path gameDir = Path.of(argv[1]); Path out = null; boolean json = false;
    List<Path> jars = new ArrayList<>();
    for (int i = 2; i < argv.length; i++) {
      if (argv[i].equals("--out") && i + 1 < argv.length) { out = Path.of(argv[++i]); Files.createDirectories(out); }
      else if (argv[i].equals("--json")) json = true;
      else jars.add(Path.of(argv[i]));
    }
    RulesLoader rules = RulesLoader.load(mc);
    IntermediaryBridge bridge = IntermediaryBridge.load(mc);
    FabricApiBridges api = FabricApiBridges.load(gameDir);
    Map<String, Set<String>> blocklist = MixinBlocklistLoader.load(gameDir);
    int worst = 0;
    StringBuilder jsonOut = new StringBuilder("[");
    for (Path jar : jars) {
      TransformPipeline.Outcome o;
      try {
        o = TransformPipeline.transform(jar, mc, rules, bridge, api, blocklist);
      } catch (Exception e) {
        System.out.println(jar.getFileName() + ": FAILED — " + e);
        worst = Math.max(worst, 3);
        continue;
      }
      System.out.println(jar.getFileName() + ": " + o.oneLine());
      Map<String, List<String>> byClass = new TreeMap<>();
      for (String ref : o.unresolvedRefs) {
        int hash = ref.indexOf('#');
        String owner = hash < 0 ? ref : ref.substring(0, hash);
        byClass.computeIfAbsent(owner, k -> new ArrayList<>()).add(hash < 0 ? "(class)" : ref.substring(hash + 1));
      }
      for (var e : byClass.entrySet()) {
        System.out.println("    " + e.getKey().replace('/', '.'));
        for (String m : e.getValue()) System.out.println("        " + m);
      }
      if (!o.deregisteredMixins.isEmpty()) System.out.println("    mixins removed: " + String.join(", ", o.deregisteredMixins));
      if (out != null) {
        Path dst = out.resolve(jar.getFileName().toString().replaceFirst("\\.jar$", "") + "-fgport.jar");
        Files.write(dst, o.outputBytes);
        System.out.println("    wrote " + dst);
      }
      if (json) {
        if (jsonOut.length() > 1) jsonOut.append(',');
        jsonOut.append("{\"jar\":\"").append(jar.getFileName()).append("\",\"unresolved\":").append(o.unresolvedRefs.size())
            .append(",\"mixinsRemoved\":").append(o.deregisteredMixins.size()).append(",\"refs\":[");
        boolean first = true;
        for (String r : o.unresolvedRefs) { if (!first) jsonOut.append(','); first = false; jsonOut.append('"').append(r.replace("\"", "\\\"")).append('"'); }
        jsonOut.append("]}");
      }
      if (!o.unresolvedRefs.isEmpty()) worst = Math.max(worst, 2);
    }
    if (json) System.out.println(jsonOut.append(']'));
    System.exit(worst);
  }
}
