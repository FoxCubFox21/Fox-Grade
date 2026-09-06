package foxgrade.shim;

import java.util.AbstractList;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/** A List that forwards every add to 26.2's tooltip consumer, so a 1.21.x appendHoverText keeps working. */
public final class TooltipListShim extends AbstractList<Component> {
  private final Consumer<Component> sink; private int count;
  public TooltipListShim(Consumer<Component> sink) { this.sink = sink; }
  @Override public boolean add(Component c) { sink.accept(c); count++; return true; }
  @Override public void add(int index, Component c) { add(c); }
  @Override public Component get(int index) { throw new IndexOutOfBoundsException("tooltip lines are write-only in 26.2"); }
  @Override public int size() { return 0; }
}
