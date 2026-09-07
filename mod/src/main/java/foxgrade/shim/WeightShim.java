package foxgrade.shim;

/** Stands in for 1.21's {@code net.minecraft.util.random.Weight}, removed with the weighted-list rewrite in 26.2. */
public final class WeightShim {
  public static final com.mojang.serialization.Codec<WeightShim> CODEC = com.mojang.serialization.Codec.INT.xmap(WeightShim::of, WeightShim::asInt);
  private final int value;
  private WeightShim(int value) { this.value = value; }
  public static WeightShim of(int value) { return new WeightShim(value); }
  public int asInt() { return value; }
  @Override public String toString() { return Integer.toString(value); }
  @Override public boolean equals(Object o) { return o instanceof WeightShim w && w.value == value; }
  @Override public int hashCode() { return value; }
}
