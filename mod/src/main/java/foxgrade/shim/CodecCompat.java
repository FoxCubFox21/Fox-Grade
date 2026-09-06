package foxgrade.shim;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

/** Several vanilla {@code CODEC} constants became {@code MapCodec}s in 26.2 (MobSpawnSettings.SpawnerData among them).
 *  Old code that read them as a {@code Codec} gets the equivalent full codec. */
public final class CodecCompat {
  private CodecCompat() {}
  public static Codec<?> toCodec(MapCodec<?> map) { return map.codec(); }
}
