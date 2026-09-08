package foxgrade.shim;

/** Quilt's {@code org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader}. */
public final class MinecraftQuiltLoaderShim {
  private MinecraftQuiltLoaderShim() { }
  public static net.fabricmc.api.EnvType getEnvironmentType() { return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType(); }
}
