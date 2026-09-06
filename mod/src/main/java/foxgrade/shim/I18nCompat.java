package foxgrade.shim;

import net.minecraft.locale.Language;

/** 1.21.x {@code I18n} helpers 26.2 dropped; the Language instance still answers all of them. */
public final class I18nCompat {
  private I18nCompat() { }
  public static boolean exists(String key) { return Language.getInstance().has(key); }
  public static String getOrDefault(String key) { return Language.getInstance().getOrDefault(key); }
  public static String getOrDefault(String key, String fallback) { return Language.getInstance().getOrDefault(key, fallback); }
}
