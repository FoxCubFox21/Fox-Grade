package foxgrade.shim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * 1.21.x NBT getters returned a default for a missing key; 26.2 returns Optional and offers
 * explicit *Or variants. These pair each old call with the *Or form and the old default.
 */
public final class NbtCompat {
  private NbtCompat() { }
  public static boolean getBoolean(CompoundTag t, String k) { return t.getBooleanOr(k, false); }
  public static byte getByte(CompoundTag t, String k) { return t.getByteOr(k, (byte) 0); }
  public static short getShort(CompoundTag t, String k) { return t.getShortOr(k, (short) 0); }
  public static int getInt(CompoundTag t, String k) { return t.getIntOr(k, 0); }
  public static long getLong(CompoundTag t, String k) { return t.getLongOr(k, 0L); }
  public static float getFloat(CompoundTag t, String k) { return t.getFloatOr(k, 0f); }
  public static double getDouble(CompoundTag t, String k) { return t.getDoubleOr(k, 0d); }
  public static String getString(CompoundTag t, String k) { return t.getStringOr(k, ""); }
  public static CompoundTag getCompound(CompoundTag t, String k) { return t.getCompoundOrEmpty(k); }
  public static ListTag getList(CompoundTag t, String k, int type) { return t.getListOrEmpty(k); }
  public static int[] getIntArray(CompoundTag t, String k) { return t.getIntArray(k).orElse(new int[0]); }
  public static long[] getLongArray(CompoundTag t, String k) { return t.getLongArray(k).orElse(new long[0]); }
  public static byte[] getByteArray(CompoundTag t, String k) { return t.getByteArray(k).orElse(new byte[0]); }
  public static boolean contains(CompoundTag t, String k, int type) { return t.contains(k); }
  public static java.util.UUID getUUID(CompoundTag t, String k) { return t.read(k, net.minecraft.core.UUIDUtil.CODEC).orElse(null); }
  public static boolean hasUUID(CompoundTag t, String k) { return t.read(k, net.minecraft.core.UUIDUtil.CODEC).isPresent(); }
  public static void putUUID(CompoundTag t, String k, java.util.UUID id) { t.store(k, net.minecraft.core.UUIDUtil.CODEC, id); }
  public static CompoundTag getCompound(ListTag l, int i) { return l.getCompoundOrEmpty(i); }
  public static String getString(ListTag l, int i) { return l.getStringOr(i, ""); }
  public static int getInt(ListTag l, int i) { return l.getIntOr(i, 0); }
  public static float getFloat(ListTag l, int i) { return l.getFloatOr(i, 0f); }
  public static double getDouble(ListTag l, int i) { return l.getDoubleOr(i, 0d); }

  // ---- batch 3: container item lists (1.21.x CompoundTag + registry provider → 26.2 value IO)
  public static void loadAllItems(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items, net.minecraft.core.HolderLookup.Provider provider) {
    net.minecraft.world.ContainerHelper.loadAllItems(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, provider, tag), items);
  }
  public static net.minecraft.nbt.CompoundTag saveAllItems(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items, net.minecraft.core.HolderLookup.Provider provider) {
    net.minecraft.world.level.storage.TagValueOutput out = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
    net.minecraft.world.ContainerHelper.saveAllItems(out, items); tag.merge(out.buildResult()); return tag;
  }

  // ---- batch 4 (the fresh 1.21.1 set)
  public static String getAsString(net.minecraft.nbt.Tag tag) { return tag == null ? "" : tag.asString().orElse(tag.toString()); }
  public static void remove(net.minecraft.nbt.CompoundTag tag, String key) { tag.remove(key); }
  public static net.minecraft.nbt.Tag createUUID(java.util.UUID id) { return net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, id).getOrThrow(); }
  public static java.util.UUID loadUUID(net.minecraft.nbt.Tag tag) { return net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow(); }
  public static java.util.Optional<net.minecraft.core.BlockPos> readBlockPos(net.minecraft.nbt.CompoundTag tag, String key) {
    net.minecraft.nbt.Tag t = tag.get(key); return t == null ? java.util.Optional.empty() : net.minecraft.core.BlockPos.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, t).result();
  }
  public static net.minecraft.nbt.Tag writeBlockPos(net.minecraft.core.BlockPos pos) { return net.minecraft.core.BlockPos.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, pos).getOrThrow(); }
  public static net.minecraft.world.item.ItemStack parseOptional(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag tag) {
    if (tag == null || tag.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
    return net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.parse(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).result().orElse(net.minecraft.world.item.ItemStack.EMPTY);
  }
  public static net.minecraft.nbt.Tag saveOptional(net.minecraft.world.item.ItemStack stack, net.minecraft.core.HolderLookup.Provider provider) {
    if (stack.isEmpty()) return new net.minecraft.nbt.CompoundTag();
    return net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.encodeStart(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack).getOrThrow();
  }
  public static net.minecraft.nbt.CompoundTag saveModifier(net.minecraft.world.entity.ai.attributes.AttributeModifier m) { return (net.minecraft.nbt.CompoundTag) net.minecraft.world.entity.ai.attributes.AttributeModifier.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, m).getOrThrow(); }
  public static net.minecraft.world.entity.ai.attributes.AttributeModifier loadModifier(net.minecraft.nbt.CompoundTag tag) { return net.minecraft.world.entity.ai.attributes.AttributeModifier.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null); }
}
