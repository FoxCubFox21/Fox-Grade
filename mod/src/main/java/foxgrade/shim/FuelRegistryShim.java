package foxgrade.shim;

/** 1.21.x Fabric {@code FuelRegistry}: burn times are data-driven on 26.2; registrations are noted and ignored. */
public interface FuelRegistryShim {
  FuelRegistryShim INSTANCE = new Impl();
  Integer get(net.minecraft.world.item.ItemStack stack);
  void add(net.minecraft.world.level.ItemLike item, Integer cookTime);
  void add(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag, Integer cookTime);
  void remove(net.minecraft.world.level.ItemLike item);
  void remove(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag);
  void clear(net.minecraft.world.level.ItemLike item);
  void clear(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag);

  final class Impl implements FuelRegistryShim {
    private boolean said;
    private void note() { if (!said) { said = true; System.err.println("[Fox-Grade] FuelRegistry: fuel values are data-driven on 26.2; registrations from a 1.21.x mod are ignored"); } }
    @Override public Integer get(net.minecraft.world.item.ItemStack stack) { return null; }
    @Override public void add(net.minecraft.world.level.ItemLike item, Integer cookTime) { note(); }
    @Override public void add(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag, Integer cookTime) { note(); }
    @Override public void remove(net.minecraft.world.level.ItemLike item) { note(); }
    @Override public void remove(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) { note(); }
    @Override public void clear(net.minecraft.world.level.ItemLike item) { note(); }
    @Override public void clear(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) { note(); }
  }
}
