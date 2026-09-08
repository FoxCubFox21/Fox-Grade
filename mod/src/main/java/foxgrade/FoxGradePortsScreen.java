// The Fox-Grade panel — the full lifecycle manager for ported mods.
//
// One screen runs the whole loop a ported mod lives through:
//   · every active port is a row: mod icon + name + from→to, [Details] [Off/On] [Retire]
//   · Off/On renames the jar to .jar.disabled and back — reversible, takes effect on restart
//   · the inbox section shows jars waiting to be ported, with a one-click port-and-restart
//   · a Modrinth watcher checks (async, best-effort) whether the author shipped an official
//     build for this MC — if so the row says so, because an official build always beats a port
//   · [Test boot] clones the most recent world to FG-Test, arms a one-shot autotest, and
//     restarts into it via quickPlay; the verdict lands back on this screen next session
//   · Details adds live verification (Class.forName against the RUNNING game), a clipboard
//     report, a re-port-from-original action, and a human-readable list of features that were
//     turned off for safety
// Stock widgets only — Fox-Grade depends on no other mod.
package foxgrade;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FoxGradePortsScreen extends Screen {
  private final Screen parent;
  private int page = 0;
  // Survives screen close/reopen within a session: an action was taken that needs a restart.
  static volatile boolean pendingRestart = false;

  // Icon textures registered once per mod id; dims always declared 18×18 so ImageWidget's blit
  // stretches the WHOLE image into the slot instead of cropping the top-left corner.
  private static final Map<String, Identifier> ICONS = new HashMap<>();
  private static final Set<String> ICONS_TRIED = new HashSet<>();
  // Modrinth watcher cache: id → the official build's file info when one exists for this MC.
  record Official(String url, String filename, String sha1, String version) { }
  private static final Map<String, Official> MODRINTH = new HashMap<>();
  private static final Set<String> MODRINTH_ASKED = new HashSet<>();

  public FoxGradePortsScreen(Screen parent) {
    super(Component.literal("Fox-Grade"));
    this.parent = parent;
  }

  public static Component buttonLabel() { return gradient("Fox-Grade", 0xFFAA00, 0xFFFFFF, false); }

  private void centered(int y, Component text) {
    int w = this.font.width(text);
    if (w > this.width - 16) {   // never bleed off both edges: trim with an ellipsis
      String flat = text.getString();
      while (flat.length() > 8 && this.font.width(flat + "…") > this.width - 16) flat = flat.substring(0, flat.length() - 4);
      text = Component.literal("§8" + flat + "…§r");
      w = this.font.width(text);
    }
    addRenderableWidget(new StringWidget((this.width - w) / 2, y, w, 12, text, this.font));
  }

  static Component gradient(String text, int fromRgb, int toRgb, boolean bold) {
    var out = Component.empty();
    int n = Math.max(1, text.length() - 1);
    for (int i = 0; i < text.length(); i++) {
      float t = (float) i / n;
      int r = (int) (((fromRgb >> 16) & 0xFF) * (1 - t) + ((toRgb >> 16) & 0xFF) * t);
      int g = (int) (((fromRgb >> 8) & 0xFF) * (1 - t) + ((toRgb >> 8) & 0xFF) * t);
      int b = (int) ((fromRgb & 0xFF) * (1 - t) + (toRgb & 0xFF) * t);
      int rgb = (r << 16) | (g << 8) | b;
      out.append(Component.literal(String.valueOf(text.charAt(i)))
          .withStyle((st) -> st.withColor(rgb).withBold(bold)));
    }
    return out;
  }

  @Override protected void init() {
    Path gameDir = Loaders.current().gameDir();
    List<Port> ports = collectPorts();
    collectDisabledFromDisk(gameDir, ports);
    for (Port pt : ports) askModrinth(pt.origId);

    int y = 14;
    centered(y, gradient("FOX-GRADE", 0xFFAA00, 0xFFFFFF, true));
    y += 12;
    FoxGradeStats stats = FoxGradeStats.load(gameDir);
    String life = stats.ports == 0 ? "§8v" + FoxGradePreLaunch.VERSION + "§r"
        : "§7lifetime: §f" + stats.ports + "§7 port" + (stats.ports == 1 ? "" : "s") + " · §f"
          + String.format("%,d", stats.classesRemapped) + "§7 classes remapped · §f"
          + String.format("%,d", stats.handlersStripped) + "§7 hooks made safe §8· v" + FoxGradePreLaunch.VERSION + "§r";
    centered(y, Component.literal(life));
    y += 11;
    centered(y, Component.literal("§8────────────────────────────────────────§r"));
    y += 10;

    int perPage = Math.max(2, (this.height - 82 - y) / 34);
    int pages = Math.max(1, (ports.size() + perPage - 1) / perPage);
    if (page >= pages) page = pages - 1;

    if (ports.isEmpty()) {
      y += 12;
      centered(y, Component.literal("§7No ported mods yet§r"));
      y += 14;
      centered(y, Component.literal("§7Drop old jars in §fmods/fox-grade-inbox/§7 — ported on next launch§r"));
    }

    int rowLeft = this.width / 2 - 172;
    int btnX = this.width / 2 + 52;
    List<Port> shown = ports.subList(page * perPage, Math.min(ports.size(), (page + 1) * perPage));
    for (Port pt : shown) {
      Identifier icon = iconFor(pt);
      if (icon != null) {
        var img = ImageWidget.texture(18, 18, icon, 18, 18);
        img.setX(rowLeft); img.setY(y + 1);
        addRenderableWidget(img);
      }
      int textX = rowLeft + (icon != null ? 22 : 0);
      var name = pt.disabled
          ? Component.literal("§8§l" + pt.origId + "§r §8" + pt.fromMc + " → " + pt.targetMc + " (off)§r")
          : Component.literal("§a§l" + pt.origId + "§r §7" + pt.fromMc + " §8→§r §f" + pt.targetMc + "§r");
      addRenderableWidget(new StringWidget(textX, y + 5, this.font.width(name), 12, name, this.font));
      // Official-build badge: right-aligned against the buttons, on the NAME row where there is
      // guaranteed free space (only added when it actually fits after the name).
      if (MODRINTH.get(pt.origId) != null) {
        var badge = Component.literal("§b⬆ official!§r");
        int bx = btnX - 6 - this.font.width(badge);
        if (bx > textX + this.font.width(name) + 8) {
          var bw2 = new StringWidget(bx, y + 5, this.font.width(badge), 12, badge, this.font);
          bw2.setTooltip(Tooltip.create(Component.literal("The author ships a real " + pt.targetMc + " build on Modrinth.\nOpen Details to install it with one click.")));
          addRenderableWidget(bw2);
        }
      }

      var details = Button.builder(Component.literal("Details"), (b) ->
          this.minecraft.setScreenAndShow(new DetailsScreen(this, pt)))
          .bounds(btnX, y, 44, 18).build();
      details.setTooltip(Tooltip.create(Component.literal("Full report: stats, unresolved references,\nfeatures turned off, live verify, re-port")));
      addRenderableWidget(details);

      Button toggle = Button.builder(Component.literal(pt.disabled ? "§aOn§r" : "Off"), (b) -> {
        if (togglePort(pt)) { pendingRestart = true; rebuildWidgets(); }
      }).bounds(btnX + 47, y, 28, 18).build();
      toggle.setTooltip(Tooltip.create(Component.literal(pt.disabled
          ? "Re-enable this port (takes effect on restart)"
          : "Disable without deleting (takes effect on restart)")));
      addRenderableWidget(toggle);

      Button retire = Button.builder(Component.literal("§cRetire§r"), (b) -> {
        if (retirePort(pt)) {
          pendingRestart = true;
          b.setMessage(Component.literal("§8retired§r"));
          b.active = false;
        }
      }).bounds(btnX + 78, y, 44, 18).build();
      retire.setTooltip(Tooltip.create(Component.literal("Move the ported jar to mods-backup/ — gone next launch")));
      if (pt.retired) { retire.setMessage(Component.literal("§8retired§r")); retire.active = false; }
      addRenderableWidget(retire);
      y += 19;

      var status = pt.disabled
          ? Component.literal("§8disabled — jar kept, restart to fully unload§r")
          : pt.unresolved.isEmpty()
            ? Component.literal("§2✔ all references resolved§r §8· " + pt.shortStats + "§r")
            : Component.literal("§6⚠ " + pt.unresolvedCount + " unresolved§r §8· " + pt.shortStats + "§r");
      addRenderableWidget(new StringWidget(textX + 8, y, this.font.width(status), 10, status, this.font));
      y += 15;
    }

    // --- footer info: inbox, last boot test, pending restart -------------------------------
    List<Path> inboxJars = listInbox(gameDir);
    if (!inboxJars.isEmpty()) {
      String names = inboxJars.stream().limit(2).map((p) -> p.getFileName().toString())
          .reduce((a, b) -> a + "§7, §f" + b).orElse("");
      if (inboxJars.size() > 2) names += " §7+" + (inboxJars.size() - 2) + " more";
      centered(this.height - 66, Component.literal("§7Inbox: §f" + names + "§7 — waiting to be ported§r"));
      pendingRestart = true;   // porting happens at launch; a restart is the action
    } else {
      String testLine = lastTestLine(gameDir);
      if (testLine != null) centered(this.height - 66, Component.literal(testLine));
      else centered(this.height - 66, Component.literal("§8F8 opens this panel§r"));   // the drop-in hint is already the empty state's headline
    }
    if (pendingRestart) {
      centered(this.height - 54, Component.literal("§e⟳ Changes pending — restart to apply§r"));
    }

    // --- footer buttons --------------------------------------------------------------------
    if (pages > 1) {
      addRenderableWidget(Button.builder(Component.literal("<"), (b) -> { page = Math.max(0, page - 1); rebuildWidgets(); })
          .bounds(this.width / 2 - 88, this.height - 28, 20, 20).build());
      addRenderableWidget(Button.builder(Component.literal(">"), (b) -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); })
          .bounds(this.width / 2 + 68, this.height - 28, 20, 20).build());
      centered(this.height - 40, Component.literal("§8page " + (page + 1) + "/" + pages + "§r"));
    }

    Button test = confirmingButton("Test boot", "§eSure? Click again§r", (b) -> runBootTest(gameDir, b));
    test.setTooltip(Tooltip.create(Component.literal("Clone your latest world to FG-Test, restart straight\ninto it, and verify everything boots — the verdict\nshows here afterwards. Your real world is untouched.")));
    test.setX(this.width / 2 - 170); test.setY(this.height - 28); test.setWidth(76);
    addRenderableWidget(test);

    addRenderableWidget(Button.builder(Component.literal("Done"), (b) -> onClose())
        .bounds(this.width / 2 - 64, this.height - 28, 128, 20).build());

    Button restart = Button.builder(
        Component.literal(pendingRestart ? "§e⟳ Restart§r" : "Restart"), (b) -> {
          if (FoxGradePreLaunch.relaunchSelf(true, List.of())) this.minecraft.stop();
          else b.setMessage(Component.literal("§cfailed§r"));
        }).bounds(this.width / 2 + 94, this.height - 28, 76, 20).build();
    restart.setTooltip(Tooltip.create(Component.literal("Close and relaunch the game in one click")));
    addRenderableWidget(restart);
  }

  // Two-click confirm for heavyweight actions: first click re-labels, second click fires.
  private Button confirmingButton(String label, String confirmLabel, Button.OnPress action) {
    boolean[] armed = {false};
    return Button.builder(Component.literal(label), (b) -> {
      if (!armed[0]) { armed[0] = true; b.setMessage(Component.literal(confirmLabel)); return; }
      action.onPress(b);
    }).bounds(0, 0, 76, 20).build();
  }

  // ==== actions ====

  private boolean togglePort(Port pt) {
    try {
      if (pt.jar == null) return false;
      if (pt.disabled) {
        String n = pt.jar.getFileName().toString();
        Path target = pt.jar.getParent().resolve(n.substring(0, n.length() - ".disabled".length()));
        Files.move(pt.jar, target);
        FoxGradePreLaunch.log("panel: re-enabled " + pt.origId);
      } else {
        Files.move(pt.jar, pt.jar.getParent().resolve(pt.jar.getFileName() + ".disabled"));
        FoxGradePreLaunch.log("panel: disabled " + pt.origId + " (reversible — restart to apply)");
      }
      return true;
    } catch (Exception e) {
      FoxGradePreLaunch.log("panel: toggle failed for " + pt.origId + ": " + e.getMessage());
      return false;
    }
  }

  static boolean retirePort(Port pt) {
    try {
      if (pt.jar == null) return false;
      Path backups = pt.jar.getParent().getParent().resolve("mods-backup");
      Files.createDirectories(backups);
      String base = pt.jar.getFileName().toString().replace(".disabled", "");
      Path dest = backups.resolve(base);
      int n = 1;
      while (Files.exists(dest)) dest = backups.resolve(base.replace(".jar", "." + (n++) + ".jar"));
      Files.move(pt.jar, dest);
      pt.retired = true;
      FoxGradePreLaunch.log("panel: retired " + pt.origId + " → " + dest.getFileName() + " (takes effect next launch)");
      return true;
    } catch (Exception e) {
      FoxGradePreLaunch.log("panel: retire failed for " + pt.origId + ": " + e.getMessage());
      return false;
    }
  }

  // Clone the most recent world to FG-Test (never touching the original), arm a one-shot
  // autotest, and restart straight into the clone via quickPlay. FG-Test is only ever deleted
  // when it carries the marker file Fox-Grade itself wrote — a user's own world named FG-Test
  // is left alone and the test refuses to run.
  private void runBootTest(Path gameDir, Button b) {
    try {
      Path saves = gameDir.resolve("saves");
      Path newest = null;
      long newestTime = Long.MIN_VALUE;
      if (Files.isDirectory(saves)) {
        try (var st = Files.list(saves)) {
          for (Path w : st.toList()) {
            if (!Files.isDirectory(w) || w.getFileName().toString().equals("FG-Test")) continue;
            Path level = w.resolve("level.dat");
            if (!Files.exists(level)) continue;
            long t = Files.getLastModifiedTime(level).toMillis();
            if (t > newestTime) { newestTime = t; newest = w; }
          }
        }
      }
      if (newest == null) { b.setMessage(Component.literal("§cno world found§r")); b.active = false; return; }
      Path clone = saves.resolve("FG-Test");
      if (Files.exists(clone)) {
        if (!Files.exists(clone.resolve("fox-grade-test-marker.txt"))) {
          b.setMessage(Component.literal("§cFG-Test is yours§r")); b.active = false; return;
        }
        try (var walk = Files.walk(clone)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach((p) -> { try { Files.delete(p); } catch (Exception ignored) { } });
        }
      }
      Path src = newest;
      try (var walk = Files.walk(src)) {
        for (Path p : walk.toList()) {
          if (p.getFileName().toString().equals("session.lock")) continue;
          Path dest = clone.resolve(src.relativize(p));
          if (Files.isDirectory(p)) Files.createDirectories(dest);
          else { Files.createDirectories(dest.getParent()); Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        }
      }
      Files.writeString(clone.resolve("fox-grade-test-marker.txt"), "Disposable clone made by Fox-Grade for boot testing. Safe to delete.\n");
      // arm the one-shot autotest
      Path cfgPath = gameDir.resolve("fox-grade.config.json");
      com.google.gson.JsonObject o = Files.exists(cfgPath)
          ? new com.google.gson.Gson().fromJson(Files.readString(cfgPath), com.google.gson.JsonObject.class)
          : new com.google.gson.JsonObject();
      if (o == null) o = new com.google.gson.JsonObject();
      o.addProperty("autotestTicks", 150);
      o.addProperty("autotestPanel", false);
      o.addProperty("autotestOnce", true);
      Files.writeString(cfgPath, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
      Files.deleteIfExists(gameDir.resolve("fox-grade-test-result.json"));
      FoxGradePreLaunch.log("panel: boot test armed — cloned '" + src.getFileName() + "' → FG-Test, restarting into it");
      if (FoxGradePreLaunch.relaunchSelf(true, List.of("--quickPlaySingleplayer", "FG-Test"))) this.minecraft.stop();
      else b.setMessage(Component.literal("§crelaunch failed§r"));
    } catch (Exception e) {
      FoxGradePreLaunch.log("panel: boot test failed to arm: " + e.getMessage());
      b.setMessage(Component.literal("§cfailed — see log§r"));
    }
  }

  // ==== data collection ====

  static List<Path> listInbox(Path gameDir) {
    List<Path> out = new ArrayList<>();
    for (Path dir : List.of(gameDir.resolve("mods").resolve("fox-grade-inbox"), gameDir.resolve("fox-grade-inbox"))) {
      try (var st = Files.list(dir)) {
        out.addAll(st.filter((f) -> f.getFileName().toString().endsWith(".jar")).sorted().toList());
      } catch (Exception ignored) { }
    }
    return out;
  }

  static String lastTestLine(Path gameDir) {
    try {
      Path f = gameDir.resolve("fox-grade-test-result.json");
      if (!Files.exists(f)) return null;
      var o = new com.google.gson.Gson().fromJson(Files.readString(f), com.google.gson.JsonObject.class);
      if (o == null || !o.has("passed")) return null;
      long mins = (System.currentTimeMillis() - (o.has("when") ? o.get("when").getAsLong() : 0)) / 60000;
      String age = mins < 60 ? mins + "m ago" : mins < 60 * 48 ? (mins / 60) + "h ago" : (mins / 1440) + "d ago";
      return "§2✔ Boot test passed§r §7— stable for " + o.get("ticks").getAsInt() + " ticks with "
          + o.get("mods").getAsInt() + " mods §8(" + age + ")§r";
    } catch (Exception e) { return null; }
  }

  private static Identifier iconFor(Port pt) {
    if (pt.container == null) return ICONS.get(pt.origId);
    if (ICONS_TRIED.contains(pt.origId)) return ICONS.get(pt.origId);
    ICONS_TRIED.add(pt.origId);
    try {
      var iconPath = pt.container.iconPath(32);
      if (iconPath.isEmpty()) return null;
      var file = pt.container.findPath(iconPath.get());
      if (file.isEmpty()) return null;
      try (var in = Files.newInputStream(file.get())) {
        var img = com.mojang.blaze3d.platform.NativeImage.read(in);
        String safe = pt.origId.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        var id = Identifier.fromNamespaceAndPath("foxgrade", "icon/" + safe);
        net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id,
            new net.minecraft.client.renderer.texture.DynamicTexture(() -> "foxgrade icon " + safe, img));
        ICONS.put(pt.origId, id);
        return id;
      }
    } catch (Exception e) { return null; }
  }

  // Best-effort async: does the author ship an official build for this MC on Modrinth?
  // Read-only public API, one query per mod id per session, quietly gives up on any failure.
  static void askModrinth(String id) {
    if (!MODRINTH_ASKED.add(id)) return;
    String mc = Loaders.current().gameVersion();
    Thread t = new Thread(() -> {
      Official found = null;
      try {
        var url = java.net.URI.create("https://api.modrinth.com/v2/project/" + id
            + "/version?game_versions=%5B%22" + mc + "%22%5D&loaders=%5B%22fabric%22%5D").toURL();
        var conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000); conn.setReadTimeout(4000);
        conn.setRequestProperty("User-Agent", "Fox-Grade/" + FoxGradePreLaunch.VERSION);
        if (conn.getResponseCode() == 200) {
          try (var in = conn.getInputStream()) {
            var arr = new com.google.gson.Gson().fromJson(new String(in.readAllBytes()), com.google.gson.JsonArray.class);
            if (arr != null && !arr.isEmpty()) {
              var ver = arr.get(0).getAsJsonObject();
              var files = ver.getAsJsonArray("files");
              com.google.gson.JsonObject file = null;
              for (var f : files) {
                var fo = f.getAsJsonObject();
                if (fo.has("primary") && fo.get("primary").getAsBoolean()) { file = fo; break; }
              }
              if (file == null && !files.isEmpty()) file = files.get(0).getAsJsonObject();
              if (file != null) {
                found = new Official(file.get("url").getAsString(), file.get("filename").getAsString(),
                    file.getAsJsonObject("hashes").get("sha1").getAsString(),
                    ver.has("version_number") ? ver.get("version_number").getAsString() : "?");
              }
            }
          }
        }
      } catch (Exception ignored) { }
      Official v = found;
      var mcClient = net.minecraft.client.Minecraft.getInstance();
      mcClient.execute(() -> {
        MODRINTH.put(id, v);
        if (v == null) return;
        var scr = mcClient.gui.screen();
        if (scr instanceof FoxGradePortsScreen s) s.rebuildWidgets();
        else if (scr instanceof DetailsScreen ds && ds.pt.origId.equals(id)) ds.refresh();
      });
    }, "foxgrade-modrinth-" + id);
    t.setDaemon(true);
    t.start();
  }

  // Autotest hook: pose the Details view of the most interesting port (most to show).
  static Screen detailsForAutotest() {
    List<Port> ports = collectPorts();
    if (ports.isEmpty()) return new FoxGradePortsScreen(null);
    Port best = ports.get(0);
    for (Port pt : ports) {
      if (pt.strippedHandlers.size() + pt.unresolved.size() > best.strippedHandlers.size() + best.unresolved.size()) best = pt;
    }
    return new DetailsScreen(new FoxGradePortsScreen(null), best);
  }

  static final class Port {
    String origId = "?", targetMc = "?", fromMc = "?", source = "?", summary = "", shortStats = "";
    List<String> unresolved = new ArrayList<>();
    List<String> strippedHandlers = new ArrayList<>();
    List<String> disabledMixins = new ArrayList<>();
    int unresolvedCount = 0;
    Path jar;
    LoaderHost.Mod container;
    boolean retired = false;
    boolean disabled = false;
  }

  static List<Port> collectPorts() {
    List<Port> out = new ArrayList<>();
    for (LoaderHost.Mod mod : Loaders.current().mods()) {
      com.google.gson.JsonObject o = mod.custom("foxgrade");
      if (o == null) continue;
      if (o.get("pastPort") == null) continue;
      Port pt = new Port();
      pt.container = mod;
      pt.origId = str(o, "originalId", mod.id());
      pt.targetMc = str(o, "pastPort", "?");
      pt.source = str(o, "source", "?");
      pt.summary = str(o, "summary", "");
      pt.shortStats = pt.summary.replace(" classes remapped", " remapped")
          .replace(" handler(s) stripped", " stripped").replaceAll(", \\d+ unresolved ref\\(s\\)", "");
      String fromMc = str(o, "fromMc", "").trim();
      if (!fromMc.isEmpty()) {
        pt.fromMc = fromMc.replace(">=", "").replace("<=", "– ").replace("<", "before ").trim();
      } else {
        var m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(pt.source);
        pt.fromMc = m.find() ? m.group(1) : "older MC";
      }
      strList(o, "unresolved", pt.unresolved);
      strList(o, "strippedHandlers", pt.strippedHandlers);
      strList(o, "disabledMixins", pt.disabledMixins);
      pt.unresolvedCount = pt.unresolved.size();
      try {
        var paths = mod.jarPaths();
        if (!paths.isEmpty()) pt.jar = paths.get(0);
      } catch (Exception ignored) { }
      // Disabled THIS session: the loaded jar path is gone but its .disabled sibling exists.
      if (pt.jar != null && !Files.exists(pt.jar)) {
        Path off = pt.jar.getParent().resolve(pt.jar.getFileName() + ".disabled");
        if (Files.exists(off)) { pt.jar = off; pt.disabled = true; }
      }
      out.add(pt);
    }
    return out;
  }

  // Ports disabled in an EARLIER session live only on disk as mods/*.jar.disabled — the loader
  // has never seen them. Read the marker straight from the zip so they still show up with [On].
  static void collectDisabledFromDisk(Path gameDir, List<Port> ports) {
    Set<String> seen = new HashSet<>();
    for (Port p : ports) seen.add(p.origId);
    try (var st = Files.list(gameDir.resolve("mods"))) {
      for (Path f : st.toList()) {
        if (!f.getFileName().toString().endsWith(".jar.disabled")) continue;
        try (var zf = new java.util.zip.ZipFile(f.toFile())) {
          String metaText = QuiltMeta.fabricMeta(zf);
          if (metaText == null) continue;
          com.google.gson.JsonObject meta;
          {
            meta = new com.google.gson.Gson().fromJson(metaText, com.google.gson.JsonObject.class);
          }
          if (meta == null || !meta.has("custom") || !meta.getAsJsonObject("custom").has("foxgrade")) continue;
          var fg = meta.getAsJsonObject("custom").getAsJsonObject("foxgrade");
          Port pt = new Port();
          pt.disabled = true;
          pt.jar = f;
          pt.origId = fg.has("originalId") ? fg.get("originalId").getAsString()
              : meta.has("id") ? meta.get("id").getAsString() : f.getFileName().toString();
          if (seen.contains(pt.origId)) continue;
          pt.targetMc = fg.has("pastPort") ? fg.get("pastPort").getAsString() : "?";
          pt.source = fg.has("source") ? fg.get("source").getAsString() : "?";
          pt.summary = fg.has("summary") ? fg.get("summary").getAsString() : "";
          pt.shortStats = pt.summary;
          String fromMc = fg.has("fromMc") ? fg.get("fromMc").getAsString().trim() : "";
          if (!fromMc.isEmpty()) {
            pt.fromMc = fromMc.replace(">=", "").trim();
          } else {
            var m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(pt.source);
            pt.fromMc = m.find() ? m.group(1) : "older MC";
          }
          ports.add(pt);
        } catch (Exception ignored) { }
      }
    } catch (Exception ignored) { }
  }

  static void strList(com.google.gson.JsonObject o, String key, List<String> into) {
    com.google.gson.JsonElement v = o.get(key);
    if (v != null && v.isJsonArray()) {
      for (com.google.gson.JsonElement e : v.getAsJsonArray()) into.add(e.getAsString());
    }
  }

  static String str(com.google.gson.JsonObject o, String key, String def) {
    com.google.gson.JsonElement v = o.get(key);
    return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : def;
  }

  @Override public void onClose() {
    this.minecraft.setScreenAndShow(parent);
    FoxGradeClient.panelOpen = false;
  }

  // Turn "SprintParticlesMixin#onSprintStep" into "Sprint Particles — onSprintStep". The mixin
  // class name IS the mod author's own description of the feature; just de-camel it.
  static String describeFeature(String handler) {
    int hash = handler.indexOf('#');
    String cls = hash < 0 ? handler : handler.substring(0, hash);
    String method = hash < 0 ? "" : handler.substring(hash + 1);
    cls = cls.replaceAll("(Mixin|Accessor|Invoker)$", "").replaceAll("^(Mixin|Base)", "");
    String words = cls.replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
    if (words.isEmpty()) words = cls;
    return words + (method.isEmpty() ? "" : " §8— " + method);
  }

  // Full breakdown + power tools for one port.
  static final class DetailsScreen extends Screen {
    private final Screen parent;
    final Port pt;
    private String verifyResult = null;

    void refresh() { rebuildWidgets(); }   // protected in Screen; the watcher calls cross-class

    DetailsScreen(Screen parent, Port pt) {
      super(Component.literal("Fox-Grade — " + pt.origId));
      this.parent = parent; this.pt = pt;
      askModrinth(pt.origId);   // opened directly (hotkey/autotest): the panel may not have asked yet
    }

    private void centered(int y, Component text) {
      int w = this.font.width(text);
      if (w > this.width - 16) {
        // Too wide to center (long class lists from live verify): trim with an ellipsis rather
        // than bleeding off both screen edges.
        String flat = text.getString();
        while (flat.length() > 8 && this.font.width(flat + "…") > this.width - 16)
          flat = flat.substring(0, flat.length() - 4);
        text = Component.literal("§6" + flat + "…§r");
        w = this.font.width(text);
      }
      addRenderableWidget(new StringWidget((this.width - w) / 2, y, w, 12, text, this.font));
    }

    @Override protected void init() {
      int y = 16;
      centered(y, Component.literal("§a§l" + pt.origId + "§r §7" + pt.fromMc + " §8→§r §f" + pt.targetMc + "§r"));
      y += 13;
      centered(y, Component.literal("§8────────────────────────────────────────§r"));
      y += 11;
      centered(y, Component.literal("§7source: §f" + pt.source + "§r"));
      y += 11;
      centered(y, Component.literal("§7" + pt.summary + "§r"));
      y += 14;
      if (verifyResult != null) {
        centered(y, Component.literal(verifyResult));
        y += 13;
      }
      if (pt.unresolved.isEmpty()) {
        centered(y, Component.literal("§2✔ every reference this mod makes exists in " + pt.targetMc + "§r"));
        y += 13;
      } else {
        centered(y, Component.literal("§6⚠ unresolved references §8(crash if their code path runs)§r"));
        y += 12;
        for (String u : pt.unresolved) {
          if (y > this.height - 90) { centered(y, Component.literal("§8…§r")); y += 11; break; }
          centered(y, Component.literal("§6" + u + "§r"));
          y += 11;
        }
        y += 3;
      }
      int featureCount = pt.strippedHandlers.size() + pt.disabledMixins.size();
      if (featureCount > 0) {
        centered(y, Component.literal("§7Features turned off so the rest could run safely:§r"));
        y += 12;
        for (String h : pt.strippedHandlers) {
          if (y > this.height - 70) { centered(y, Component.literal("§8… +" + featureCount + " total§r")); y += 11; break; }
          centered(y, Component.literal("§8• §f" + describeFeature(h) + "§r"));
          y += 11;
        }
        for (String m : pt.disabledMixins) {
          if (y > this.height - 70) break;
          centered(y, Component.literal("§8• §f" + describeFeature(m) + " §8(whole feature)§r"));
          y += 11;
        }
      } else {
        centered(y, Component.literal("§2✔ no features had to be turned off§r"));
      }

      Official off = MODRINTH.get(pt.origId);
      if (off != null) {
        // The version STRING is the author's own label (often "2.0.7+26.1" even for a build that
        // supports 26.2), so showing it on the button reads like the wrong version is on offer.
        // Modrinth already filtered by the running game version — say so plainly, details in the tooltip.
        var getLabel = Component.literal("§b⬇ Install the author's official build §7— retires this port§r");
        Button get = Button.builder(getLabel, (b) -> {
          b.active = false;
          b.setMessage(Component.literal("§7downloading…§r"));
          installOfficial(pt, off, b);
        }).bounds(0, 0, 20, 20).build();
        // Size the button to its own text: a fixed width clipped the label on some phrasings.
        int getW = Math.min(this.width - 20, this.font.width(getLabel) + 24);
        get.setWidth(getW);
        get.setX((this.width - getW) / 2);
        get.setY(this.height - 52);
        get.setTooltip(Tooltip.create(Component.literal(off.version() + " — a real " + pt.targetMc + " build from the author.\nDownloads " + off.filename() + " from Modrinth, verifies its\nchecksum, and retires Fox-Grade's port.\nThe author's build always beats a port.")));
        addRenderableWidget(get);
      }
      int bw = 72, gap = 4;
      int x0 = this.width / 2 - (bw * 4 + gap * 3) / 2;
      Button verify = Button.builder(Component.literal("Verify live"), (b) -> runVerify(b))
          .bounds(x0, this.height - 28, bw, 20).build();
      verify.setTooltip(Tooltip.create(Component.literal("Check every Minecraft class this mod references\nagainst the GAME RUNNING RIGHT NOW")));
      addRenderableWidget(verify);
      Button copy = Button.builder(Component.literal("Copy report"), (b) -> {
        this.minecraft.keyboardHandler.setClipboard(buildReport());
        b.setMessage(Component.literal("§2copied!§r"));
      }).bounds(x0 + bw + gap, this.height - 28, bw, 20).build();
      copy.setTooltip(Tooltip.create(Component.literal("Full port report to the clipboard — paste it\nanywhere when asking for help")));
      addRenderableWidget(copy);
      Button reportBtn = Button.builder(Component.literal("Re-port"), (b) -> runReport(b))
          .bounds(x0 + (bw + gap) * 2, this.height - 28, bw, 20).build();
      reportBtn.setTooltip(Tooltip.create(Component.literal("Port the ORIGINAL jar again with the current engine\n— newer Fox-Grade tables often resolve more")));
      addRenderableWidget(reportBtn);
      addRenderableWidget(Button.builder(Component.literal("Back"), (b) ->
          this.minecraft.setScreenAndShow(parent))
          .bounds(x0 + (bw + gap) * 3, this.height - 28, bw, 20).build());
    }

    // Ground truth beats any table: ask the RUNNING game for every net/minecraft class the
    // ported jar references. Async — the jar walk takes a moment on big mods.
    private void runVerify(Button b) {
      if (pt.jar == null || !Files.exists(pt.jar)) { b.setMessage(Component.literal("§8jar missing§r")); b.active = false; return; }
      b.setMessage(Component.literal("§7checking…§r"));
      b.active = false;
      Thread t = new Thread(() -> {
        Set<String> missing = new java.util.TreeSet<>();
        int checked = 0;
        try (var zf = new java.util.zip.ZipFile(pt.jar.toFile())) {
          Set<String> refs = new HashSet<>();
          var entries = zf.entries();
          while (entries.hasMoreElements()) {
            var e = entries.nextElement();
            if (!e.getName().endsWith(".class")) continue;
            try (var in = zf.getInputStream(e)) {
              var reader = new org.objectweb.asm.ClassReader(in.readAllBytes());
              var collector = new org.objectweb.asm.commons.Remapper() {
                @Override public String map(String internalName) {
                  if (internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/blaze3d/"))
                    refs.add(internalName);
                  return internalName;
                }
              };
              reader.accept(new org.objectweb.asm.commons.ClassRemapper(
                  new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) { }, collector), 0);
            } catch (Exception ignored) { }
          }
          for (String ref : refs) {
            checked++;
            try { Class.forName(ref.replace('/', '.'), false, FoxGradePortsScreen.class.getClassLoader()); }
            catch (Throwable miss) { missing.add(ref.substring(ref.lastIndexOf('/') + 1)); }
          }
        } catch (Exception e) {
          FoxGradePreLaunch.log("panel: live verify failed: " + e.getMessage());
        }
        int total = checked;
        this.minecraft.execute(() -> {
          verifyResult = missing.isEmpty()
              ? "§2✔ live-verified: all " + total + " Minecraft classes it uses exist in this game§r"
              : "§c✘ " + missing.size() + "/" + total + " missing live: §6"
                + missing.stream().limit(3).reduce((a, c) -> a + ", " + c).orElse("") + (missing.size() > 3 ? " +" + (missing.size() - 3) + " more" : "") + "§r";
          FoxGradePreLaunch.log("panel: live verify of " + pt.origId + ": " + total + " checked, " + missing.size() + " missing"
              + (missing.isEmpty() ? "" : " — " + missing));
          if (this.minecraft.gui.screen() == this) rebuildWidgets();
        });
      }, "foxgrade-verify");
      t.setDaemon(true);
      t.start();
    }

    // Find the pre-port original (inbox/processed/ or mods-backup/), queue it for a fresh port
    // with the CURRENT engine, and retire the existing port so the two never collide.
    private void runReport(Button b) {
      try {
        Path gameDir = Loaders.current().gameDir();
        Path found = null;
        for (Path dir : List.of(gameDir.resolve("mods").resolve("fox-grade-inbox").resolve("processed"),
                                gameDir.resolve("fox-grade-inbox").resolve("processed"), gameDir.resolve("mods-backup"))) {
          if (!Files.isDirectory(dir)) continue;
          try (var st = Files.list(dir)) {
            for (Path f : st.toList()) {
              if (!f.getFileName().toString().endsWith(".jar")) continue;
              try (var zf = new java.util.zip.ZipFile(f.toFile())) {
                String metaText = QuiltMeta.fabricMeta(zf);
                if (metaText == null) continue;
                com.google.gson.JsonObject meta;
                {
                  meta = new com.google.gson.Gson().fromJson(metaText, com.google.gson.JsonObject.class);
                }
                if (meta != null && meta.has("id") && meta.get("id").getAsString().equals(pt.origId)) { found = f; break; }
              } catch (Exception ignored) { }
            }
          }
          if (found != null) break;
        }
        if (found == null) { b.setMessage(Component.literal("§cno original found§r")); b.active = false; return; }
        Path inbox = gameDir.resolve("mods").resolve("fox-grade-inbox");
        Files.createDirectories(inbox);
        Files.copy(found, inbox.resolve(found.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (pt.jar != null && Files.exists(pt.jar)) {
          Path backups = gameDir.resolve("mods-backup");
          Files.createDirectories(backups);
          Path dest = backups.resolve("replaced-" + pt.jar.getFileName());
          Files.move(pt.jar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        pendingRestart = true;
        b.setMessage(Component.literal("§2queued — restart§r"));
        b.active = false;
        FoxGradePreLaunch.log("panel: re-port queued for " + pt.origId + " from " + found.getFileName());
      } catch (Exception e) {
        FoxGradePreLaunch.log("panel: re-port failed: " + e.getMessage());
        b.setMessage(Component.literal("§cfailed — see log§r"));
      }
    }

    // The full close-the-loop action: official build in, Fox-Grade port out. Nothing is
    // installed unless the checksum matches Modrinth's manifest exactly.
    private void installOfficial(Port pt, Official off, Button b) {
      Thread t = new Thread(() -> {
        String err = null;
        try {
          Path modsDir = Loaders.current().gameDir().resolve("mods");
          Path tmp = modsDir.resolve(off.filename() + ".fgdownload");
          var conn = (java.net.HttpURLConnection) java.net.URI.create(off.url()).toURL().openConnection();
          conn.setConnectTimeout(8000); conn.setReadTimeout(30000);
          conn.setRequestProperty("User-Agent", "Fox-Grade/" + FoxGradePreLaunch.VERSION);
          byte[] bytes;
          try (var in = conn.getInputStream()) { bytes = in.readAllBytes(); }
          var md = java.security.MessageDigest.getInstance("SHA-1");
          var hex = new StringBuilder();
          for (byte x : md.digest(bytes)) hex.append(String.format("%02x", x));
          if (!hex.toString().equalsIgnoreCase(off.sha1())) {
            err = "checksum mismatch — not installed";
          } else {
            Files.write(tmp, bytes);
            Files.move(tmp, modsDir.resolve(off.filename()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            retirePort(pt);
            pendingRestart = true;
            FoxGradePreLaunch.log("panel: installed official " + off.filename() + " (sha1 ok), retired the port");
          }
        } catch (Exception e) {
          err = e.getMessage();
          FoxGradePreLaunch.log("panel: official install failed: " + e);
        }
        String fail = err;
        this.minecraft.execute(() -> {
          b.setMessage(fail == null ? Component.literal("§2✔ installed — restart§r")
                                    : Component.literal("§c" + fail + "§r"));
          if (fail != null) b.active = true;
        });
      }, "foxgrade-install");
      t.setDaemon(true);
      t.start();
    }

    private String buildReport() {
      StringBuilder sb = new StringBuilder();
      sb.append("Fox-Grade port report — ").append(pt.origId).append('\n');
      sb.append("ported from MC ").append(pt.fromMc).append(" to ").append(pt.targetMc).append('\n');
      sb.append("source jar: ").append(pt.source).append('\n');
      sb.append(pt.summary).append('\n');
      if (verifyResult != null) sb.append("live verify: ").append(verifyResult.replaceAll("§.", "")).append('\n');
      if (!pt.unresolved.isEmpty()) {
        sb.append("unresolved references:\n");
        for (String u : pt.unresolved) sb.append("  - ").append(u).append('\n');
      }
      if (!pt.strippedHandlers.isEmpty() || !pt.disabledMixins.isEmpty()) {
        sb.append("features turned off for safety:\n");
        for (String h : pt.strippedHandlers) sb.append("  - ").append(h).append('\n');
        for (String m : pt.disabledMixins) sb.append("  - ").append(m).append(" (whole config)\n");
      }
      sb.append("generated by Fox-Grade v").append(FoxGradePreLaunch.VERSION).append('\n');
      return sb.toString();
    }

    @Override public void onClose() { this.minecraft.setScreenAndShow(parent); }
  }
}
