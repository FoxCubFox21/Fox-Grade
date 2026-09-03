package me.thosea.badoptimizations.mixin.tick;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({Camera.class})
public final class MixinCamera {
   @Shadow
   @Final
   private Minecraft minecraft;

   @WrapOperation(
      method = {"tickFov"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/player/AbstractClientPlayer;getFieldOfViewModifier(ZF)F"
      )}
   )
   private float getPlayerFov(AbstractClientPlayer player, boolean firstPerson, float fovEffectScale, Operation<Float> original) {
      if (fovEffectScale == 0.0F) {
         return this.minecraft.options.getCameraType() == CameraType.FIRST_PERSON && player.isScoping() ? 0.1F : 1.0F;
      } else {
         return (Float)original.call(new Object[]{player, firstPerson, fovEffectScale});
      }
   }
}
