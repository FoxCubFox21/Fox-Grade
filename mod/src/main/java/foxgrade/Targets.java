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

  /** Measured against a real corpus in a running game. */
  private static final Set<String> SUPPORTED = Set.of("26.2");

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
