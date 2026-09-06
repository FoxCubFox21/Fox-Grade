package foxgrade.shim;

import net.minecraft.world.InteractionResult;

/** The InteractionResult constants, whose field types narrowed to subclasses in 26.2. */
public final class InteractionCompat {
  private InteractionCompat() { }
  public static InteractionResult SUCCESS() { return InteractionResult.SUCCESS; }
  public static InteractionResult SUCCESS_NO_ITEM_USED() { return InteractionResult.SUCCESS; }
  public static InteractionResult CONSUME() { return InteractionResult.CONSUME; }
  public static InteractionResult CONSUME_PARTIAL() { return InteractionResult.CONSUME; }
  public static InteractionResult PASS() { return InteractionResult.PASS; }
  public static InteractionResult FAIL() { return InteractionResult.FAIL; }
}
