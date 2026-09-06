package foxgrade.shim;

import net.minecraft.network.FriendlyByteBuf;

/** {@code FriendlyByteBuf.writeDate/readDate} were dropped in 26.2; a date is its epoch millis on the wire. */
public final class BufCompat {
  private BufCompat() {}
  public static FriendlyByteBuf writeDate(FriendlyByteBuf buf, java.util.Date date) { buf.writeLong(date.getTime()); return buf; }
  public static java.util.Date readDate(FriendlyByteBuf buf) { return new java.util.Date(buf.readLong()); }
}
