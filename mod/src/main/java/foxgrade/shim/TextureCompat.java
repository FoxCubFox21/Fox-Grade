package foxgrade.shim;

import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.AbstractTexture;

public final class TextureCompat {
  private TextureCompat() { }
  /** The debug name 26.2's DynamicTexture constructor requires; old code had none to give. */
  public static Supplier<String> name() { return () -> "fox-grade-port"; }
  /** Filtering is a sampler property now; nothing to set on the texture. */
  public static void setFilter(AbstractTexture t, boolean blur, boolean mipmap) { }
}
