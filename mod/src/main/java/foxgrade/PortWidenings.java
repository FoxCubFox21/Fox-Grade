package foxgrade;

import java.util.ArrayList;
import java.util.List;

/** The widenings a ported mod needs, written into the port rather than into Fox-Grade.
 *
 *  <p>These entries used to live in Fox-Grade's own access widener, which was wrong twice over. They are not for
 *  Fox-Grade — every one of them exists because a ported mod expects a member the target made private, or expects to
 *  subclass something the target made final. And a single global widener cannot be right on more than one version:
 *  its header names a namespace, and "official" means Mojang names on 26.x and obfuscated names on everything older,
 *  so declaring one made Fabric reject the whole mod on 1.21.2 before anything else could go wrong.
 *
 *  <p>Written into each port instead, filtered against that target's inventory so an entry naming something the
 *  version does not have is left out rather than failing the load, and headed with the namespace that target
 *  actually uses. */
final class PortWidenings {
  private PortWidenings() { }

  /** {@code kind class member descriptor} — descriptor empty for a class-level entry. */
  private static final String[][] ENTRIES = {
      // Made private during record-ization; ports from older versions read them directly.
      {"accessible field", "net/minecraft/world/level/ChunkPos", "x", "I"},
      {"accessible field", "net/minecraft/world/level/ChunkPos", "z", "I"},
      // Made final; ports subclass it for custom recipe serializers.
      {"extendable class", "net/minecraft/world/item/crafting/RecipeSerializer", "", ""},
      // Put behind accessors; ports read the field.
      {"accessible field", "net/minecraft/world/level/Level", "isClientSide", "Z"},
      {"accessible field", "net/minecraft/world/entity/player/Inventory", "selected", "I"},
      {"accessible field", "net/minecraft/server/level/ServerPlayer", "server", "Lnet/minecraft/server/MinecraftServer;"},
      {"accessible field", "net/minecraft/client/gui/screens/Screen", "children", "Ljava/util/List;"},
      {"accessible field", "net/minecraft/world/level/block/ChestBlock", "SHAPE", "Lnet/minecraft/world/phys/shapes/VoxelShape;"},
  };

  /** The same widenings as an access transformer, for a NeoForge or Forge port, or null when none apply.
   *
   *  <p>Same entries, same inventory filter, different file format — and until this existed, NeoForge and Forge ports
   *  got no widenings at all, because the only writer sat inside the branch that edits a fabric.mod.json and a
   *  NeoForge mod has none. EntityCulling is what that costs: it reads ChunkPos.x, 26.2 made it private, and the
   *  port died on IllegalAccessError with the entry that would have fixed it sitting unused two lines away.
   *
   *  <p>A transformer says "public" where a widener says "accessible", and "public-f" — public, minus final — where
   *  a widener says "extendable". Fields are written without a descriptor, which is the format's own rule. */
  static String transformerFor(String targetMc) {
    List<String> lines = applicable(targetMc);
    if (lines == null) return null;
    StringBuilder out = new StringBuilder();
    out.append("# Written by Fox-Grade for this port: members the target made private or final that the mod was\n");
    out.append("# compiled against. Only entries this version actually has are listed.\n");
    for (String l : lines) {
      // applicable() writes "<kind> <class> [member desc]", and the kind is two words — "accessible field",
      // "extendable class". Splitting and reading e[1] as the class put the class where the field name goes and
      // NeoForge refused to start at all: "Invalid fieldname 'net/minecraft/world/level/ChunkPos' at line 3".
      String[] e = l.split(" ");
      String dotted = e[2].replace('/', '.');
      // "extendable" is about final, not visibility; public-f is the transformer that says both.
      out.append(e[1].equals("class") ? "public-f " + dotted : "public " + dotted + " " + e[3]).append('\n');
    }
    return out.toString();
  }

  /** The entries this target actually has, as {@code kind class [member desc]} strings, or null when none do. */
  private static List<String> applicable(String targetMc) {
    var present = TargetInventory.membersByClass(targetMc);
    if (present.isEmpty()) return null;                 // no inventory: say nothing rather than guess
    List<String> lines = new ArrayList<>();
    for (String[] e : ENTRIES) {
      var members = present.get(e[1]);
      if (members == null) continue;                    // the class itself is gone here
      if (!e[2].isEmpty() && !members.contains(e[2] + ":" + e[3])) continue;
      lines.add(e[2].isEmpty() ? e[0] + " " + e[1] : e[0] + " " + e[1] + " " + e[2] + " " + e[3]);
    }
    return lines.isEmpty() ? null : lines;
  }

  /** The widener document for this target, or null when none of it applies. */
  static String documentFor(String targetMc) {
    List<String> lines = applicable(targetMc);
    if (lines == null) return null;
    StringBuilder out = new StringBuilder("accessWidener v2 " + Targets.namespace(targetMc) + "\n");
    out.append("# Written by Fox-Grade for this port: members the target made private or final that the mod\n");
    out.append("# was compiled against. Only entries this version actually has are listed.\n");
    for (String l : lines) out.append(l).append('\n');
    return out.toString();
  }
}
