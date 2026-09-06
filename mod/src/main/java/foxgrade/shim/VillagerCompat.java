package foxgrade.shim;

import com.google.common.collect.ImmutableSet;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** 1.21.x {@code new VillagerProfession(name, heldJobSite, acquirableJobSite, requestedItems, secondaryPoi, workSound)}. */
public final class VillagerCompat {
  private VillagerCompat() {}
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static VillagerProfession profession(String name, Predicate heldJobSite, Predicate acquirableJobSite, ImmutableSet requestedItems, ImmutableSet secondaryPoi, SoundEvent workSound) {
    return new VillagerProfession(Component.translatable("entity.minecraft.villager." + name), heldJobSite, acquirableJobSite, requestedItems, secondaryPoi, workSound, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
  }
}
