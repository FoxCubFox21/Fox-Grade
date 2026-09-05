// Client entrypoint: the in-game autotest.
//
// When fox-grade.config.json carries {"autotestTicks": N > 0}, Fox-Grade counts client ticks
// once a world is loaded and, at tick N, calls Minecraft's own F2 screenshot handler. The PNG
// lands in <gamedir>/screenshots/ regardless of whether the OS screen is locked — the game's
// framebuffer is grabbed directly, which is what makes remote verification loops possible on a
// machine nobody is sitting at. A second marker line goes to the log so the harness can await it.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FoxGradeClient implements ClientModInitializer {
  private int autotestTicks = 0;
  private boolean autotestPanel = false;
  private boolean autotestOnce = false;   // panel-armed boot test: fire once, record, disarm
  private String autotestPose = "panel";
  private net.minecraft.client.gui.screens.Screen posedScreen = null;
  private int counted = 0;
  private boolean done = false;

  @Override public void onInitializeClient() {
    try {
      Path cfg = FabricLoader.getInstance().getGameDir().resolve("fox-grade.config.json");
      if (Files.exists(cfg)) {
        JsonObject o = new Gson().fromJson(Files.readString(cfg), JsonObject.class);
        if (o != null && o.has("autotestTicks")) autotestTicks = o.get("autotestTicks").getAsInt();
        if (o != null && o.has("autotestPanel")) autotestPanel = o.get("autotestPanel").getAsBoolean();
        if (o != null && o.has("autotestPose")) autotestPose = o.get("autotestPose").getAsString();
        if (o != null && o.has("autotestOnce")) autotestOnce = o.get("autotestOnce").getAsBoolean();
      }
    } catch (Exception ignored) { }
    // The "Fox-Grade" button on the title and pause screens — the discoverable path to the
    // panel (F8 stays as the shortcut). Injected through the loader's screen-init event; still
    // no third-party mod involved.
    // Run in a LATE phase so any other mod's menu handler (Mod Menu moves the squares to fit
    // its icon) finishes first — Fox-Grade measures the FINAL layout and gets the last word.
    var LATE = net.minecraft.resources.Identifier.fromNamespaceAndPath("foxgrade", "late");
    net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.addPhaseOrdering(
        net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
    net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(LATE, (client, screen, sw, sh) -> {
      boolean title = screen instanceof net.minecraft.client.gui.screens.TitleScreen;
      boolean pause = screen instanceof net.minecraft.client.gui.screens.PauseScreen;
      if (!title && !pause) return;
      var widgets = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
      var press = (net.minecraft.client.gui.components.Button.OnPress)
          (b) -> { panelOpen = true; client.setScreenAndShow(new FoxGradePortsScreen(screen)); };
      if (pause) {
        // Slot into the menu grid. Geometry comes from the REAL buttons, not assumptions:
        //   · the row above the squares gives the two column rectangles (Advancements/Statistics)
        //   · squares right-align flush with the right column's right edge
        //   · Fox-Grade takes the left column; if another mod (Mod Menu) already put a button on
        //     the squares row, the left column is split half-and-half so both fit.
        java.util.List<net.minecraft.client.gui.components.AbstractWidget> squares = new java.util.ArrayList<>();
        for (var w : widgets) if (w.getWidth() == 20 && w.getHeight() == 20) squares.add(w);
        if (!squares.isEmpty()) {
          squares.sort(java.util.Comparator.comparingInt(net.minecraft.client.gui.components.AbstractWidget::getX));
          int rowY = squares.get(0).getY();
          net.minecraft.client.gui.components.AbstractWidget colLeft = null, colRight = null;
          for (var w : widgets) {
            if (w.getY() >= rowY || w.getHeight() != 20 || w.getWidth() <= 20) continue;
            if (colLeft == null || w.getY() > colLeft.getY()
                || (w.getY() == colLeft.getY() && w.getX() < colLeft.getX())) {
              // track the row closest above; fill left/right by x below
            }
          }
          // simpler: gather the closest row above rowY containing wide buttons
          int bestY = Integer.MIN_VALUE;
          for (var w : widgets) if (w.getHeight() == 20 && w.getWidth() > 20 && w.getY() < rowY && w.getY() > bestY) bestY = w.getY();
          for (var w : widgets) {
            if (w.getHeight() != 20 || w.getWidth() <= 20 || w.getY() != bestY) continue;
            if (colLeft == null || w.getX() < colLeft.getX()) { colRight = colLeft; colLeft = w; }
            else if (colRight == null || w.getX() < colRight.getX()) colRight = w;
          }
          if (colLeft != null && colRight != null) {
            // One rule: the square-button group is CENTERED on the right column's own center
            // line — visually "under Statistics" exactly like vanilla centers its squares on
            // the menu. If Mod Menu's extra icon makes the group wider than the column it
            // spills evenly on both sides, staying centered. Fox-Grade takes the space left.
            int leftEdge = colLeft.getX();
            int gap = squares.size() > 4 ? 2 : 4;
            int total = squares.size() * 20 + (squares.size() - 1) * gap;
            int colRightCenter = colRight.getX() + colRight.getWidth() / 2;
            int x = colRightCenter - total / 2;
            int groupStart = x;
            for (var sq : squares) { sq.setX(x); x += 20 + gap; }
            int foxRightLimit = Math.min(colLeft.getX() + colLeft.getWidth(), groupStart - 6);
            // is something already sitting on the squares row in the left column? (Mod Menu)
            net.minecraft.client.gui.components.AbstractWidget occupant = null;
            for (var w : widgets) {
              if (w.getY() == rowY && w.getHeight() == 20 && w.getWidth() > 20
                  && w.getX() < sw / 2 && !squares.contains(w)) { occupant = w; break; }
            }
            int foxWidth = foxRightLimit - colLeft.getX();
            if (occupant != null) {
              int half = (foxWidth - 4) / 2;
              occupant.setX(colLeft.getX());
              occupant.setWidth(half);
              widgets.add(net.minecraft.client.gui.components.Button.builder(FoxGradePortsScreen.buttonLabel(), press)
                  .bounds(colLeft.getX() + half + 4, rowY, foxWidth - half - 4, 20).build());
            } else {
              widgets.add(net.minecraft.client.gui.components.Button.builder(FoxGradePortsScreen.buttonLabel(), press)
                  .bounds(colLeft.getX(), rowY, foxWidth, 20).build());
            }
            return;
          }
        }
        // Layout not recognised (another mod rearranged the menu) — fall back to the corner.
      }
      widgets.add(net.minecraft.client.gui.components.Button.builder(FoxGradePortsScreen.buttonLabel(), press)
          .bounds(4, 4, 82, 20).build());
    });
    if (autotestTicks > 0) FoxGradePreLaunch.log("autotest armed: screenshot at tick " + autotestTicks + " in-world");
    ClientTickEvents.END_CLIENT_TICK.register((mc) -> {
      // F8 opens the Fox-Grade panel — raw GLFW poll through vanilla's input helper, so the
      // hotkey needs no keybinding registry and works on the title screen and in-world alike.
      boolean f8 = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), 297);
      if (f8 && !f8WasDown && !panelOpen) {
        panelOpen = true;
        mc.setScreenAndShow(new FoxGradePortsScreen(null));
      }
      f8WasDown = f8;

      if (autotestTicks <= 0 || done || mc.level == null || mc.player == null) return;
      counted++;
      // Autotest can pose the panel for the screenshot — how a remote verification run "sees" UI.
      if (autotestPanel && counted == Math.max(1, autotestTicks - 30)) {
        panelOpen = true;
        posedScreen = switch (autotestPose) {
          case "pause" -> new net.minecraft.client.gui.screens.PauseScreen(true);
          case "details" -> FoxGradePortsScreen.detailsForAutotest();
          default -> new FoxGradePortsScreen(null);
        };
        mc.setScreenAndShow(posedScreen);
      }
      // Two ticks before the shot: clear hover AND keyboard focus so no widget renders
      // highlighted in the capture (programmatic screen-open focuses the first widget).
      if (counted == autotestTicks - 2) {
        // The OS ignores cursor warps for an unfocused window (locked screen), so lie to the
        // GAME instead: MouseHandler's own position fields drive GUI hover. Reflection because
        // the access widener opens them only at runtime — javac still sees private.
        try {
          var mhField = net.minecraft.client.Minecraft.class.getDeclaredField("mouseHandler");
          mhField.setAccessible(true);
          Object mh = mhField.get(mc);
          var fx = mh.getClass().getDeclaredField("xpos");
          var fy = mh.getClass().getDeclaredField("ypos");
          fx.setAccessible(true); fy.setAccessible(true);
          fx.setDouble(mh, 2); fy.setDouble(mh, 2);
        } catch (Throwable ignored) { }
        if (posedScreen != null) posedScreen.clearFocus();
      }
      if (counted == autotestTicks) {
        done = true;
        try {
          Screenshot.grab(mc, false);
          FoxGradePreLaunch.log("autotest screenshot taken at tick " + counted);
        } catch (Throwable t) {
          FoxGradePreLaunch.log("autotest screenshot FAILED: " + t);
        }
        // Panel-armed one-shot boot test: reaching this tick in-world IS the pass signal.
        // Record the verdict for the panel, disarm the config so normal launches stay normal.
        if (autotestOnce) {
          Path gameDir = FabricLoader.getInstance().getGameDir();
          try {
            JsonObject result = new JsonObject();
            result.addProperty("passed", true);
            result.addProperty("ticks", counted);
            result.addProperty("world", mc.level.dimension().identifier().toString());
            result.addProperty("mods", FabricLoader.getInstance().getAllMods().size());
            result.addProperty("when", System.currentTimeMillis());
            Files.writeString(gameDir.resolve("fox-grade-test-result.json"), new Gson().toJson(result));
            FoxGradePreLaunch.log("boot test PASSED — world reached and stable for " + counted + " ticks");
          } catch (Exception e) {
            FoxGradePreLaunch.log("boot test: could not write result: " + e.getMessage());
          }
          try {
            Path cfgPath = gameDir.resolve("fox-grade.config.json");
            JsonObject o = Files.exists(cfgPath) ? new Gson().fromJson(Files.readString(cfgPath), JsonObject.class) : new JsonObject();
            if (o == null) o = new JsonObject();
            o.remove("autotestTicks"); o.remove("autotestOnce"); o.remove("autotestPanel"); o.remove("autotestPose");
            Files.writeString(cfgPath, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
          } catch (Exception e) {
            FoxGradePreLaunch.log("boot test: could not disarm config: " + e.getMessage());
          }
        }
      }
    });
  }

  static volatile boolean panelOpen = false;
  private boolean f8WasDown = false;
}
