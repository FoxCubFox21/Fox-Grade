package foxgrade.shim;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** 1.21.x chat events were (action, value) pairs; 26.2 makes them typed records. */
public final class ChatCompat {
  private ChatCompat() {}
  public static ClickEvent clickEvent(ClickEvent.Action action, String value) {
    if (action == ClickEvent.Action.OPEN_URL) { try { return new ClickEvent.OpenUrl(java.net.URI.create(value)); } catch (IllegalArgumentException e) { return new ClickEvent.SuggestCommand(value); } }
    if (action == ClickEvent.Action.RUN_COMMAND) return new ClickEvent.RunCommand(value);
    if (action == ClickEvent.Action.SUGGEST_COMMAND) return new ClickEvent.SuggestCommand(value);
    if (action == ClickEvent.Action.COPY_TO_CLIPBOARD) return new ClickEvent.CopyToClipboard(value);
    if (action == ClickEvent.Action.OPEN_FILE) return new ClickEvent.OpenFile(value);
    if (action == ClickEvent.Action.CHANGE_PAGE) { try { return new ClickEvent.ChangePage(Integer.parseInt(value.trim())); } catch (NumberFormatException e) { return new ClickEvent.ChangePage(1); } }
    return new ClickEvent.SuggestCommand(value);
  }
  /** 1.21.x {@code ClickEvent.getValue()}: the string behind whichever record this is. */
  public static String clickValue(ClickEvent event) {
    if (event instanceof ClickEvent.OpenUrl e) return e.uri().toString();
    if (event instanceof ClickEvent.RunCommand e) return e.command();
    if (event instanceof ClickEvent.SuggestCommand e) return e.command();
    if (event instanceof ClickEvent.CopyToClipboard e) return e.value();
    if (event instanceof ClickEvent.ChangePage e) return Integer.toString(e.page());
    return String.valueOf(event);
  }
  public static HoverEvent hoverEvent(HoverEvent.Action action, Object value) {
    if (value instanceof Component c) return new HoverEvent.ShowText(c);
    if (value instanceof net.minecraft.world.item.ItemStack s) return new HoverEvent.ShowText(s.getHoverName());
    return new HoverEvent.ShowText(Component.literal(String.valueOf(value)));
  }
  public static Object hoverValue(HoverEvent event, HoverEvent.Action action) {
    if (event instanceof HoverEvent.ShowText t) return t.value();
    return null;
  }
  public static Style withFont(Style style, Identifier font) { return style.withFont(new FontDescription.Resource(font)); }
}
