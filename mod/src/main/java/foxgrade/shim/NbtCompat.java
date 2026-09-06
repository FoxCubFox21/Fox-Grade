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
  public static CompoundTag getCompound(ListTag l, int i) { return l.getCompoundOrEmpty(i); }
  public static String getString(ListTag l, int i) { return l.getStringOr(i, ""); }
  public static int getInt(ListTag l, int i) { return l.getIntOr(i, 0); }
  public static float getFloat(ListTag l, int i) { return l.getFloatOr(i, 0f); }
  public static double getDouble(ListTag l, int i) { return l.getDoubleOr(i, 0d); }
}
