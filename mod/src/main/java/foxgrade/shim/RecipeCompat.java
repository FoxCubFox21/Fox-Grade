package foxgrade.shim;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** Recipe / loot / food builder shapes from 1.21.x over 26.2. */
public final class RecipeCompat {
  private RecipeCompat() {}
  public static RecipeManager getRecipeManager(Level level) { return level instanceof ServerLevel sl ? sl.recipeAccess() : null; }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static List getAllRecipesFor(RecipeManager manager, RecipeType type) {
    if (manager == null) return new ArrayList<>();
    try {   // the map field is private; find it by type
      for (java.lang.reflect.Field f : RecipeManager.class.getDeclaredFields()) {
        if (f.getType() == net.minecraft.world.item.crafting.RecipeMap.class) { f.setAccessible(true); return new ArrayList<>(((net.minecraft.world.item.crafting.RecipeMap) f.get(manager)).byType(type)); }
      }
    } catch (ReflectiveOperationException ignore) { }
    return new ArrayList<>();
  }
  public static LootPool.Builder conditionally(LootPool.Builder builder, LootItemCondition condition) { return builder.when(() -> condition); }
  /** {@code FoodProperties.Builder.effect(...)}: food effects moved to the Consumable component; the food itself still works. */
  public static FoodProperties.Builder effect(FoodProperties.Builder builder, MobEffectInstance effect, float probability) { return builder; }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.core.NonNullList getIngredients(net.minecraft.world.item.crafting.Recipe recipe) { net.minecraft.core.NonNullList l = net.minecraft.core.NonNullList.create(); l.addAll(recipe.placementInfo().ingredients()); return l; }
  /** 1.21.x {@code Ingredient.EMPTY}: 26.2 rejects empty ingredients, so the stand-in matches air (what an empty stack holds). */
  public static net.minecraft.world.item.crafting.Ingredient emptyIngredient() { return net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.BARRIER); }   // 26.2 rejects empty and air ingredients; a barrier is the visible stand-in
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.core.HolderLookup.RegistryLookup asLookup(net.minecraft.core.Registry registry) { return (net.minecraft.core.HolderLookup.RegistryLookup) registry; }
}
