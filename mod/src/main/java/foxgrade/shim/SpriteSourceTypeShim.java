package foxgrade.shim;

/** Type stand-in for 1.21's {@code SpriteSourceType} record; 26.2 registers sprite sources by MapCodec (RegistryCompat unwraps). */
public final class SpriteSourceTypeShim {
  private final com.mojang.serialization.MapCodec<?> codec;
  public SpriteSourceTypeShim(com.mojang.serialization.MapCodec<?> codec) { this.codec = codec; }
  public com.mojang.serialization.MapCodec<?> codec() { return codec; }
}
