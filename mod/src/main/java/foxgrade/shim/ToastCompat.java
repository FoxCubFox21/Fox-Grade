package foxgrade.shim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public final class ToastCompat {
  private ToastCompat() { }
  public static SystemToast multiline(Minecraft mc, SystemToast.SystemToastId id, Component title, Component message) { return new SystemToast(id, title, message); }
}
