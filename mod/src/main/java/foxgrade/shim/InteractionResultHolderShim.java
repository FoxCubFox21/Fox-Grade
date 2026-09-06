package foxgrade.shim;

import net.minecraft.world.InteractionResult;

/** 1.21.x InteractionResultHolder<T> (removed; the result alone is returned now). */
public final class InteractionResultHolderShim<T> {
  private final InteractionResult result;
  private final T object;
  public InteractionResultHolderShim(InteractionResult result, T object) { this.result = result; this.object = object; }
  public InteractionResult getResult() { return result; }
  public T getObject() { return object; }
  public static <T> InteractionResultHolderShim<T> success(T o) { return new InteractionResultHolderShim<>(InteractionResult.SUCCESS, o); }
  public static <T> InteractionResultHolderShim<T> consume(T o) { return new InteractionResultHolderShim<>(InteractionResult.CONSUME, o); }
  public static <T> InteractionResultHolderShim<T> pass(T o) { return new InteractionResultHolderShim<>(InteractionResult.PASS, o); }
  public static <T> InteractionResultHolderShim<T> fail(T o) { return new InteractionResultHolderShim<>(InteractionResult.FAIL, o); }
  public static <T> InteractionResultHolderShim<T> sidedSuccess(T o, boolean clientSide) { return new InteractionResultHolderShim<>(InteractionResult.SUCCESS, o); }
}
