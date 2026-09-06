package foxgrade.shim;

import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;

/** 1.21.x particle shapes over 26.2. */
public final class ParticleCompat {
  private ParticleCompat() {}
  private static int channel(float f) { return Math.max(0, Math.min(255, Math.round(f * 255f))); }
  public static DustParticleOptions dust(Vector3f color, float scale) {
    int argb = 0xFF000000 | (channel(color.x()) << 16) | (channel(color.y()) << 8) | channel(color.z());
    return new DustParticleOptions(argb, scale);
  }
  public static ParticleRenderType sheetTranslucent() { return ParticleRenderType.SINGLE_QUADS; }
}
