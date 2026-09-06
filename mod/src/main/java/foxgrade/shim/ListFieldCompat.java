package foxgrade.shim;

import java.util.WeakHashMap;
import net.minecraft.client.gui.components.AbstractSelectionList;

/**
 * The protected list fields and hooks 1.21.x subclasses used (headerHeight, itemHeight,
 * clickedHeader, setRenderHeader) have no 26.2 counterpart: entries size themselves. Reads and
 * writes are kept per list so a mod's own bookkeeping still adds up; the game is not involved.
 */
@SuppressWarnings("rawtypes")
public final class ListFieldCompat {
  private ListFieldCompat() { }
  private static final WeakHashMap<AbstractSelectionList, int[]> SIDE = new WeakHashMap<>();
  private static int[] of(AbstractSelectionList l) { return SIDE.computeIfAbsent(l, k -> new int[]{0, 0}); }
  // 26.2's children() hands out an unmodifiable view; 1.21.x code mutates the live list.
  private static java.lang.reflect.Field childrenField;
  @SuppressWarnings("unchecked")
  public static java.util.List<Object> children(AbstractSelectionList l) {
    try {
      if (childrenField == null) { childrenField = AbstractSelectionList.class.getDeclaredField("children"); childrenField.setAccessible(true); }
      return (java.util.List<Object>) childrenField.get(l);
    } catch (Throwable t) { return l.children(); }
  }
  public static int headerHeight(AbstractSelectionList l) { return of(l)[0]; }
  public static void setHeaderHeight(AbstractSelectionList l, int v) { of(l)[0] = v; }
  public static int itemHeight(AbstractSelectionList l) { return of(l)[1]; }
  public static void setItemHeight(AbstractSelectionList l, int v) { of(l)[1] = v; }
  public static boolean clickedHeader(AbstractSelectionList l, int x, int y) { return false; }
  public static void setRenderHeader(AbstractSelectionList l, boolean render, int height) { of(l)[0] = render ? height : 0; }
}
