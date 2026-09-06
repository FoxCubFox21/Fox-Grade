package foxgrade.shim;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

public final class EffectsCompat {
  private EffectsCompat() { }
  public static Holder<MobEffect> confusion() { return MobEffects.NAUSEA; }
}
