package foxgrade.shim;

/** Stands in for 1.21's {@code WeightedEntry} (with its {@code Wrapper} and {@code IntrusiveBase}), removed in 26.2. */
public interface WeightedEntryShim {
  WeightShim getWeight();

  static <T> Wrapper<T> wrap(T data, int weight) { return new Wrapper<>(data, WeightShim.of(weight)); }

  final class Wrapper<T> implements WeightedEntryShim {
    private final T data; private final WeightShim weight;
    public Wrapper(T data, WeightShim weight) { this.data = data; this.weight = weight; }
    public T data() { return data; }
    public T getData() { return data; }
    @Override public WeightShim getWeight() { return weight; }
    public WeightShim weight() { return weight; }
    public static <T> Wrapper<T> wrap(T data, int weight) { return new Wrapper<>(data, WeightShim.of(weight)); }
    public static <E> com.mojang.serialization.Codec<Wrapper<E>> codec(com.mojang.serialization.Codec<E> elementCodec) {
      return com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
          elementCodec.fieldOf("data").forGetter(Wrapper::data), WeightShim.CODEC.fieldOf("weight").forGetter(Wrapper::weight)).apply(i, Wrapper::new));
    }
  }

  abstract class IntrusiveBase implements WeightedEntryShim {
    private final WeightShim weight;
    protected IntrusiveBase(int weight) { this.weight = WeightShim.of(weight); }
    protected IntrusiveBase(WeightShim weight) { this.weight = weight; }
    @Override public WeightShim getWeight() { return weight; }
  }
}
