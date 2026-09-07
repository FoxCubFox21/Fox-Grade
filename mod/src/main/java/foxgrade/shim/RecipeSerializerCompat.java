package foxgrade.shim;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/** 1.21's {@code SimpleCraftingRecipeSerializer(factory)}: in 26.2 it is the RecipeSerializer record built from the two
 *  codecs a category-only special recipe needs. */
public final class RecipeSerializerCompat {
  private RecipeSerializerCompat() {}
  public static <T extends CraftingRecipe> MapCodec<T> simpleMapCodec(java.util.function.Function<CraftingBookCategory, T> factory) {
    return RecordCodecBuilder.mapCodec(i -> i.group(CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(CraftingRecipe::category)).apply(i, factory));
  }
  public static <T extends CraftingRecipe> StreamCodec<RegistryFriendlyByteBuf, T> simpleStreamCodec(java.util.function.Function<CraftingBookCategory, T> factory) {
    return StreamCodec.composite(CraftingBookCategory.STREAM_CODEC, CraftingRecipe::category, factory);
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static RecipeSerializer simpleSerializer(java.util.function.Function factory) { return new RecipeSerializer(simpleMapCodec(factory), simpleStreamCodec(factory)); }
}
