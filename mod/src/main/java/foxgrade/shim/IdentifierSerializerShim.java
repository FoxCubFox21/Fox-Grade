package foxgrade.shim;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import net.minecraft.resources.Identifier;

/** 1.21.x {@code ResourceLocation$Serializer}: the Gson adapter mods register for identifiers. */
public final class IdentifierSerializerShim implements JsonDeserializer<Identifier>, JsonSerializer<Identifier> {
  public IdentifierSerializerShim() {}
  @Override public Identifier deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) { return Identifier.parse(json.getAsString()); }
  @Override public JsonElement serialize(Identifier id, Type type, JsonSerializationContext ctx) { return new JsonPrimitive(id.toString()); }
}
