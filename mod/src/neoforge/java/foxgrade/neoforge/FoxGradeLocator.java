package foxgrade.neoforge;

import foxgrade.FabricApiBridges;
import foxgrade.IntermediaryBridge;
import foxgrade.Loaders;
import foxgrade.MixinBlocklistLoader;
import foxgrade.RulesLoader;
import foxgrade.TransformPipeline;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Fox-Grade's NeoForge entry point.
 *
 *  <p>This is where NeoForge is a better host than Fabric. Fabric commits to its mod set before any mod code runs, so a
 *  port can only take effect by relaunching the game. NeoForge asks registered locators for candidates while discovery
 *  is still open, so Fox-Grade can port a jar and hand the result straight to the loader: the mod comes up running on
 *  the same launch, with no restart at all.
 *
 *  <p>Registered through {@code META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator}. It is
 *  compiled separately against NeoForge and is never loaded on Fabric, where the service file means nothing.
 *
 *  <p>Everything below the entry point is the same engine the Fabric path uses — the porting work is about Minecraft
 *  versions, not about which loader is reading the jars. */
public final class FoxGradeLocator implements IModFileCandidateLocator {

  /** Where a user drops an old jar, checked in order. Mirrors the Fabric layout so one install works on both. */
  private static final String[] INBOXES = {"mods/fox-grade-inbox", "fox-grade-inbox"};

  @Override
  public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
    try {
      locate(pipeline);
    } catch (Throwable failedButNotFatal) {
      // A porter must never be the reason a game will not start. Report and let discovery carry on without us.
      System.err.println("[Fox-Grade] locator stopped early: " + failedButNotFatal);
    }
  }

  private void locate(IDiscoveryPipeline pipeline) throws IOException {
    Path gameDir = Loaders.current().gameDir();
    String mc = Loaders.current().gameVersion();
    List<Path> inbox = new ArrayList<>();
    for (String rel : INBOXES) {
      Path dir = gameDir.resolve(rel);
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> files = Files.list(dir)) {
        files.filter((f) -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(inbox::add);
      }
    }
    if (inbox.isEmpty()) return;
    if (!foxgrade.Targets.supported(mc)) {
      System.err.println("[Fox-Grade] " + foxgrade.Targets.refusal(mc));
      return;
    }

    System.err.println("[Fox-Grade] " + inbox.size() + " jar(s) in the inbox; porting for " + mc + " before discovery closes");
    Path cacheDir = gameDir.resolve(".fox-grade").resolve("cache");
    RulesLoader rules = RulesLoader.load(mc, cacheDir);
    IntermediaryBridge bridge = IntermediaryBridge.load(mc, cacheDir);
    FabricApiBridges apiBridges = FabricApiBridges.load(gameDir, mc);
    Map<String, Set<String>> blocklist;
    try { blocklist = MixinBlocklistLoader.load(gameDir); } catch (IOException noBlocklist) { blocklist = Map.of(); }

    Path out = gameDir.resolve("mods");
    Files.createDirectories(out);
    for (Path jar : inbox) {
      try {
        TransformPipeline.Outcome outcome = TransformPipeline.transform(jar, mc, rules, bridge, apiBridges, blocklist);
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", "-fgport.jar");
        Path ported = out.resolve(name);
        Files.write(ported, outcome.outputBytes);
        // Hand it to the loader directly. This is the whole point of running as a locator rather than as a mod.
        // WARN rather than ERROR: a port the loader still cannot read should be a complaint in the log, not a
        // refusal to start the game. The port report already says what could not be carried over.
        pipeline.addPath(ported, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        System.err.println("[Fox-Grade]   ported " + jar.getFileName() + " → " + name + " and handed it to discovery");
      } catch (Throwable oneJarFailed) {
        System.err.println("[Fox-Grade]   " + jar.getFileName() + " could not be ported: " + oneJarFailed);
      }
    }
  }
}
