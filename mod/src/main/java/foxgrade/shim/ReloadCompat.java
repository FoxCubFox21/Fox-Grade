package foxgrade.shim;

import com.mojang.serialization.Codec;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

/** 1.21.x resource-reload listener shapes over 26.2 (shared state instead of a manager + two profilers). */
public final class ReloadCompat {
  private ReloadCompat() {}
  public static ResourceManager resourceManager(PreparableReloadListener.SharedState state) { return state.resourceManager(); }
  public static PreparableReloadListener.SharedState sharedState(ResourceManager manager) { return new PreparableReloadListener.SharedState(manager); }
  public static ProfilerFiller profiler() { return Profiler.get(); }
  /** {@code new SimpleJsonResourceReloadListener(gson, "dir")}: the JSON codec keeps the map values as JsonElement. */
  public static Codec<?> jsonCodec() { return ExtraCodecs.JSON; }
  public static FileToIdConverter jsonConverter(String directory) { return FileToIdConverter.json(directory); }
}
