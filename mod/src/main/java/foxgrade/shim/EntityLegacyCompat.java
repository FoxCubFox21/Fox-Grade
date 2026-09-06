package foxgrade.shim;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** 1.21.x Entity/Mob members that 26.2 removed or gave a ServerLevel parameter. Calls that need a
 *  server level and don't have one become no-ops on the client, which is what vanilla did anyway. */
public final class EntityLegacyCompat {
  private EntityLegacyCompat() {}
  private static ServerLevel server(Entity e) { return e.level() instanceof ServerLevel sl ? sl : null; }

  public static ItemEntity spawnAtLocation(Entity e, ItemLike item) { ServerLevel sl = server(e); return sl == null ? null : e.spawnAtLocation(sl, item); }
  public static ItemEntity spawnAtLocation(Entity e, ItemStack stack) { ServerLevel sl = server(e); return sl == null ? null : e.spawnAtLocation(sl, stack); }
  public static ItemEntity spawnAtLocation(Entity e, ItemStack stack, float yOffset) { ServerLevel sl = server(e); return sl == null ? null : e.spawnAtLocation(sl, stack, yOffset); }
  public static boolean isInvulnerableTo(Entity e, DamageSource source) {
    ServerLevel sl = server(e);
    if (sl != null && e instanceof LivingEntity le) return le.isInvulnerableTo(sl, source);
    return e.isInvulnerable() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
  }
  public static boolean wantsToPickUp(Mob m, ItemStack stack) { ServerLevel sl = server(m); return sl != null && m.wantsToPickUp(sl, stack); }
  public static boolean killedEntity(Entity e, ServerLevel level, LivingEntity victim) { return e.killedEntity(level, victim, e.damageSources().generic()); }
  public static boolean isInWaterOrBubble(Entity e) { return e.isInWater(); }
  public static boolean isControlledByLocalInstance(Entity e) { return e.isLocalInstanceAuthoritative(); }
  public static void tryCheckInsideBlocks(Entity e) { /* 26.2 does this during movement */ }
  public static float walkDist(Entity e) { return 0f; }
  public static float walkDistO(Entity e) { return 0f; }
  public static void setWalkDist(Entity e, float v) {}
  public static void setWalkDistO(Entity e, float v) {}
  public static boolean hasImpulse(Entity e) { return e.needsSync; }
  public static void setHasImpulse(Entity e, boolean v) { e.needsSync = v; }
  public static void setCanPassDoors(PathNavigation nav, boolean v) { /* folded into setCanOpenDoors in 26.2 */ }
  public static SoundEvent getEatingSound(LivingEntity e, ItemStack stack) { Object o = SoundEvents.GENERIC_EAT; return o instanceof net.minecraft.core.Holder<?> h ? (SoundEvent) h.value() : (SoundEvent) o; }

  private static final Map<Mob, float[]> HAND_DROP = new WeakHashMap<>();
  public static float[] handDropChances(Mob m) { synchronized (HAND_DROP) { return HAND_DROP.computeIfAbsent(m, k -> new float[]{0.085f, 0.085f}); } }
  public static void setHandDropChances(Mob m, float[] v) { synchronized (HAND_DROP) { HAND_DROP.put(m, v); } }

  // ---- batch 3
  public static boolean isDamageSourceBlocked(LivingEntity e, DamageSource source) { return e.isBlocking(); }
  public static void clearRestriction(net.minecraft.world.entity.PathfinderMob mob) { /* 26.2 dropped home restrictions on PathfinderMob */ }
  public static net.minecraft.world.phys.AABB getAttackBoundingBox(net.minecraft.world.entity.animal.Animal animal) { return animal.getBoundingBox(); }
  public static net.minecraft.world.phys.Vec3 zeroVec() { return net.minecraft.world.phys.Vec3.ZERO; }
  /** 1.21.x {@code LivingEntity.getSlotForHand(hand)} was static. */
  public static net.minecraft.world.entity.EquipmentSlot getSlotForHand(net.minecraft.world.InteractionHand hand) { return hand == net.minecraft.world.InteractionHand.OFF_HAND ? net.minecraft.world.entity.EquipmentSlot.OFFHAND : net.minecraft.world.entity.EquipmentSlot.MAINHAND; }
  public static net.minecraft.world.entity.EquipmentSlot getSlotForHand(LivingEntity e, net.minecraft.world.InteractionHand hand) { return hand == net.minecraft.world.InteractionHand.OFF_HAND ? net.minecraft.world.entity.EquipmentSlot.OFFHAND : net.minecraft.world.entity.EquipmentSlot.MAINHAND; }
  public static void displayClientMessage(net.minecraft.world.entity.player.Player p, net.minecraft.network.chat.Component msg, boolean actionBar) { p.sendSystemMessage(msg); }
  public static float fallDistance(Entity e) { return (float) e.fallDistance; }
  public static void setFallDistance(Entity e, float v) { e.fallDistance = v; }
  public static net.minecraft.nbt.CompoundTag shoulderEntity(net.minecraft.world.entity.player.Player p) { return new net.minecraft.nbt.CompoundTag(); }
  public static Entity create(net.minecraft.world.entity.EntityType<?> type, net.minecraft.world.level.Level level) { return type.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED); }
  public static net.minecraft.world.entity.EntitySpawnReason spawnEggReason() { return net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE; }
  public static java.util.Optional<net.minecraft.world.entity.EntityType<?>> byString(String id) {
    net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
    return key == null ? java.util.Optional.empty() : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(key).map(h -> (net.minecraft.world.entity.EntityType<?>) h.value());
  }
  public static boolean isType(net.minecraft.world.entity.EntityType<?> type, net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> tag) { return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).is(tag); }
  public static float one() { return 1f; }
  public static ItemStack emptyStack() { return ItemStack.EMPTY; }
  public static java.util.Set<?> emptySet() { return java.util.Set.of(); }
  public static java.util.Set<?> setOf(Object o) { return java.util.Set.of(o); }
  public static boolean save(Entity e, net.minecraft.nbt.CompoundTag tag) {
    net.minecraft.world.level.storage.TagValueOutput out = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
    boolean r = e.save(out); tag.merge(out.buildResult()); return r;
  }

  // ---- batch 4
  public static net.minecraft.server.MinecraftServer getServer(Entity e) { return e.level().getServer(); }
  public static net.minecraft.commands.CommandSourceStack createCommandSourceStack(net.minecraft.world.entity.player.Player p) { return p instanceof net.minecraft.server.level.ServerPlayer sp ? sp.createCommandSourceStack() : null; }
  public static boolean falseValue() { return false; }
  public static net.minecraft.world.phys.Vec3 getCenter(net.minecraft.core.BlockPos pos) { return net.minecraft.world.phys.Vec3.atCenterOf(pos); }
  public static net.minecraft.world.entity.EntityEquipment newEquipment() { return new net.minecraft.world.entity.EntityEquipment(); }

  /** 1.21.x {@code LivingEntity.getArmorSlots()}: the four armour stacks. */
  public static Iterable<ItemStack> getArmorSlots(LivingEntity e) {
    return java.util.List.of(e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET), e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS), e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
  }
  /** 1.21's {@code Entity.createCommandSourceStack()}: 26.2 keeps it on ServerPlayer only; other entities resolve through their level. */
  public static net.minecraft.commands.CommandSourceStack commandSourceStack(net.minecraft.world.entity.Entity e) {
    if (e instanceof net.minecraft.server.level.ServerPlayer p) return p.createCommandSourceStack();
    if (e.level() instanceof net.minecraft.server.level.ServerLevel sl) return e.createCommandSourceStackForNameResolution(sl);
    throw new IllegalStateException("no server level for " + e);
  }
}
