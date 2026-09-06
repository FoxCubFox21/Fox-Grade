package foxgrade.shim;

import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;

/** 1.21.x Level day/night + profiler accessors over 26.2. */
public final class LevelCompat {
  private LevelCompat() {}
  public static boolean isDay(Level level) { return level.isBrightOutside(); }
  public static boolean isNight(Level level) { return level.isDarkOutside(); }
  /** 1.21.x {@code Level.getTimeOfDay(partialTick)}: the vanilla celestial-angle formula over the day time. */
  public static float getTimeOfDay(Level level, float partialTick) {
    double frac = Mth.frac(dayTime(level) / 24000.0 - 0.25);
    double curve = 0.5 - Math.cos(frac * Math.PI) / 2.0;
    return (float) (frac * 2.0 + curve) / 3.0f;
  }
  /** 1.21.x {@code Level.getDayTime()}: 26.2 keeps per-dimension timelines; the default clock is the old day time. */
  public static long getDayTime(Level level) { return level.getDefaultClockTime(); }
  private static long dayTime(Level level) { return level.getDefaultClockTime(); }
  public static ProfilerFiller getProfiler(Level level) { return Profiler.get(); }
  public static net.minecraft.util.RandomSource randomOf(net.minecraft.world.level.LevelAccessor level) { return level.getRandom(); }

  // ---- batch 3: TargetingConditions-based lookups 26.2 removed from Level
  private static boolean pass(Level level, net.minecraft.world.entity.ai.targeting.TargetingConditions tc, net.minecraft.world.entity.LivingEntity from, net.minecraft.world.entity.LivingEntity candidate) {
    return level instanceof net.minecraft.server.level.ServerLevel sl && tc.test(sl, from, candidate);
  }
  public static net.minecraft.world.entity.player.Player getNearestPlayer(Level level, net.minecraft.world.entity.ai.targeting.TargetingConditions tc, net.minecraft.world.entity.LivingEntity from) {
    net.minecraft.world.entity.player.Player best = null; double bestD = Double.MAX_VALUE;
    for (net.minecraft.world.entity.player.Player p : level.players()) { double d = p.distanceToSqr(from); if (d < bestD && pass(level, tc, from, p)) { best = p; bestD = d; } }
    return best;
  }
  public static java.util.List<net.minecraft.world.entity.player.Player> getNearbyPlayers(Level level, net.minecraft.world.entity.ai.targeting.TargetingConditions tc, net.minecraft.world.entity.LivingEntity from, net.minecraft.world.phys.AABB box) {
    java.util.List<net.minecraft.world.entity.player.Player> out = new java.util.ArrayList<>();
    for (net.minecraft.world.entity.player.Player p : level.players()) if (box.contains(p.position()) && pass(level, tc, from, p)) out.add(p);
    return out;
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static java.util.List getNearbyEntities(Level level, Class cls, net.minecraft.world.entity.ai.targeting.TargetingConditions tc, net.minecraft.world.entity.LivingEntity from, net.minecraft.world.phys.AABB box) {
    java.util.List<net.minecraft.world.entity.LivingEntity> found = level.getEntitiesOfClass(cls, box, (e) -> pass(level, tc, from, (net.minecraft.world.entity.LivingEntity) e));
    return found;
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static net.minecraft.world.entity.LivingEntity getNearestEntity(Level level, Class cls, net.minecraft.world.entity.ai.targeting.TargetingConditions tc, net.minecraft.world.entity.LivingEntity from, double x, double y, double z, net.minecraft.world.phys.AABB box) {
    net.minecraft.world.entity.LivingEntity best = null; double bestD = Double.MAX_VALUE;
    for (Object o : getNearbyEntities(level, cls, tc, from, box)) { net.minecraft.world.entity.LivingEntity e = (net.minecraft.world.entity.LivingEntity) o; double d = e.distanceToSqr(x, y, z); if (d < bestD) { best = e; bestD = d; } }
    return best;
  }
}
