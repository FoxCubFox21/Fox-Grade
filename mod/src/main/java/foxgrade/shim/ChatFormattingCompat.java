package foxgrade.shim;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;

/**
 * The query methods 1.21.x {@code ChatFormatting} had (getColor, getChar, isColor …) and 26.2
 * removed. Colours come from TextColor's own legacy table, never a hard-coded palette; the enum
 * order (sixteen colours first, then formats, RESET last) is what the id/isColor answers rely on.
 */
public final class ChatFormattingCompat {
  private ChatFormattingCompat() { }
  public static char getChar(ChatFormatting f) { return f.toString().charAt(1); }
  public static String getName(ChatFormatting f) { return f.name().toLowerCase(Locale.ROOT); }
  public static String getSerializedName(ChatFormatting f) { return getName(f); }
  public static boolean isColor(ChatFormatting f) { return f.ordinal() < 16; }
  public static boolean isFormat(ChatFormatting f) { return f.ordinal() >= 16 && f != ChatFormatting.RESET; }
  public static int getId(ChatFormatting f) { return isColor(f) ? f.ordinal() : -1; }
  public static Integer getColor(ChatFormatting f) { TextColor c = TextColor.fromLegacyFormat(f); return c == null ? null : c.getValue(); }
  public static ChatFormatting getById(int id) { ChatFormatting[] v = ChatFormatting.values(); return id >= 0 && id < 16 && id < v.length ? v[id] : null; }
  public static ChatFormatting getByName(String name) {
    if (name == null) return null;
    String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    for (ChatFormatting f : ChatFormatting.values()) if (getName(f).replace("_", "").equals(n)) return f;
    return null;
  }
}
