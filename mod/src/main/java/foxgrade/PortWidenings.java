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
      // Made private in 26.2, and read by an injected mixin from inside a vanilla class: EntityTextureFeatures'
      // handler runs in ModelPart and touches BufferBuilder.building, so the caller is vanilla and the widening is
      // the only thing that can let it through.
      {"accessible field", "com/mojang/blaze3d/vertex/BufferBuilder", "building", "Z"},
      // A private METHOD, which is why this list needed a third kind. LambDynamicLights' foundation calls it while
      // building a crash report.
      {"accessible method", "net/minecraft/SystemReport", "putSpaceForPath", "(Ljava/lang/String;Ljava/util/function/Supplier;)V"},
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
      // Three shapes, and the format spells each differently: a class alone (public-f, because "extendable" is about
      // final rather than visibility), a field as a bare name, and a method as name and descriptor written joined
      // with no space — which is the format's own rule and the reason the descriptor is carried here at all.
      if (e[1].equals("class")) out.append("public-f ").append(dotted);
      else if (e[1].equals("method")) out.append("public ").append(dotted).append(' ').append(e[3]).append(e[4]);
      else out.append("public ").append(dotted).append(' ').append(e[3]);
      out.append('\n');
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
      // The inventory writes a field as "name:descriptor" and a method as "name(args)return", so the key has to be
      // built the way the kind is stored. Asking for a method under the field spelling matches nothing, and the
      // entry is then dropped in silence — present in the list, absent from every port.
      String key = e[0].endsWith("method") ? e[2] + e[3] : e[2] + ":" + e[3];
      if (!e[2].isEmpty() && !members.contains(key)) continue;
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
