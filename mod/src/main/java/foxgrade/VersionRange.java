// Just enough SemVer-range parsing to decide whether a target MC version satisfies a mod's
// declared range. Fabric's real parser lives in fabric-loader and would be the correct dependency
// long-term; this hand-rolled subset covers the shapes that actually appear in the wild:
//
//   "26.2"           exact
//   ">=26.1"         open upper
//   ">=26.1 <26.3"   AND of clauses
//   "26.1.x"         wildcard patch
//   "*"              matches anything
//   "26.1 || 26.2"   OR of clauses (Fabric syntax)
//
// A wrong parse errs on the side of "fine" (returns true) — the point of Fox-Grade is to make
// mods work when they safely can, not to over-refuse.
package foxgrade;

public final class VersionRange {

  // Returns true if `mc` satisfies the range string, or if the range is null/empty/"*".
  public static boolean satisfies(String range, String mc) {
    if (range == null || range.isEmpty() || range.equals("*")) return true;
    for (String orClause : range.split("\\|\\|")) {
      if (matchesAll(orClause.trim(), mc)) return true;
    }
    return false;
  }

  private static boolean matchesAll(String clause, String mc) {
    if (clause.isEmpty()) return true;
    for (String c : clause.split("\\s+")) {
      if (c.isEmpty()) continue;
      if (!matchesOne(c, mc)) return false;
    }
    return true;
  }

  private static boolean matchesOne(String c, String mc) {
    // wildcard suffix ("26.1.x") — treat as ">=26.1 <26.2"
    if (c.endsWith(".x") || c.endsWith(".*")) {
      String base = c.substring(0, c.length() - 2);
      return startsWith(mc, base);
    }
    // range operators
    if (c.startsWith(">=")) return compare(mc, c.substring(2)) >= 0;
    if (c.startsWith("<=")) return compare(mc, c.substring(2)) <= 0;
    if (c.startsWith(">"))  return compare(mc, c.substring(1)) > 0;
    if (c.startsWith("<"))  return compare(mc, c.substring(1)) < 0;
    if (c.startsWith("~")) {
      // ~26.1.2 means >=26.1.2 <26.2.0. The upper bound is computed from the PRIMARY (release)
      // portion of the base, so ~26.2-beta.4 upper-bounds at 26.3.0, not at the pre-release.
      String base = c.substring(1);
      if (compare(mc, base) < 0) return false;
      String[] p = splitPrimary(base)[0].split("\\.");
      if (p.length < 2) return true;
      String upperBase = p[0] + "." + (parseIntSafe(p[1]) + 1) + ".0";
      return compare(mc, upperBase) < 0;
    }
    if (c.startsWith("^")) {
      // ^26.1.2 means >=26.1.2 <27.0.0. Same pre-release rule as tilde.
      String base = c.substring(1);
      if (compare(mc, base) < 0) return false;
      String[] p = splitPrimary(base)[0].split("\\.");
      String upperBase = (parseIntSafe(p[0]) + 1) + ".0.0";
      return compare(mc, upperBase) < 0;
    }
    if (c.equals(mc)) return true;
    // Some mods declare a range like "26.1.x-26.2.x" or something malformed; if we don't
    // recognise the shape, permit it — better a false positive than a spurious block.
    return true;
  }

  private static boolean startsWith(String mc, String base) {
    return mc.equals(base) || mc.startsWith(base + ".");
  }

  // SemVer-ish compare: split on '.', numeric compare per segment, pad missing with 0. Non-numeric
  // suffixes (pre-releases like "26.3-pre-1") sort BEFORE the same-prefix release.
  private static int compare(String a, String b) {
    // strip pre-release suffix for the primary compare; if primaries equal, pre-release loses.
    String[] as = splitPrimary(a), bs = splitPrimary(b);
    String[] ap = as[0].split("\\."), bp = bs[0].split("\\.");
    int n = Math.max(ap.length, bp.length);
    for (int i = 0; i < n; i++) {
      int ai = i < ap.length ? parseIntSafe(ap[i]) : 0;
      int bi = i < bp.length ? parseIntSafe(bp[i]) : 0;
      if (ai != bi) return Integer.compare(ai, bi);
    }
    if (as[1].isEmpty() && !bs[1].isEmpty()) return 1;   // release > pre-release
    if (!as[1].isEmpty() && bs[1].isEmpty()) return -1;
    return as[1].compareTo(bs[1]);
  }

  private static String[] splitPrimary(String v) {
    int i = v.indexOf('-');
    return i < 0 ? new String[]{v, ""} : new String[]{v.substring(0, i), v.substring(i + 1)};
  }

  private static int parseIntSafe(String s) {
    try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
  }
}
