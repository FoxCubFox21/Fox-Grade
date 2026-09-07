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
  /** 1.21's {@code new ChunkPos(long packed)}: 26.2 only keeps the (x, z) constructor. */
  public static net.minecraft.world.level.ChunkPos chunkPos(long packed) { return new net.minecraft.world.level.ChunkPos((int) packed, (int) (packed >> 32)); }
  /** 1.21's {@code TicketType.create(name, comparator[, timeout])}: 26.2 ticket types are (timeout, flags) records without
   *  names; a mod-created ticket loads chunks, like the vanilla ones mods copied. */
  public static net.minecraft.server.level.TicketType ticketType(String name, java.util.Comparator<?> comparator, int timeout) {
    return new net.minecraft.server.level.TicketType((long) timeout, net.minecraft.server.level.TicketType.FLAG_LOADING);
  }
  public static net.minecraft.server.level.TicketType ticketType(String name, java.util.Comparator<?> comparator) { return ticketType(name, comparator, 0); }
  /** 1.21's {@code Biome.coldEnoughToSnow(pos)} / {@code warmEnoughToRain(pos)} / {@code getTemperature(pos)} take the
   *  sea level in 26.2; the overworld default is what every 1.21 caller implicitly used. */
  private static final int SEA_LEVEL = 63;
  public static boolean coldEnoughToSnow(net.minecraft.world.level.biome.Biome biome, net.minecraft.core.BlockPos pos) { return biome.coldEnoughToSnow(pos, SEA_LEVEL); }
  public static boolean warmEnoughToRain(net.minecraft.world.level.biome.Biome biome, net.minecraft.core.BlockPos pos) { return biome.warmEnoughToRain(pos, SEA_LEVEL); }
  private static java.lang.reflect.Method biomeTemperature;
  public static float biomeTemperature(net.minecraft.world.level.biome.Biome biome, net.minecraft.core.BlockPos pos) {
    try {   // private in 26.2 (was a public, deprecated accessor in 1.21)
      if (biomeTemperature == null) { biomeTemperature = net.minecraft.world.level.biome.Biome.class.getDeclaredMethod("getTemperature", net.minecraft.core.BlockPos.class, int.class); biomeTemperature.setAccessible(true); }
      return (Float) biomeTemperature.invoke(biome, pos, SEA_LEVEL);
    } catch (ReflectiveOperationException e) { return biome.getBaseTemperature(); }
  }
  /** 1.21's {@code Level.getSunAngle(partialTick)} / {@code getTimeOfDay(partialTick)}: 26.2 dropped them with the clock rework.
   *  Same curve vanilla used (DimensionType.timeOfDay), driven by the default clock. */
  public static float timeOfDay(net.minecraft.world.level.Level level, float partialTick) {
    double d = net.minecraft.util.Mth.frac((double) level.getDefaultClockTime() / 24000.0 - 0.25);
    double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
    return (float) (d * 2.0 + e) / 3.0f;
  }
  public static float sunAngle(net.minecraft.world.level.Level level, float partialTick) { return timeOfDay(level, partialTick) * ((float) Math.PI * 2f); }

  /** 1.21 DimensionType.effectsLocation(): the classic effects id, read back from the 26.2 skybox kind. */
  public static net.minecraft.resources.Identifier effectsLocation(net.minecraft.world.level.dimension.DimensionType type) {
    Object sky = type.skybox();
    String s = sky == null ? "" : sky.toString().toLowerCase(java.util.Locale.ROOT);   // OVERWORLD, END, NONE
    return net.minecraft.resources.Identifier.withDefaultNamespace(s.contains("end") ? "the_end" : s.contains("overworld") ? "overworld" : "the_nether");
  }

  /** 1.21 WorldData.worldGenOptions(): 26.2 keeps no WorldOptions on the level data. A stand-in whose seed is stable
   *  per level name, so callers that key caches on it (Distant Horizons) stay consistent between launches. */
  public static net.minecraft.world.level.levelgen.WorldOptions worldGenOptions(net.minecraft.world.level.storage.WorldData data) {
    long seed = 0;
    try { seed = data.getLevelSettings().levelName().hashCode() * 0x9E3779B97F4A7C15L; } catch (RuntimeException ignored) { }
    return new net.minecraft.world.level.levelgen.WorldOptions(seed, true, false);
  }
}
