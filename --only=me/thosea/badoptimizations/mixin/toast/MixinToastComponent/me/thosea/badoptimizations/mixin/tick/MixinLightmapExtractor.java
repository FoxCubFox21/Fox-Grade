package me.thosea.badoptimizations.mixin.tick;

import me.thosea.badoptimizations.hook.CacheHooks;
import me.thosea.badoptimizations.mixin.accessors.GameRendererAccessor;
import me.thosea.badoptimizations.mixin.accessors.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LightmapRenderStateExtractor.class})
public abstract class MixinLightmapExtractor {
   @Shadow
   @Final
   private Minecraft minecraft;
   private EnvironmentAttributeProbe bo$probe;
   private GameRendererAccessor bo$gameRendererAccessor;
   private int bo$lastSkyColor;
   private float bo$lastSkyFactor;
   private float bo$lastEndFactor = 0.0F;
   private double bo$lastGamma;
   private DimensionType bo$lastDimension;
   private boolean bo$lastNightVision;
   private boolean bo$lastConduitPower;
   private float bo$previousSkyDarkness;

   @Inject(
      method = {"<init>"},
      at = {@At("TAIL")}
   )
   private void onInit(GameRenderer renderer, Minecraft client, CallbackInfo ci) {
      this.bo$gameRendererAccessor = (GameRendererAccessor)renderer;
      this.bo$probe = renderer.mainCamera().attributeProbe();
   }

   @Inject(
      method = {"tick"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void onTick(CallbackInfo ci) {
      if (this.minecraft.player != null) {
         if (!this.bo$isDirty()) {
            ci.cancel();
         }
      }
   }

   private boolean bo$isDirty() {
      int skyColor = (Integer)this.bo$probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, 1.0F);
      float skyFactor = (Float)this.bo$probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1.0F);
      if (this.bo$lastSkyColor == skyColor && this.bo$lastSkyFactor == skyFactor) {
         if (this.minecraft.player.isUnderWater() && ((PlayerAccessor)this.minecraft.player).bo$underwaterVisibilityTicks() < 600) {
            return true;
         } else {
            if (!(Boolean)this.minecraft.options.hideLightningFlash().get()) {
               EndFlashState flash = this.minecraft.level.endFlashState();
               if (flash != null) {
                  float factor = flash.getIntensity(this.minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                  if (this.bo$lastEndFactor != factor) {
                     this.bo$lastEndFactor = factor;
                     return true;
                  }
               }
            }

            MobEffectInstance nightVision = this.minecraft.player.getEffect(MobEffects.NIGHT_VISION);
            boolean hasNightVision = nightVision != null;
            if (this.bo$lastNightVision != hasNightVision) {
               this.bo$lastNightVision = hasNightVision;
               return true;
            } else if (nightVision != null && nightVision.endsWithin(200)) {
               return true;
            } else if (this.minecraft.player.hasEffect(MobEffects.DARKNESS)) {
               return true;
            } else {
               boolean conduitPower = this.minecraft.player.hasEffect(MobEffects.CONDUIT_POWER);
               if (this.bo$lastConduitPower != conduitPower) {
                  this.bo$lastConduitPower = conduitPower;
                  return true;
               } else {
                  DimensionType dimension = this.minecraft.level.dimensionType();
                  if (this.bo$lastDimension != dimension) {
                     this.bo$lastDimension = dimension;
                     return true;
                  } else {
                     float skyDarkness = this.bo$gameRendererAccessor.bo$getSkyDarkness();
                     if (this.bo$previousSkyDarkness != skyDarkness) {
                        this.bo$previousSkyDarkness = skyDarkness;
                        return true;
                     } else {
                        double gamma = (Double)this.minecraft.options.gamma().get();
                        if (this.bo$lastGamma != gamma) {
                           this.bo$lastGamma = gamma;
                           return true;
                        } else {
                           return CacheHooks.invokeCommon() || CacheHooks.invokeLightmap();
                        }
                     }
                  }
               }
            }
         }
      } else {
         this.bo$lastSkyColor = skyColor;
         this.bo$lastSkyFactor = skyFactor;
         return true;
      }
   }
}
