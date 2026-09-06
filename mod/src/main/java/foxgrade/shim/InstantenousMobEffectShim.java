package foxgrade.shim;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** 1.21.x {@code InstantenousMobEffect}: a MobEffect whose {@code isInstantaneous()} is true. */
public class InstantenousMobEffectShim extends MobEffect {
  public InstantenousMobEffectShim(MobEffectCategory category, int color) { super(category, color); }
  @Override public boolean isInstantaneous() { return true; }
}
