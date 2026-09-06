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
}
