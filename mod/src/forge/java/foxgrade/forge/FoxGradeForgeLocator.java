package foxgrade.forge;

import foxgrade.FabricApiBridges;
import foxgrade.IntermediaryBridge;
import foxgrade.Loaders;
import foxgrade.MixinBlocklistLoader;
import foxgrade.RulesLoader;
import foxgrade.Targets;
import foxgrade.TransformPipeline;

import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Fox-Grade's Forge entry point.
 *
 *  <p>The same idea as the NeoForge locator and a different shape, because Forge kept the older SPI the two share an
 *  ancestor in. NeoForge hands candidates to a discovery pipeline as paths; Forge asks a locator to return mod files
 *  it has built itself, which {@link AbstractModProvider#createMod} does from a path. Either way the port happens
 *  while the loader is still deciding what to load, so the mod comes up on the same launch with no restart.
 *
 *  <p>Registered through {@code META-INF/services/net.minecraftforge.forgespi.locating.IModLocator}. Compiled
 *  separately against Forge and never loaded anywhere else, where the service file means nothing.
 *
 *  <p>Everything below the entry point is the engine every other loader uses. Which loader is reading the jars has
 *  never been the interesting part of porting; the Minecraft version is. */
public final class FoxGradeForgeLocator extends AbstractModProvider implements IModLocator {

  /** Where a user drops an old jar, checked in order. The same layout as every other loader, so one install works. */
  private static final String[] INBOXES = {"mods/fox-grade-inbox", "fox-grade-inbox"};

  @Override
  public String name() {
    return "fox-grade";
  }

  @Override
  public List<ModFileOrException> scanMods() {
    try {
      return locate();
    } catch (Throwable failedButNotFatal) {
      // A porter must never be the reason a game will not start. Report and let discovery carry on without us.
      System.err.println("[Fox-Grade] locator stopped early: " + failedButNotFatal);
      return List.of();
    }
  }

  /** Forge asks providers to scan a file it already has; Fox-Grade contributes only its own ports, so there is
   *  nothing to add here. */
  @Override
  public void scanFile(IModFile file, Consumer<Path> pathConsumer) { }

  private List<ModFileOrException> locate() throws IOException {
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
    if (inbox.isEmpty()) return List.of();
    if (!Targets.supported(mc)) {
      System.err.println("[Fox-Grade] " + Targets.refusal(mc));
      return List.of();
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
    List<ModFileOrException> found = new ArrayList<>();
    for (Path jar : inbox) {
      try {
        TransformPipeline.Outcome outcome = TransformPipeline.transform(jar, mc, rules, bridge, apiBridges, blocklist);
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", "-fgport.jar");
        Path ported = out.resolve(name);
        Files.write(ported, outcome.outputBytes);
        found.add(createMod(ported));
        System.err.println("[Fox-Grade]   ported " + jar.getFileName() + " → " + name + " and handed it to discovery");
      } catch (Throwable oneJarFailed) {
        System.err.println("[Fox-Grade]   " + jar.getFileName() + " could not be ported: " + oneJarFailed);
      }
    }
    return found;
  }
}
