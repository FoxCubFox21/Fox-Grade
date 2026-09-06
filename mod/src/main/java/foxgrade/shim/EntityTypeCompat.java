package foxgrade.shim;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;

public final class EntityTypeCompat {
  private EntityTypeCompat() { }
  /** 1.21.x build(String id) → 26.2 build(ResourceKey). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static EntityType build(EntityType.Builder b, String id) { return b.build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(id))); }
}
