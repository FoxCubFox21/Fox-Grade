package foxgrade.shim;

import com.google.gson.JsonObject;

/** 1.21.x {@code MetadataSectionSerializer<T>}: pack metadata sections are codec-typed on 26.2. Mods define serializers
 *  for their own pack sections; the lookups they drive return "no section" here. */
public interface MetadataSectionSerializerShim<T> {
  String getMetadataSectionName();
  T fromJson(JsonObject json);
}
