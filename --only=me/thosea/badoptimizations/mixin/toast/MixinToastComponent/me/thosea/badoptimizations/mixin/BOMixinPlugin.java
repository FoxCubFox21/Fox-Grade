package me.thosea.badoptimizations.mixin;

import java.util.List;
import java.util.Set;
import me.thosea.badoptimizations.config.Config;
import me.thosea.badoptimizations.hook.CacheHooks;
import me.thosea.badoptimizations.utils.PlatformMethods;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class BOMixinPlugin implements IMixinConfigPlugin {
   private static final boolean isOnServer = PlatformMethods.isOnServer();

   public void onLoad(String mixinPackage) {
      if (!isOnServer) {
         Config.init();
         CacheHooks.init();
      }
   }

   public boolean shouldApplyMixin(String targetClassName, String mixin) {
      if (isOnServer) {
         return false;
      } else {
         mixin = mixin.substring("me.thosea.badoptimizations.mixin.".length());
         if (mixin.equals("tick.MixinLightmapExtractor") || mixin.equals("accessors.GameRendererAccessor") || mixin.equals("accessors.PlayerAccessor")) {
            return Config.lightmapCaching.effectiveValue;
         } else if (mixin.startsWith("debug.")) {
            return Config.debugRendererDisableIfNotNeeded.effectiveValue;
         } else if (mixin.equals("MixinParticleManager")) {
            return Config.particleManagerOptimization.effectiveValue;
         } else if (mixin.equals("MixinToastComponent")) {
            return Config.toastOptimizations.effectiveValue;
         } else if (mixin.equals("MixinWorldRenderer")) {
            return Config.skyAngleCaching.effectiveValue;
         } else if (mixin.startsWith("renderer.entity.")) {
            return Config.entityRendererCaching.effectiveValue;
         } else if (mixin.startsWith("renderer.blockentity.")) {
            return Config.blockEntityRendererCaching.effectiveValue;
         } else if (mixin.equals("tick.MixinCamera")) {
            return Config.removeRedundantFovCalcs.effectiveValue;
         } else if (mixin.equals("tick.MixinTutorial")) {
            return Config.removeTutorialIfNotDemo.effectiveValue;
         } else if (mixin.equals("MixinDebugHud_AddText")) {
            return Config.showF3Text;
         } else {
            throw new RuntimeException("No config option for mixin " + mixin);
         }
      }
   }

   public String getRefMapperConfig() {
      return null;
   }

   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   public List<String> getMixins() {
      return null;
   }

   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }
}
