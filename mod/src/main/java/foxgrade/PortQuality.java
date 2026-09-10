package foxgrade;

import java.util.List;

/** How much of a mod survived its port, as a number and a sentence.
 *
 *  <p>The report already carried everything needed and said it in the tool's own vocabulary: "436 classes remapped,
 *  14 handler(s) stripped, 14 unresolved ref(s)". Someone deciding whether to trust a port cannot tell from that
 *  whether fourteen is fine or fatal — it depends entirely on how big the mod is and on what those fourteen were.
 *  This turns the same numbers into the question actually being asked.
 *
 *  <p>Deliberately blunt about its own limits, and they have been measured rather than guessed. Checked against the
 *  26.2 compatibility results: no mod that actually boots scores below 50, so the score does not cry wolf. But two
 *  mods that do NOT boot score above 90 — REI, which fails on a dependency it cannot find, and Xaero's world map,
 *  which fails in rendering. Both ports are structurally complete; what defeats them is invisible to a pipeline
 *  that only sees the jar. So this measures how much of the mod came through intact, which predicts loading well
 *  and correct behaviour poorly. It is a summary of the report, not a promise. */
public final class PortQuality {
  private PortQuality() { }

  public record Score(int percent, String verdict, String detail) { }

  /** @param classes    how many classes the mod HAS — not how many were rewritten. An early version divided by the
   *                     rewrite count and produced nonsense: FerriteCore, Architectury and NoChatReports all scored
   *                     under 20% and read "heavily degraded" while all three actually boot on 26.2. A mod is big or
   *                     small independently of how much of it Fox-Grade had to touch, and it is the size that says
   *                     whether ten changes are a lot.
   *  @param unresolved references to things the target no longer has
   *  @param stripped   handlers removed because what they hooked is gone
   *  @param mixins     mixins disabled outright
   *  @param audit      structural problems found in the finished jar */
  public static Score of(int classes, int unresolved, int stripped, int mixins, List<String> audit) {
    if (audit != null && !audit.isEmpty()) {
      return new Score(0, "may not load",
          audit.size() + " structural problem(s) found in the finished jar — see the report");
    }
    // Everything is measured against the size of the mod, because ten unresolved references in a mod of forty
    // classes is a different thing from ten in a mod of four hundred.
    int size = Math.max(classes, 1);
    // A disabled mixin costs more than a stripped handler, which costs more than an unresolved reference, because
    // that is the order in which they remove behaviour. The weights are deliberately mild: this is a summary for a
    // person choosing whether to try a port, and a number that calls a working mod broken is worse than no number.
    double lost = (0.5 * unresolved + 1.0 * stripped + 2.0 * mixins) / size;
    int percent = (int) Math.round(Math.max(0, Math.min(1, 1 - lost)) * 100);
    String verdict = percent >= 98 ? "clean"
        : percent >= 90 ? "nearly complete"
        : percent >= 70 ? "mostly working"
        : percent >= 40 ? "partly working"
        : "heavily degraded";
    StringBuilder d = new StringBuilder();
    if (mixins > 0) d.append(mixins).append(" mixin(s) disabled");
    if (stripped > 0) d.append(d.length() > 0 ? ", " : "").append(stripped).append(" handler(s) removed");
    if (unresolved > 0) d.append(d.length() > 0 ? ", " : "").append(unresolved).append(" unresolved reference(s)");
    if (d.length() == 0) d.append("nothing had to be changed or removed");
    return new Score(percent, verdict, d.toString());
  }
}
