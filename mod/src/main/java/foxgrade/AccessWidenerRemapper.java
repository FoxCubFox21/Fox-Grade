// Rewrite class names inside a Fabric .accesswidener so it still points at the same members
// after the surrounding jar has been remapped. Java port of remap-accesswidener.mjs — same
// semantics, same output format, same whitespace preservation.
//
// A widener line looks like:
//   <access> <target> <owner> [name] [descriptor]
// The owner is a slash-delimited class name; the descriptor (when present) carries L…; class
// types. Both get rewritten by the same class rename table used for bytecode.
package foxgrade;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AccessWidenerRemapper {
  private static final Pattern DESC_TYPE = Pattern.compile("L([\\w/$]+);");
  private static final Pattern PART = Pattern.compile("(\\S+|\\s+)");

  public static final class Result {
    public final String text; public final int owners; public final int descriptors;
    Result(String t, int o, int d) { text = t; owners = o; descriptors = d; }
  }

  public static Result rewrite(String text, Map<String, String> slashTable) {
    if (slashTable.isEmpty()) return new Result(text, 0, 0);
    StringBuilder out = new StringBuilder(text.length());
    int owners = 0, descriptors = 0;
    boolean first = true;
    for (String rawLine : text.split("\n", -1)) {
      if (!first) out.append('\n');
      first = false;
      String stripped = rawLine.replaceAll("#.*$", "").stripTrailing();
      if (stripped.isEmpty()) { out.append(rawLine); continue; }
      // Header:  accessWidener v<N> <namespace>  — the namespace field must match the runtime
      // namespace Fabric uses to load classes. For MC 26.x (unobfuscated), that's "official".
      // For obfuscated builds, "intermediary" or "named" show up here. After Fox-Grade's remap
      // the class references are in the official (real) 26.2 names, so rewrite the header
      // namespace to match — otherwise Fabric refuses the widener with a namespace-mismatch.
      if (stripped.startsWith("accessWidener")) {
        String[] parts = stripped.split("\\s+");
        if (parts.length >= 3) parts[2] = "official";
        out.append(String.join(" ", parts));
        continue;
      }
      // Split preserving whitespace exactly, so we can rebuild the line verbatim.
      Matcher m = PART.matcher(rawLine);
      java.util.List<String> parts = new java.util.ArrayList<>();
      while (m.find()) parts.add(m.group());
      java.util.List<Integer> tokenIdx = new java.util.ArrayList<>();
      for (int i = 0; i < parts.size(); i++) if (!parts.get(i).isBlank()) tokenIdx.add(i);
      if (tokenIdx.size() < 3) { out.append(rawLine); continue; }
      // tokens: [access, target, owner, (name)?, (desc)?]
      int ownerIdx = tokenIdx.get(2);
      String owner = parts.get(ownerIdx);
      String toOwner = slashTable.get(owner);
      if (toOwner != null) { parts.set(ownerIdx, toOwner); owners++; }
      for (int t = 3; t < tokenIdx.size(); t++) {
        int idx = tokenIdx.get(t);
        String tok = parts.get(idx);
        if (tok.startsWith("(") || (tok.length() > 0 && "BCDFIJSZL[".indexOf(tok.charAt(0)) >= 0)) {
          String rewritten = remapDescriptor(tok, slashTable);
          if (!rewritten.equals(tok)) { parts.set(idx, rewritten); descriptors++; }
        }
      }
      for (String p : parts) out.append(p);
    }
    return new Result(out.toString(), owners, descriptors);
  }

  static String remapDescriptor(String desc, Map<String, String> table) {
    Matcher m = DESC_TYPE.matcher(desc);
    StringBuilder sb = new StringBuilder();
    int last = 0;
    while (m.find()) {
      sb.append(desc, last, m.start());
      String to = table.get(m.group(1));
      sb.append(to == null ? m.group() : "L" + to + ";");
      last = m.end();
    }
    sb.append(desc, last, desc.length());
    return sb.toString();
  }
}
