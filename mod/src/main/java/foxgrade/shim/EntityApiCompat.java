package foxgrade.shim;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;

/** The recurring 1.21.x → 26.2 entity-API deltas that need more than a rename. */
public final class EntityApiCompat {
  private EntityApiCompat() { }
  /** The ServerLevel a 26.2 method wants that the 1.21.x caller never had to pass. */
  public static ServerLevel serverLevel(Object entity) { Level l = ((Entity) entity).level(); return l instanceof ServerLevel s ? s : null; }
  public static void knockback(LivingEntity e, double strength, double x, double z) { e.knockback(strength, x, z, e.level().damageSources().generic(), 1f); }
  public static UUID getOwnerUUID(OwnableEntity e) { EntityReference<LivingEntity> r = e.getOwnerReference(); return r == null ? null : r.getUUID(); }
  public static void setOwnerUUID(TamableAnimal e, UUID id) { e.setOwnerReference(ownerReference(id)); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static EntityReference<LivingEntity> ownerReference(UUID id) { if (id == null) return null; EntityReference raw = EntityReference.of(id); return (EntityReference<LivingEntity>) raw; }
  public static void addPersistentAngerSaveData(NeutralMob m, CompoundTag tag) { var o = NbtBridge.outputFor(tag); m.addPersistentAngerSaveData(o); NbtBridge.flushSupers(tag); }
  public static void readPersistentAngerSaveData(NeutralMob m, Level level, CompoundTag tag) { m.readPersistentAngerSaveData(level, NbtBridge.inputFor(tag, level)); }
}
