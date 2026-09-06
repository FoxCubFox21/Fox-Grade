package foxgrade.shim;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** 1.21.x {@code net.minecraft.world.entity.Saddleable}; 26.2 moved saddles to the equipment system.
 *  Mods implement it; nothing in the game calls it, so the interface only has to exist. */
public interface SaddleableShim {
  boolean isSaddleable();
  void equipSaddle(ItemStack stack, SoundSource source);
  boolean isSaddled();
  default SoundEvent getSaddleSoundEvent() { return net.minecraft.sounds.SoundEvents.HORSE_SADDLE.value(); }
}
