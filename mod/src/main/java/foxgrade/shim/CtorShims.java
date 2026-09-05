// Argument suppliers for constructor-signature adapters (see BytecodeRemapper's ctor stage).
// Each method turns an argument a pre-26.x constructor call has on the stack into the value the
// 26.2 constructor wants in that slot. Reviewed one at a time, same bar as every other shim.
package foxgrade.shim;

import net.minecraft.core.Registry;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

public final class CtorShims {
  private CtorShims() { }

  // ChunkAccess.<init> slot 4: Registry<Biome> → PalettedContainerFactory. Mods construct dummy
  // chunks while a level is loaded (the biome registry they pass came FROM that level), so the
  // level's own factory is the faithful replacement.
  // KeyMapping.<init> last slot: legacy category translation key (String) → KeyMapping.Category.
  // Vanilla keys map onto the vanilla constants; anything else becomes a Fox-Grade-namespaced
  // category, added to the controls screen's sort list when that list allows it.
  private static final java.util.Map<String, net.minecraft.client.KeyMapping.Category> KEY_CATS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.Map<net.minecraft.client.KeyMapping.Category, String> KEY_CATS_REVERSE = new java.util.concurrent.ConcurrentHashMap<>();

  // Old KeyMapping.getCategory() returned the category's translation-key String; 26.2 returns
  // the Category record. Vanilla constants reverse-map to their classic keys, Fox-Grade-made
  // categories to the exact legacy string the mod passed in, so string comparisons in the mod
  // (grouping, sorting, config round-trips) keep working.
  public static String getCategoryString(net.minecraft.client.KeyMapping km) {
    var cat = km.getCategory();
    if (cat == net.minecraft.client.KeyMapping.Category.MOVEMENT) return "key.categories.movement";
    if (cat == net.minecraft.client.KeyMapping.Category.INVENTORY) return "key.categories.inventory";
    if (cat == net.minecraft.client.KeyMapping.Category.MULTIPLAYER) return "key.categories.multiplayer";
    if (cat == net.minecraft.client.KeyMapping.Category.GAMEPLAY) return "key.categories.gameplay";
    if (cat == net.minecraft.client.KeyMapping.Category.CREATIVE) return "key.categories.creative";
    if (cat == net.minecraft.client.KeyMapping.Category.SPECTATOR) return "key.categories.spectator";
    if (cat == net.minecraft.client.KeyMapping.Category.MISC) return "key.categories.misc";
    String rev = KEY_CATS_REVERSE.get(cat);
    return rev != null ? rev : cat.id().toString();
  }

  public static net.minecraft.client.KeyMapping.Category keyCategory(String legacy) {
    String l = legacy == null ? "misc" : legacy.toLowerCase();
    if (l.contains("movement")) return net.minecraft.client.KeyMapping.Category.MOVEMENT;
    if (l.contains("inventory")) return net.minecraft.client.KeyMapping.Category.INVENTORY;
    if (l.contains("multiplayer")) return net.minecraft.client.KeyMapping.Category.MULTIPLAYER;
    if (l.contains("gameplay")) return net.minecraft.client.KeyMapping.Category.GAMEPLAY;
    if (l.contains("creative")) return net.minecraft.client.KeyMapping.Category.CREATIVE;
    if (l.contains("spectator")) return net.minecraft.client.KeyMapping.Category.SPECTATOR;
    if (l.contains("debug")) return net.minecraft.client.KeyMapping.Category.DEBUG;
    if (l.contains("misc")) return net.minecraft.client.KeyMapping.Category.MISC;
    return KEY_CATS.computeIfAbsent(l, (k) -> {
      String path = k.replaceAll("[^a-z0-9/._-]", "_");
      var cat = new net.minecraft.client.KeyMapping.Category(
          net.minecraft.resources.Identifier.fromNamespaceAndPath("foxgrade", path));
      KEY_CATS_REVERSE.put(cat, legacy);
      try {
        var f = net.minecraft.client.KeyMapping.Category.class.getDeclaredField("SORT_ORDER");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var list = (java.util.List<Object>) f.get(null);
        if (!list.contains(cat)) list.add(cat);
      } catch (Throwable ignored) { }   // private/immutable list: the key still works, just unsorted
      return cat;
    });
  }

  // Replacement for Fabric API's removed internal KeyBindingAccessor.fabric_getCategoryMap():
  // the old String-category sort map. Mods that reach into it (ukulib's keybind screen) only
  // read/insert sort entries; returning a live map of the classic categories keeps them running
  // with sorting quietly ignored — degraded beats a NoSuchMethodError at init.
  private static final java.util.Map<String, Integer> FABRIC_CATEGORY_MAP = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(
      "key.categories.movement", 1, "key.categories.gameplay", 2, "key.categories.inventory", 3,
      "key.categories.creative", 4, "key.categories.multiplayer", 5, "key.categories.ui", 6,
      "key.categories.misc", 7));

  public static java.util.Map<String, Integer> fabricCategoryMap() { return FABRIC_CATEGORY_MAP; }

  public static PalettedContainerFactory chunkFactory(Registry<?> biomes) {
    var mc = net.minecraft.client.Minecraft.getInstance();
    if (mc != null && mc.level != null) return mc.level.palettedContainerFactory();
    throw new IllegalStateException("Fox-Grade: no client level to derive a PalettedContainerFactory from");
  }
}
