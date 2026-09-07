package foxgrade.shim;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The 1.21 Fabric networking packet factories (ServerConfigurationNetworking.createS2CPacket, ClientPlayNetworking
 *  .createC2SPacket, ...) that later Fabric API renamed or dropped: a custom-payload packet is all they ever built. */
public final class NetworkingCompat {
  private NetworkingCompat() { }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Packet createS2CPacket(CustomPacketPayload payload) { return new ClientboundCustomPayloadPacket(payload); }
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Packet createC2SPacket(CustomPacketPayload payload) { return new ServerboundCustomPayloadPacket(payload); }
}
