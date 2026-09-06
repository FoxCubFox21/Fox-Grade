package foxgrade.shim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.world.entity.AnimationState;
import org.joml.Vector3f;

/** 1.21.x animation helpers: accumulated time came from the state; 26.2 derives it from the age. */
public final class AnimationCompat {
  private AnimationCompat() {}
  public static long getAccumulatedTime(AnimationState state) {
    if (state == null || !state.isStarted()) return 0L;
    long tick = Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
    return Math.max(0L, (tick - startTick(state)) * 50L);
  }
  private static java.lang.reflect.Field START;
  private static long startTick(AnimationState state) {
    try {
      if (START == null) { START = AnimationState.class.getDeclaredField("startTick"); START.setAccessible(true); }
      return START.getInt(state);
    } catch (ReflectiveOperationException e) { return 0L; }
  }
  public static void animate(HierarchicalModelShim model, AnimationDefinition definition, long accumulatedMillis, float scale, Vector3f scratch) {
    model.animateMillis(definition, accumulatedMillis, scale);
  }
}
