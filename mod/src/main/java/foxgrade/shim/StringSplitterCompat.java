package foxgrade.shim;

/** 1.21.x {@code StringSplitter.componentStyleAtWidth(...)}: 26.2 dropped the sequence-based lookup; "no style here". */
public final class StringSplitterCompat {
  private StringSplitterCompat() {}
  public static net.minecraft.network.chat.Style componentStyleAtWidth(net.minecraft.client.StringSplitter splitter, net.minecraft.util.FormattedCharSequence text, int x) { return null; }
  public static net.minecraft.network.chat.Style componentStyleAtWidth(net.minecraft.client.StringSplitter splitter, net.minecraft.network.chat.FormattedText text, int x) { return null; }
}
