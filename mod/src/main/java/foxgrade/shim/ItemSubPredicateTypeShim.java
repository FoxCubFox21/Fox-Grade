package foxgrade.shim;

/** Stands in for 1.21's {@code ItemSubPredicate.Type<T>(Codec<T> codec)} record. */
public final class ItemSubPredicateTypeShim<T> {
  private final com.mojang.serialization.Codec<T> codec;
  public ItemSubPredicateTypeShim(com.mojang.serialization.Codec<T> codec) { this.codec = codec; }
  public com.mojang.serialization.Codec<T> codec() { return codec; }
}
