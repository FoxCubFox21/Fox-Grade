package foxgrade.shim;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Stands in for Fabric's removed {@code PacketByteBufs}. */
public final class PacketByteBufsShim {
  private PacketByteBufsShim() {}
  public static FriendlyByteBuf create() { return new FriendlyByteBuf(Unpooled.buffer()); }
  public static FriendlyByteBuf empty() { return new FriendlyByteBuf(Unpooled.EMPTY_BUFFER); }
  public static FriendlyByteBuf copy(io.netty.buffer.ByteBuf buf) { return new FriendlyByteBuf(Unpooled.copiedBuffer(buf)); }
  public static FriendlyByteBuf duplicate(io.netty.buffer.ByteBuf buf) { return new FriendlyByteBuf(buf.duplicate()); }
  public static FriendlyByteBuf slice(io.netty.buffer.ByteBuf buf) { return new FriendlyByteBuf(buf.slice()); }
  public static FriendlyByteBuf readBytes(io.netty.buffer.ByteBuf buf, int length) { return new FriendlyByteBuf(buf.readBytes(length)); }
  public static FriendlyByteBuf readRetainedSlice(io.netty.buffer.ByteBuf buf, int length) { return new FriendlyByteBuf(buf.readRetainedSlice(length)); }
}
