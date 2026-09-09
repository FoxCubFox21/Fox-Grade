package foxgrade;

import java.util.Set;

/** Which Minecraft versions Fox-Grade will port for.
 *
 *  <p>This list is deliberately separate from the question of whether the tables exist. Building a rules block, a
 *  class inventory, an intermediary bridge and a shim set for a version is most of the work, and it is tempting to
 *  treat their existence as the answer — the code would happily run the moment the resources are there. It should
 *  not. Tables are derived and verified against a target's own inventory, which catches names that do not exist but
 *  says nothing about whether a real mod, ported with them, starts a real game.
 *
 *  <p>So a version joins this list when a corpus has been run against it and the result is known, and not before.
 *  Everything else Fox-Grade promises — that a port which cannot be proved safe is disabled and named, that a port
 *  is always reversible — rests on the numbers being measured rather than assumed, and this is where that starts.
 *
 *  <p>{@code -Dfoxgrade.target=<version>} adds one for a single run. That is how a version gets measured in the
 *  first place; it is not a setting to recommend to anyone. */
public final class Targets {
  private Targets() { }

  /** Measured against a real corpus in a running game.
   *
   *  <p>26.2: 86 of 130 mods boot. 26.1.2: 7 of 13 on the head-to-head corpus. 26.1: 6 of 13 on that same corpus,
   *  agreeing with 26.1.2 on twelve of the thirteen — the one difference is FerriteCore, which replaces vanilla's
   *  blockstate cache and hits a real shape change between those two releases, not a fault in the tables. Every
   *  number is in docs/compat.md with the reason for each failure, which is the point of requiring them: a version
   *  is supported when someone can see how well it does, not when its tables exist. */
  private static final Set<String> SUPPORTED = Set.of("26.2", "26.1.2", "26.1");

  /** Versions whose API is the same as another's, and which therefore share its tables.
   *
   *  <p>26.1, 26.1.1 and 26.1.2 declare identical class sets and differ by a single added method between them. The
   *  rename tables and the intermediary bridge describe that API, so shipping three near-identical copies would add
   *  four megabytes to every download to say the same thing three times.
   *
   *  <p>What is <em>not</em> shared is the class inventory. That file answers "does this member exist here", and it
   *  is the one place where a single added method matters: told that 26.1 has Checkbox.overflowsRowLimit because
   *  26.1.2 does, Fox-Grade would ship a port that calls it and fails at runtime, instead of naming it unresolved.
   *  The inventory stays per version and exact. */
  private static final java.util.Map<String, String> FAMILY = java.util.Map.of(
      "26.1", "26.1.2",
      "26.1.1", "26.1.2");

  /** The version whose tables describe this one's API — itself, unless it belongs to a family. */
  public static String tables(String mc) {
    return mc == null ? "" : FAMILY.getOrDefault(mc, mc);
  }

  public static boolean supported(String mc) {
    if (mc == null || mc.isEmpty() || mc.equals("?")) return false;
    if (SUPPORTED.contains(mc)) return true;
    String opened = System.getProperty("foxgrade.target", "");
    return !opened.isEmpty() && opened.equals(mc);
  }

  /** What to say when asked to run somewhere Fox-Grade has not been measured. */
  public static String refusal(String mc) {
    return "Fox-Grade has not been measured on Minecraft " + mc + ", so it will not port here. "
        + "Supported: " + String.join(", ", SUPPORTED) + ".";
  }
}
