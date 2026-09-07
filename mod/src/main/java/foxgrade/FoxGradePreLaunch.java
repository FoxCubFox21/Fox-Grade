// Fox-Grade — Fabric preLaunch entrypoint.
//
// Runs BEFORE Fabric's own mod discovery finishes. This is where a mod-porting tool has to sit:
// by the time preLaunch finishes, `mods/` must already contain the FINAL set of jars Fabric will
// scan for classes and mixins. Anything ported here is picked up on the next launch — Fabric has
// already resolved the current one's mod set before preLaunch fires, so a mod ported now will
// take effect on restart.
//
// Design rules (borrowed from the Node CLI Fox-Grade grew out of):
//   · nothing is ever deleted — originals move to mods-backup/ before anything is written
//   · nothing that failed a check is installed. "I could not port this" is a fine answer;
//     a mod that loads and quietly misbehaves is not
//   · an official build from the mod's own author always beats a port of ours
//   · every failure says what the PERSON should do, not what went wrong inside the jar
//
// v0.5.0: full transform pipeline. When the user opts a mod into fox-grade.config.json (add its
// id to `port`, or set `portAll: true`), Fox-Grade backs the original up and writes a rewritten
// jar in its place: fabric.mod.json range narrowed, bytecode remapped via ASM, .accesswidener
// and mixin refmap rewritten, mixin blocklist applied. All safe fallbacks — if a stage throws,
// the original is left alone and the mod is marked ERROR with the reason.
package foxgrade;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FoxGradePreLaunch implements PreLaunchEntrypoint {
  static final String VERSION = "1.1.0";

  @Override public void onPreLaunch() {
    FabricLoader loader = FabricLoader.getInstance();
    Path gameDir = loader.getGameDir();
    Path modsDir = gameDir.resolve("mods");
    Path backupDir = gameDir.resolve("mods-backup");
    String mc = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();

    log("Fox-Grade " + VERSION + " — target MC " + mc);
    if (!Files.isDirectory(modsDir)) { log("  no mods folder; nothing to do."); return; }

    Config cfg;
    try { cfg = Config.load(gameDir); }
    catch (IOException e) { log("  ! could not read config: " + e.getMessage()); return; }

    RulesLoader rules;
    try { rules = RulesLoader.load(mc); }
    catch (IOException e) { log("  ! could not load bundled rules.json.gz: " + e.getMessage()); return; }
    log("  loaded " + rules.size() + " verified class rename(s) for " + mc);

    IntermediaryBridge bridge;
    try { bridge = IntermediaryBridge.load(mc); }
    catch (IOException e) {
      log("  ! could not load intermediary bridge: " + e.getMessage());
      try { bridge = IntermediaryBridge.load(""); } catch (IOException ignored) { return; }
    }
    log("  loaded " + bridge.size() + " intermediary→mojang class + " + bridge.memberCount() + " member rename(s) for " + mc);
    // The bundled tables are built for ONE target version, and they load EMPTY rather than
    // failing on any other. Without this guard Fox-Grade would "port" a mod without translating
    // a single name, skip mixin verification entirely (the class inventory is missing too), and
    // still stamp the jar as compatible — producing something guaranteed to crash. Do nothing
    // instead, and say why.
    if (bridge.size() == 0) {
      log("");
      log("  This build of Fox-Grade carries translation tables for one Minecraft version, and");
      log("  none of them are for " + mc + ". Nothing has been ported and nothing was changed.");
      log("  Install the Fox-Grade build made for " + mc + " and your mods will be ported then.");
      log("");
      return;
    }

    FabricApiBridges apiBridges;
    try { apiBridges = FabricApiBridges.load(gameDir); }
    catch (IOException e) {
      log("  ! could not load api bridges: " + e.getMessage());
      try { apiBridges = FabricApiBridges.load(gameDir.resolve("does-not-exist")); } catch (IOException ignored) { return; }
    }
    log("  loaded " + apiBridges.size() + " third-party API rename(s)");

    Map<String, Set<String>> blocklist;
    try { blocklist = MixinBlocklistLoader.load(gameDir); }
    catch (IOException e) { log("  ! could not load mixin blocklist: " + e.getMessage()); blocklist = Map.of(); }

    List<ModsScanner.ModInfo> mods;
    try { mods = ModsScanner.scan(modsDir); }
    catch (IOException e) { log("  ! could not list mods folder: " + e.getMessage()); return; }

    BackupService backup = new BackupService(backupDir);

    // The inbox: <gamedir>/fox-grade-inbox/. Fabric reads every candidate jar's access widener
    // BEFORE any entrypoint runs, so an old mod dropped straight into mods/ can kill the loader
    // before Fox-Grade exists. Jars in the inbox are invisible to Fabric; placing one there IS
    // the opt-in. Each gets the full pipeline, the ported jar lands in mods/, the original moves
    // to fox-grade-inbox/processed/, and the game restarts itself with the result.
    // The inbox sits INSIDE mods/ — the folder people already open to install things — as a
    // subfolder Fabric never scans (it only reads top-level jars). The 1.0.x location next to
    // mods/ is still honoured so nobody who followed the old docs is stranded.
    Path inbox = modsDir.resolve("fox-grade-inbox");
    Path legacyInbox = gameDir.resolve("fox-grade-inbox");
    int inboxPorted = 0;
    try {
      if (!Files.isDirectory(inbox)) {
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("README.txt"),
            "Drop mods built for OLDER Minecraft versions in here.\n" +
            "On the next launch Fox-Grade ports them, installs the result into mods/,\n" +
            "moves the original into processed/, and restarts the game automatically.\n");
      }
      // Zero-configuration path: an old mod dropped straight into mods/ is moved to the inbox now.
      for (Path movedIn : AutoInbox.sweep(modsDir, inbox, mc, FoxGradePreLaunch::log)) { /* logged by the sweep */ }
      java.util.List<Path> inboxJars = new java.util.ArrayList<>();
      for (Path dir : java.util.List.of(inbox, legacyInbox)) {
        if (!Files.isDirectory(dir)) continue;
        try (var st = Files.list(dir)) {
          inboxJars.addAll(st.filter((f) -> f.getFileName().toString().endsWith(".jar")).sorted().toList());
        }
      }
      // Dependency pre-check: a ported jar whose hard deps can't resolve doesn't crash THIS
      // launch — it bricks the NEXT one into Fabric's error screen, before Fox-Grade can even
      // run. So nothing with an unsatisfiable dep is installed. Ids satisfied by installed
      // mods, by OTHER jars in this same inbox batch, or by the loader's builtins all count.
      Set<String> satisfiable = new java.util.HashSet<>(java.util.List.of("minecraft", "java", "fabricloader"));
      for (var m : mods) { satisfiable.add(m.id); if (m.jar != null) satisfiable.addAll(JarDeps.providesOf(m.jar)); }   // cloth-config provides cloth-config2
      for (Path jar : inboxJars) {
        String id = JarDeps.idOf(jar);
        if (id != null) satisfiable.add(id);
        satisfiable.addAll(JarDeps.providesOf(jar));
      }
      for (Path jar : inboxJars) {
        try {
          TransformPipeline.Outcome o = TransformPipeline.transform(jar, mc, rules, bridge, apiBridges, blocklist);
          java.util.List<String> missing = new java.util.ArrayList<>();
          for (String dep : JarDeps.dependsOf(o.outputBytes)) {
            if (satisfiable.contains(dep) || dep.startsWith("fabric-") || dep.equals("fabric")) continue;
            missing.add(dep);
          }
          if (!missing.isEmpty()) {
            log("  INBOX HELD     " + jar.getFileName() + " — needs " + String.join(", ", missing)
                + "; drop " + (missing.size() == 1 ? "it" : "them") + " in the inbox too (old versions are fine — they get ported alongside)");
            continue;
          }
          String outName = jar.getFileName().toString().replaceAll("\\.jar$", "") + "-fgport.jar";
          Files.write(modsDir.resolve(outName), o.outputBytes);
          Path processed = inbox.resolve("processed");
          Files.createDirectories(processed);
          Files.move(jar, processed.resolve(jar.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          inboxPorted++;
          FoxGradeStats.recordPort(gameDir, o);
          log("  INBOX PORTED   " + jar.getFileName() + " → mods/" + outName + "   — " + o.oneLine());
        } catch (Exception e) {
          log("  INBOX ERROR    " + jar.getFileName() + " — " + e.getMessage() + " (left in inbox)");
        }
      }
    } catch (IOException e) {
      log("  ! inbox unavailable: " + e.getMessage());
    }
    if (inboxPorted > 0) {
      log("");
      log("  " + inboxPorted + " mod(s) ported from the inbox.");
      if (relaunchSelf()) log("  Restarting the game automatically — they will be live in a moment.");
      else log("  " + restartHint());
      log("");
      System.exit(0);
    }

    // Crash guard: if the PREVIOUS launch crashed and the report blames one of our ports,
    // retire that port right now and relaunch clean. A port must never be able to keep the
    // game down twice — the user asked for exactly this: if it breaks things, override it.
    // Only the report's head (description + stack trace, everything before System Details)
    // is searched: the System Details section lists EVERY installed mod and would false-match.
    try {
      Path crashDir = gameDir.resolve("crash-reports");
      Path seenMarker = gameDir.resolve(".fox-grade-crash-seen");
      if (Files.isDirectory(crashDir)) {
        Path newest = null;
        try (var st = Files.list(crashDir)) {
          newest = st.filter((f) -> f.getFileName().toString().endsWith(".txt"))
              .max(java.util.Comparator.comparing((f) -> f.getFileName().toString())).orElse(null);
        }
        String seen = Files.exists(seenMarker) ? Files.readString(seenMarker).trim() : null;
        if (newest != null && seen == null) {
          // First run ever: baseline on the newest existing report without acting — an old
          // crash from before Fox-Grade (or before this feature) must not retire anything now.
          Files.writeString(seenMarker, newest.getFileName().toString());
        } else if (newest != null && !newest.getFileName().toString().equals(seen)) {
          String head = Files.readString(newest);
          int cut = head.indexOf("-- System Details --");
          if (cut > 0) head = head.substring(0, cut);
          // Enumeration lines (resource packs, datapacks) name EVERY installed port — only
          // stack frames and suspected-mod lines may assign blame, so those lists are dropped.
          StringBuilder scanned = new StringBuilder();
          for (String line : head.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Packs:") || t.startsWith("Enabled data packs") || t.startsWith("Available data packs")) continue;
            scanned.append(line).append('\n');
          }
          java.util.Set<String> blamed = new java.util.TreeSet<>();
          var mm = java.util.regex.Pattern.compile("([a-z0-9_-]+?)_fgport").matcher(scanned);
          while (mm.find()) blamed.add(mm.group(1) + "_fgport");
          java.util.List<Path> retiredJars = new java.util.ArrayList<>();
          for (String badId : blamed) {
            for (var m : mods) {
              if (!m.id.equals(badId)) continue;
              try {
                Path moved = new BackupService(backupDir).moveToBackup(m.jar);
                retiredJars.add(moved);
                log("  CRASH GUARD    last launch crashed because of '" + badId + "' — retired its jar to " + moved.getFileName());
              } catch (IOException e) {
                log("  ! crash guard could not retire " + m.jar.getFileName() + ": " + e.getMessage());
              }
            }
          }
          Files.writeString(seenMarker, newest.getFileName().toString());
          if (!retiredJars.isEmpty()) {
            log("");
            log("  " + retiredJars.size() + " port(s) retired by the crash guard. The original jar(s) are safe in mods-backup/.");
            // Force the relaunch even when this process was itself a relaunch child: every guard
            // pass retires at least one jar, so the chain is bounded by the number of ports.
            if (relaunchSelf(true, java.util.List.of())) log("  Restarting clean…");
            else log("  " + (isDedicatedServer() ? "Restart the server for a clean start." : "Please LAUNCH AGAIN for a clean start."));
            log("");
            System.exit(0);
          }
        }
      }
    } catch (Exception e) {
      log("  ! crash guard error (ignored): " + e.getMessage());
    }

    // Yield-to-official: a port always loses to the author's real build. If both are present
    // (port marker + original id living on another jar), the port retires to mods-backup/ and
    // the launch restarts clean — one loud message instead of a runtime double-registration mess.
    java.util.Map<String, ModsScanner.ModInfo> byId = new java.util.HashMap<>();
    for (var m : mods) byId.put(m.id, m);
    java.util.List<ModsScanner.ModInfo> retired = new java.util.ArrayList<>();
    for (var m : mods) {
      if (!m.id.endsWith("_fgport")) continue;
      String orig = m.id.substring(0, m.id.length() - "_fgport".length());
      if (byId.containsKey(orig)) {
        try {
          Path moved = backup.moveToBackup(m.jar);
          retired.add(m);
          log("  OFFICIAL BUILD FOUND for '" + orig + "' — Fox-Grade retired its port to " + moved.getFileName());
        } catch (IOException e) {
          log("  ! could not retire port " + m.jar.getFileName() + ": " + e.getMessage());
        }
      }
    }
    if (!retired.isEmpty()) {
      log("");
      log("  " + retired.size() + " ported mod(s) yielded to the author's official build.");
      if (relaunchSelf()) {
        log("  Restarting the game automatically with the clean mod set…");
      } else {
        log("  Both were loaded for THIS run — " + (isDedicatedServer() ? "restart the server." : "please LAUNCH AGAIN."));
      }
      log("");
      System.exit(0);
    }
    Report report = new Report();
    for (var m : mods) {
      if ("foxgrade".equals(m.id)) continue;
      report.add(handleOne(m, mc, cfg, rules, bridge, apiBridges, blocklist, backup));
    }
    for (var row : report.rows()) {
      log(String.format("  %-14s %s   %s%s",
          row.verdict.name(), row.modId, row.jar.getFileName(),
          row.detail == null || row.detail.isEmpty() ? "" : "   — " + row.detail));
    }
    log("  " + report.oneLine());

    Path reportFile = gameDir.resolve("fox-grade-report.txt");
    try { report.writeTo(reportFile); log("  wrote " + reportFile); }
    catch (IOException e) { log("  ! could not write report: " + e.getMessage()); }

    // If anything was ported on THIS launch, Fabric has already cached the pre-port bytes for
    // those mods (its mod discovery ran before Fox-Grade's preLaunch). The rewritten jars will
    // load correctly on the NEXT launch — bail out now so we don't crash with the stale bytes.
    // See the README for why: preLaunch is too late for in-place transforms. A custom mod
    // locator would fix this properly; until then, ask the user to restart.
    int portedCount = report.summary().get(Report.Verdict.PORTED);
    if (portedCount > 0) {
      log("");
      log("  " + portedCount + " mod(s) were ported this launch. Fabric cached their pre-port");
      log("  classes, so a fresh process is needed for the ports to take effect.");
      if (relaunchSelf()) {
        log("  Restarting the game automatically — the ported mods will be live in a moment.");
      } else {
        log("  " + (isDedicatedServer() ? "Restart the server" : "Please LAUNCH AGAIN") + " — the ports are saved and will load cleanly next start.");
      }
      log("");
      System.exit(0);
    }
  }

  private Report.Row handleOne(ModsScanner.ModInfo m, String mc, Config cfg, RulesLoader rules,
                               IntermediaryBridge bridge, FabricApiBridges apiBridges, Map<String, Set<String>> blocklist, BackupService backup) {
    Path jar = m.jar;
    if (m.error != null) return new Report.Row(m.id, jar, Report.Verdict.ERROR, m.error);
    if (!m.hasFabricJson) return new Report.Row(m.id, jar, Report.Verdict.NON_FABRIC, null);
    boolean optedIn = cfg.shouldPort(m.id);
    boolean rangeCovers = m.mcRange == null || VersionRange.satisfies(m.mcRange, mc);
    // Opt-in is authoritative. If the user names a mod in `port`, run it through the pipeline even
    // when its declared range already covers the target — the bytecode may still reference an old
    // MC namespace (e.g. appleskin-1.21 declares no MC dep at all, but its classes point at
    // intermediary names that need translation). "READY" is a classification signal, not a veto.
    if (!optedIn) {
      if (rangeCovers) return new Report.Row(m.id, jar, Report.Verdict.READY, m.mcRange == null ? "no minecraft dep" : "declared: " + m.mcRange);
      return new Report.Row(m.id, jar, Report.Verdict.NEEDS_PORTING,
          "declared: " + m.mcRange + "  (add \"" + m.id + "\" to fox-grade.config.json → port)");
    }
    // Idempotency: if a previous launch already ported this jar for this MC, don't churn.
    if (TransformPipeline.isAlreadyPortedFor(jar, mc)) {
      return new Report.Row(m.id, jar, Report.Verdict.READY, "already ported for " + mc + " on an earlier launch");
    }
    try {
      TransformPipeline.Outcome o = TransformPipeline.transform(jar, mc, rules, bridge, apiBridges, blocklist);
      Path backupPath = backup.moveToBackup(jar);
      Files.write(jar, o.outputBytes);
      FoxGradeStats.recordPort(jar.getParent().getParent(), o);
      return new Report.Row(m.id, jar, Report.Verdict.PORTED, o.oneLine() + "; backup: " + backupPath.getFileName());
    } catch (Exception e) {
      return new Report.Row(m.id, jar, Report.Verdict.ERROR, "transform failed: " + e.getMessage());
    }
  }

  // Kill the double-launch: spawn a fresh JVM with this process's exact command line, then let
  // this one die. Fabric cached the pre-port bytecode the moment it resolved mods — too early
  // for any entrypoint to prevent — so the only way to a correct SAME-CLICK boot is a clean
  // process that scans the now-ported jars from scratch. The child inherits stdio so its log
  // lands in the same launcher console; FOXGRADE_RELAUNCHED guards against loops (if the child
  // somehow ports again, it falls back to the old restart message instead of forking forever).
  // A dedicated server's process is owned by systemd, a hosting panel or a screen session.
  // Forking a replacement and exiting would orphan the child or kill the server outright, so the
  // restart-to-apply step never runs there — the operator restarts it the way their setup expects.
  static boolean isDedicatedServer() {
    try {
      return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER;
    } catch (Throwable t) { return false; }
  }

  private static String restartHint() {
    return isDedicatedServer() ? "Restart the server to load them." : "Please LAUNCH AGAIN to load them.";
  }

  private static boolean relaunchSelf() { return relaunchSelf(false, java.util.List.of()); }

  // force=true is the panel's user-initiated restart: it must work even inside a process that
  // was itself a relaunch child (FOXGRADE_RELAUNCHED set) — a human clicking Restart can't loop.
  // extraArgs land at the END of the command line (program args); any pre-existing
  // --quickPlaySingleplayer pair is stripped first so a test-run world doesn't stack with one
  // from the current launch.
  public static boolean relaunchSelf(boolean force, java.util.List<String> extraArgs) {
    if (isDedicatedServer()) return false;   // see isDedicatedServer(): never fork a server process
    if (!force && System.getenv("FOXGRADE_RELAUNCHED") != null) return false;
    try {
      var info = ProcessHandle.current().info();
      var cmd = info.command();
      var args = info.arguments();
      if (cmd.isEmpty() || args.isEmpty()) return false;
      java.util.List<String> full = new java.util.ArrayList<>();
      full.add(cmd.get());
      String[] a = args.get();
      for (int i = 0; i < a.length; i++) {
        if (!extraArgs.isEmpty() && a[i].equals("--quickPlaySingleplayer")) { i++; continue; }
        full.add(a[i]);
      }
      full.addAll(extraArgs);
      ProcessBuilder pb = new ProcessBuilder(full);
      pb.environment().put("FOXGRADE_RELAUNCHED", "1");
      pb.inheritIO();
      pb.start();
      return true;
    } catch (Exception e) {
      log("  ! self-relaunch failed: " + e.getMessage());
      return false;
    }
  }

  static void log(String msg) { System.out.println("[FOX-GRADE] " + msg); }
}
