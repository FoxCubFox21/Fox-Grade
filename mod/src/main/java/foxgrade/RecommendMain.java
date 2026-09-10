package foxgrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Which version should I actually run?" — answered by porting for each candidate and comparing.
 *
 *  <p>Fox-Grade knows how well a given mod ports to a given version, and until now it only ever answered that
 *  question one version at a time, after the user had already chosen. Someone with a folder of mods deciding where
 *  to take their world is asking something different: not "will this work on 26.2" but "where does all of this work
 *  best". The pipeline can answer it in seconds per version without launching anything.
 *
 *  <p>Reports the quality score per mod per target, and the target that carries the most mods cleanly. What it does
 *  NOT claim is that the winner will boot — the score predicts loading well and correct behaviour poorly, and this
 *  inherits that limit exactly. It narrows the choice; it does not make it.
 *
 *  <p>Usage: {@code RecommendMain <gameDir> <target,target,…> <mod.jar>…} */
public final class RecommendMain {
  public static void main(String[] argv) throws Exception {
    if (argv.length < 3) {
      System.err.println("usage: RecommendMain <gameDir> <target,target,...> <mod.jar>...");
      System.exit(64);
    }
    Path gameDir = Path.of(argv[0]);
    List<String> targets = List.of(argv[1].split(","));
    List<Path> jars = new ArrayList<>();
    for (int i = 2; i < argv.length; i++) jars.add(Path.of(argv[i]));

    Map<String, Map<String, PortQuality.Score>> byTarget = new LinkedHashMap<>();
    for (String mc : targets) {
      if (!Targets.supported(mc)) {
        System.out.println("skipping " + mc + ": " + Targets.refusal(mc));
        continue;
      }
      Map<String, PortQuality.Score> row = new LinkedHashMap<>();
      Path cache = gameDir.resolve(".fox-grade").resolve("cache");
      RulesLoader rules = RulesLoader.load(mc, cache);
      IntermediaryBridge bridge = IntermediaryBridge.load(mc, cache);
      FabricApiBridges api = FabricApiBridges.load(gameDir, mc);
      Map<String, java.util.Set<String>> blocklist = MixinBlocklistLoader.load(gameDir);
      for (Path jar : jars) {
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", "");
        try {
          TransformPipeline.Outcome o = TransformPipeline.transform(jar, mc, rules, bridge, api, blocklist);
          row.put(name, o.quality());
        } catch (Exception cannotPort) {
          row.put(name, new PortQuality.Score(0, "cannot port", String.valueOf(cannotPort)));
        }
      }
      byTarget.put(mc, row);
    }
    if (byTarget.isEmpty()) { System.out.println("no measured target among those given"); return; }

    List<String> names = new ArrayList<>(byTarget.values().iterator().next().keySet());
    System.out.printf("%-34s", "mod");
    for (String mc : byTarget.keySet()) System.out.printf("%9s", mc);
    System.out.println();
    for (String n : names) {
      System.out.printf("%-34s", n.length() > 33 ? n.substring(0, 33) : n);
      for (String mc : byTarget.keySet()) System.out.printf("%8d%%", byTarget.get(mc).get(n).percent());
      System.out.println();
    }
    System.out.printf("%-34s", "average");
    String best = null; double bestAvg = -1;
    for (var e : byTarget.entrySet()) {
      double avg = e.getValue().values().stream().mapToInt(PortQuality.Score::percent).average().orElse(0);
      System.out.printf("%8.0f%%", avg);
      if (avg > bestAvg) { bestAvg = avg; best = e.getKey(); }
    }
    System.out.println();
    System.out.println();
    System.out.printf("Best of those measured: %s (%.0f%% average across %d mod(s))%n", best, bestAvg, names.size());
    System.out.println("A score is how much of a mod survived the port, not a promise that it boots.");
  }
}
