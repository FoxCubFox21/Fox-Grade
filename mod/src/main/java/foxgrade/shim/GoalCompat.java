package foxgrade.shim;

import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

/** 1.21.x target predicates over 26.2's level-aware {@link TargetingConditions.Selector}. */
public final class GoalCompat {
  private GoalCompat() {}
  public static TargetingConditions.Selector selector(Predicate<LivingEntity> predicate) {
    return predicate == null ? null : (entity, level) -> predicate.test(entity);
  }
  /** {@code TargetingConditions.selector(Predicate)} call site. */
  public static TargetingConditions selector(TargetingConditions conditions, Predicate<LivingEntity> predicate) {
    return conditions.selector(selector(predicate));
  }
}
