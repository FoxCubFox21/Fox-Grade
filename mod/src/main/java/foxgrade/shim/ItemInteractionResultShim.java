package foxgrade.shim;

import net.minecraft.world.InteractionResult;

/** 1.21.x {@code ItemInteractionResult}; 26.2 folded it back into {@link InteractionResult}.
 *  (No switch statements here: javac would emit a synthetic $1 class the injector doesn't carry.) */
public enum ItemInteractionResultShim {
  SUCCESS, CONSUME, CONSUME_PARTIAL, PASS_TO_DEFAULT_BLOCK_INTERACTION, SKIP_DEFAULT_BLOCK_INTERACTION, FAIL;

  public static ItemInteractionResultShim sidedSuccess(boolean clientSide) { return clientSide ? SUCCESS : CONSUME; }
  public boolean consumesAction() { return this == SUCCESS || this == CONSUME || this == CONSUME_PARTIAL; }
  public boolean indicateItemUse() { return this == SUCCESS || this == CONSUME; }
  public InteractionResult result() { return toInteractionResult(this); }
  /** Return-value converter used when a ported {@code useItemOn} override is called by 26.2. */
  public static InteractionResult toInteractionResult(ItemInteractionResultShim r) {
    if (r == null || r == SKIP_DEFAULT_BLOCK_INTERACTION) return InteractionResult.PASS;
    if (r == SUCCESS) return InteractionResult.SUCCESS;
    if (r == CONSUME || r == CONSUME_PARTIAL) return InteractionResult.CONSUME;
    if (r == PASS_TO_DEFAULT_BLOCK_INTERACTION) return InteractionResult.TRY_WITH_EMPTY_HAND;
    return InteractionResult.FAIL;
  }
  public static ItemInteractionResultShim fromInteractionResult(InteractionResult r) {
    if (r == InteractionResult.FAIL) return FAIL;
    if (r == InteractionResult.PASS) return SKIP_DEFAULT_BLOCK_INTERACTION;
    if (r == InteractionResult.TRY_WITH_EMPTY_HAND) return PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (r == InteractionResult.CONSUME) return CONSUME;
    return SUCCESS;
  }
}
