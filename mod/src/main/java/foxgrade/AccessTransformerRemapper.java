package foxgrade;

/** NeoForge/Forge access transformers, translated the way {@link AccessWidenerRemapper} translates Fabric's access
 *  wideners: same job, different file format.
 *
 *  <p>A line is {@code <modifier> <dotted.Class> [member[descriptor]] [# comment]}, for example:
 *  <pre>
 *  public net.minecraft.client.Minecraft screen
 *  public-f net.minecraft.world.item.ItemStack &lt;init&gt;(Lnet/minecraft/world/level/ItemLike;I)V
 *  </pre>
 *  Two things differ from a widener and both matter. The class is dotted rather than slashed, and a method's name and
 *  descriptor are written together with no space between them, so the descriptor has to be split off before the name
 *  can be looked up and rejoined afterwards.
 *
 *  <p>A transformer aimed at a name the target renamed silently widens nothing, and the mod then fails on an access
 *  it was promised — which is why this runs at all rather than passing the file through untouched. Anything not
 *  understood is left exactly as it was. */
final class AccessTransformerRemapper {
  private AccessTransformerRemapper() { }

  static boolean isTransformer(String entryName) {
    String n = entryName.toLowerCase(java.util.Locale.ROOT);
    return n.startsWith("meta-inf/") && n.endsWith(".cfg") && n.contains("accesstransformer");
  }

  static final class Result {
    final String text; final int owners, members;
    Result(String t, int o, int m) { text = t; owners = o; members = m; }
  }

  static Result rewrite(String text, AccessWidenerRemapper.Names names) {
    StringBuilder out = new StringBuilder();
    int owners = 0, members = 0;
    boolean first = true;
    for (String raw : text.split("\n", -1)) {
      if (!first) out.append('\n');
      first = false;
      int hash = raw.indexOf('#');
      String body = hash < 0 ? raw : raw.substring(0, hash);
      String comment = hash < 0 ? "" : raw.substring(hash);
      String[] parts = body.trim().split("\\s+");
      if (parts.length < 2 || parts[0].isEmpty()) { out.append(raw); continue; }

      String dotted = parts[1];
      String internal = dotted.replace('.', '/');
      String newInternal = names.clazz(internal);
      if (newInternal == null) newInternal = internal;
      if (!newInternal.equals(internal)) owners++;

      String member = parts.length >= 3 ? parts[2] : null;
      String newMember = member;
      if (member != null) {
        int paren = member.indexOf('(');
        if (paren >= 0) {                                   // a method: name and descriptor are written joined
          String mName = member.substring(0, paren), mDesc = member.substring(paren);
          String mapped = names.method(internal, mName, mDesc);
          String mappedDesc = names.desc(mDesc);
          if (mappedDesc == null) mappedDesc = mDesc;
          newMember = (mapped == null ? mName : mapped) + mappedDesc;
        } else {                                            // a field: bare name, descriptor not written
          String mapped = names.field(internal, member, "");
          if (mapped != null) newMember = mapped;
        }
        if (!newMember.equals(member)) members++;
      }

      out.append(parts[0]).append(' ').append(newInternal.replace('/', '.'));
      if (newMember != null) out.append(' ').append(newMember);
      for (int i = 3; i < parts.length; i++) out.append(' ').append(parts[i]);
      out.append(comment);
    }
    return new Result(out.toString(), owners, members);
  }
}
