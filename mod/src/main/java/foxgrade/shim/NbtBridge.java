package foxgrade.shim;

import java.util.List;
import java.util.WeakHashMap;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 1.21.x entities saved through CompoundTag; 26.2 hands them a ValueOutput / ValueInput. A ported
 * override still fills a CompoundTag, which is then written into the real output field by field;
 * reads unwrap the CompoundTag behind the game's TagValueInput. super.addAdditionalSaveData(tag)
 * calls run against a fresh TagValueOutput whose result is merged into the same tag.
 */
public final class NbtBridge {
  private NbtBridge() { }
  private static final WeakHashMap<ValueOutput, CompoundTag> PENDING = new WeakHashMap<>();
  private static final WeakHashMap<CompoundTag, List<TagValueOutput>> SUPERS = new WeakHashMap<>();
  private static final ThreadLocal<HolderLookup.Provider> LOOKUP = new ThreadLocal<>();

  // --- synthesized addAdditionalSaveData(ValueOutput) ---
  public static CompoundTag tagForOutput(ValueOutput out) { CompoundTag t = new CompoundTag(); PENDING.put(out, t); return t; }
  public static void flushOutput(ValueOutput out) {
    CompoundTag t = PENDING.remove(out);
    if (t == null) return;
    List<TagValueOutput> supers = SUPERS.remove(t);
    if (supers != null) for (TagValueOutput s : supers) t.merge(s.buildResult());
    copyInto(t, out);
  }
  // --- super.addAdditionalSaveData(tag) inside the old method ---
  public static ValueOutput outputFor(CompoundTag tag) {
    TagValueOutput o = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
    SUPERS.computeIfAbsent(tag, k -> new java.util.ArrayList<>()).add(o);
    return o;
  }
  // --- synthesized readAdditionalSaveData(ValueInput) ---
  public static CompoundTag tagOfInput(ValueInput in) {
    try { LOOKUP.set(in.lookup()); } catch (Throwable ignore) { }
    try {
      for (var f : in.getClass().getDeclaredFields()) {
        if (f.getType() == CompoundTag.class) { f.setAccessible(true); Object v = f.get(in); if (v != null) return (CompoundTag) v; }
      }
    } catch (Throwable ignore) { }
    return new CompoundTag();
  }
  // --- super.readAdditionalSaveData(tag) inside the old method ---
  public static ValueInput inputFor(CompoundTag tag) {
    HolderLookup.Provider lookup = LOOKUP.get();
    if (lookup == null) lookup = net.minecraft.client.Minecraft.getInstance().level != null ? net.minecraft.client.Minecraft.getInstance().level.registryAccess() : null;
    return TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag);
  }
  public static ValueInput inputFor(CompoundTag tag, net.minecraft.world.level.Level level) {
    return TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
  }

  /** Merge what super-calls wrote into the tag now (for callers that keep using the tag). */
  public static void flushSupers(CompoundTag tag) { List<TagValueOutput> supers = SUPERS.remove(tag); if (supers != null) for (TagValueOutput s : supers) tag.merge(s.buildResult()); }

  public static void copyInto(CompoundTag tag, ValueOutput out) {
    for (String key : tag.keySet()) {
      Tag v = tag.get(key);
      if (v instanceof CompoundTag c) copyInto(c, out.child(key));
      else if (v instanceof StringTag s) out.putString(key, s.value());
      else if (v instanceof IntArrayTag a) out.putIntArray(key, a.getAsIntArray());
      else if (v instanceof LongArrayTag a) { long[] l = a.getAsLongArray(); List<Long> boxed = new java.util.ArrayList<>(); for (long x : l) boxed.add(x); out.store(key, Codec.LONG.listOf(), boxed); }
      else if (v instanceof ByteArrayTag a) { byte[] l = a.getAsByteArray(); List<Byte> boxed = new java.util.ArrayList<>(); for (byte x : l) boxed.add(x); out.store(key, Codec.BYTE.listOf(), boxed); }
      else if (v instanceof NumericTag n) {
        switch (n.getId()) {
          case Tag.TAG_BYTE -> out.putByte(key, n.byteValue());
          case Tag.TAG_SHORT -> out.putShort(key, (short) n.box().intValue());
          case Tag.TAG_INT -> out.putInt(key, n.box().intValue());
          case Tag.TAG_LONG -> out.putLong(key, n.box().longValue());
          case Tag.TAG_FLOAT -> out.putFloat(key, n.floatValue());
          default -> out.putDouble(key, n.doubleValue());
        }
      } else if (v instanceof ListTag l) {
        if (l.size() > 0 && l.get(0) instanceof CompoundTag) {
          ValueOutput.ValueOutputList list = out.childrenList(key);
          for (int i = 0; i < l.size(); i++) copyInto(l.getCompoundOrEmpty(i), list.addChild());
        } else if (l.size() > 0 && l.get(0) instanceof StringTag) {
          List<String> strs = new java.util.ArrayList<>(); for (int i = 0; i < l.size(); i++) strs.add(((StringTag) l.get(i)).value()); out.store(key, Codec.STRING.listOf(), strs);
        } else if (l.size() > 0 && l.get(0) instanceof NumericTag) {
          List<Double> nums = new java.util.ArrayList<>(); for (int i = 0; i < l.size(); i++) nums.add(((NumericTag) l.get(i)).doubleValue()); out.store(key, Codec.DOUBLE.listOf(), nums);
        }
      }
    }
  }

  /** The registry provider a 1.21.x save/load signature wants, from whatever owns the data. */
  public static net.minecraft.core.HolderLookup.Provider providerOf(Object owner) {
    if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity be && be.getLevel() != null) return be.getLevel().registryAccess();
    if (owner instanceof net.minecraft.world.entity.Entity e) return e.registryAccess();
    return null;   // no owner with a level: the codec falls back to plain NBT
  }
}
