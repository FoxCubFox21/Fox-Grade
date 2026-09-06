package foxgrade.shim;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

public final class EffectsCompat {
  private EffectsCompat() { }
  public static Holder<MobEffect> confusion() { return MobEffects.NAUSEA; }

  public static Holder<MobEffect> harm() { return net.minecraft.world.effect.MobEffects.INSTANT_DAMAGE; }
  public static Holder<MobEffect> heal() { return net.minecraft.world.effect.MobEffects.INSTANT_HEALTH; }
  public static Holder<MobEffect> movementSpeed() { return net.minecraft.world.effect.MobEffects.SPEED; }
  public static Holder<MobEffect> movementSlowdown() { return net.minecraft.world.effect.MobEffects.SLOWNESS; }

  // ---- 1.20.1 shapes: effects were plain objects, not holders
  public static MobEffect effectOf(net.minecraft.world.effect.MobEffectInstance instance) { return instance.getEffect().value(); }
  public static boolean hasEffect(net.minecraft.world.entity.LivingEntity e, MobEffect effect) { return e.hasEffect(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect)); }
  public static net.minecraft.world.effect.MobEffectInstance getEffect(net.minecraft.world.entity.LivingEntity e, MobEffect effect) { return e.getEffect(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect)); }
}
