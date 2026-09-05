// Lifetime counters for the panel header — "what has Fox-Grade done for you, ever".
//
// One tiny JSON file at <gamedir>/fox-grade-stats.json, updated by preLaunch each time a port
// succeeds. Read by the panel on open. Best-effort on both sides: a corrupt or missing file
// resets to zero rather than breaking a launch, because stats are decoration, not state.
package foxgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FoxGradeStats {
  public long ports, classesRemapped, handlersStripped, refsBridged;

  public static FoxGradeStats load(Path gameDir) {
    FoxGradeStats s = new FoxGradeStats();
    try {
      Path f = gameDir.resolve("fox-grade-stats.json");
      if (!Files.exists(f)) return s;
      JsonObject o = new Gson().fromJson(Files.readString(f), JsonObject.class);
      if (o == null) return s;
      s.ports = o.has("ports") ? o.get("ports").getAsLong() : 0;
      s.classesRemapped = o.has("classesRemapped") ? o.get("classesRemapped").getAsLong() : 0;
      s.handlersStripped = o.has("handlersStripped") ? o.get("handlersStripped").getAsLong() : 0;
      s.refsBridged = o.has("refsBridged") ? o.get("refsBridged").getAsLong() : 0;
    } catch (Exception ignored) { }
    return s;
  }

  public static void recordPort(Path gameDir, TransformPipeline.Outcome o) {
    try {
      FoxGradeStats s = load(gameDir);
      s.ports++;
      s.classesRemapped += o.classesRemapped;
      s.handlersStripped += o.mixinsStripped + o.autoStripped;
      s.refsBridged += o.refmapHits;
      JsonObject j = new JsonObject();
      j.addProperty("ports", s.ports);
      j.addProperty("classesRemapped", s.classesRemapped);
      j.addProperty("handlersStripped", s.handlersStripped);
      j.addProperty("refsBridged", s.refsBridged);
      Files.writeString(gameDir.resolve("fox-grade-stats.json"), new GsonBuilder().setPrettyPrinting().create().toJson(j));
    } catch (Exception e) {
      FoxGradePreLaunch.log("  ! could not update stats: " + e.getMessage());
    }
  }
}
