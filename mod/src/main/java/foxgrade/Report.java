// Per-mod verdict aggregation, printed to both the game log and a plain-text file in the game
// directory so the user can see it after Minecraft launches without hunting through log lines.
//
// Verdicts:
//   READY         — mod's declared minecraft range already covers the target; nothing to do
//   NON_FABRIC    — jar has no fabric.mod.json; not our problem
//   NEEDS_PORTING — mod's declared range does NOT cover target; a real port needs to happen
//   PORTED_META   — Fox-Grade widened the mod's declared range and wrote a rewritten jar
//   ERROR         — reading or transforming the jar threw
package foxgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Report {
  public enum Verdict { READY, NON_FABRIC, NEEDS_PORTING, PORTED_META, PORTED, ERROR }

  public static final class Row {
    public final String modId;
    public final Path jar;
    public final Verdict verdict;
    public final String detail;
    public Row(String modId, Path jar, Verdict verdict, String detail) {
      this.modId = modId; this.jar = jar; this.verdict = verdict; this.detail = detail;
    }
  }

  private final List<Row> rows = new ArrayList<>();
  public void add(Row r) { rows.add(r); }
  public int size() { return rows.size(); }
  public List<Row> rows() { return List.copyOf(rows); }

  public Map<Verdict, Integer> summary() {
    Map<Verdict, Integer> m = new EnumMap<>(Verdict.class);
    for (Verdict v : Verdict.values()) m.put(v, 0);
    for (Row r : rows) m.merge(r.verdict, 1, Integer::sum);
    return m;
  }

  // The one-line summary Fox-Grade prints at the end of preLaunch.
  public String oneLine() {
    var s = summary();
    return String.format("%d ready, %d ported, %d ported (meta), %d need porting, %d non-fabric, %d error",
        s.get(Verdict.READY), s.get(Verdict.PORTED), s.get(Verdict.PORTED_META),
        s.get(Verdict.NEEDS_PORTING), s.get(Verdict.NON_FABRIC), s.get(Verdict.ERROR));
  }

  public void writeTo(Path file) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("# Fox-Grade launch report — ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
    sb.append(oneLine()).append("\n\n");
    for (Verdict v : Verdict.values()) {
      var these = rows.stream().filter((r) -> r.verdict == v).toList();
      if (these.isEmpty()) continue;
      sb.append("## ").append(v).append("  (").append(these.size()).append(")\n");
      for (Row r : these) {
        sb.append("  · ").append(r.modId).append("   ").append(r.jar.getFileName());
        if (r.detail != null && !r.detail.isEmpty()) sb.append("   — ").append(r.detail);
        sb.append('\n');
      }
      sb.append('\n');
    }
    Files.writeString(file, sb.toString());
  }
}
