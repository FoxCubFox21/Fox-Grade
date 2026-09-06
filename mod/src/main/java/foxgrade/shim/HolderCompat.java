package foxgrade.shim;

import net.minecraft.core.Holder;

/** Unwraps a constant that 26.2 turned into a {@link Holder} (SoundEvents.X → Holder.Reference). */
public final class HolderCompat {
  private HolderCompat() {}
  public static Object value(Holder<?> holder) { return holder == null ? null : holder.value(); }
}
