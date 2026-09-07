package foxgrade.shim;

import java.util.List;
import java.util.Optional;

/** Stands in for 1.21's {@code WeightedRandomList}: an immutable list of weighted entries with a weighted pick. Mods that
 *  kept their own weighted lists in this shape keep working; 26.2's own worldgen uses WeightedList, which is not this. */
public final class WeightedRandomListShim<E extends WeightedEntryShim> {
  private final List<E> items; private final int totalWeight;
  private WeightedRandomListShim(List<E> items) {
    this.items = List.copyOf(items);
    int t = 0; for (E e : this.items) t += e.getWeight().asInt(); this.totalWeight = t;
  }
  public static <E extends WeightedEntryShim> WeightedRandomListShim<E> create() { return new WeightedRandomListShim<>(List.of()); }
  @SafeVarargs public static <E extends WeightedEntryShim> WeightedRandomListShim<E> create(E... items) { return new WeightedRandomListShim<>(List.of(items)); }
  public static <E extends WeightedEntryShim> WeightedRandomListShim<E> create(List<E> items) { return new WeightedRandomListShim<>(items); }
  public static <E extends WeightedEntryShim> com.mojang.serialization.Codec<WeightedRandomListShim<E>> codec(com.mojang.serialization.Codec<E> elementCodec) {
    return elementCodec.listOf().xmap(WeightedRandomListShim::create, WeightedRandomListShim::unwrap);
  }
  public boolean isEmpty() { return items.isEmpty(); }
  public List<E> unwrap() { return items; }
  public int totalWeight() { return totalWeight; }
  public Optional<E> getRandom(net.minecraft.util.RandomSource random) {
    if (totalWeight <= 0) return Optional.empty();
    int r = random.nextInt(totalWeight);
    for (E e : items) { r -= e.getWeight().asInt(); if (r < 0) return Optional.of(e); }
    return Optional.empty();
  }
  public E getRandomOrThrow(net.minecraft.util.RandomSource random) { return getRandom(random).orElseThrow(); }
  @Override public String toString() { return "WeightedRandomList" + items; }
}
