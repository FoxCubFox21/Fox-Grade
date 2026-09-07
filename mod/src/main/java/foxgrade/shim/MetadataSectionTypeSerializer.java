package foxgrade.shim;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.packs.metadata.MetadataSectionType;

/** A 26.2 MetadataSectionType seen through the 1.21 serializer interface, for a mod's old getMetadataSection(serializer)
 *  override that the game now calls with the codec-typed section. A named top-level class: anonymous shim classes are
 *  not carried into ports. */
public final class MetadataSectionTypeSerializer<T> implements MetadataSectionSerializerShim<T> {
  private final MetadataSectionType<T> type;
  public MetadataSectionTypeSerializer(MetadataSectionType<T> type) { this.type = type; }
  @Override public String getMetadataSectionName() { return type.name(); }
  @Override public T fromJson(JsonObject json) { return type.codec().parse(JsonOps.INSTANCE, json).result().orElse(null); }
  public MetadataSectionType<T> type() { return type; }
}
