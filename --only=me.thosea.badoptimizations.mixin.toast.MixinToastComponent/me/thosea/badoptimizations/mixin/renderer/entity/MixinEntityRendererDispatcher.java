package me.thosea.badoptimizations.mixin.renderer.entity;

import java.util.Map;
import java.util.Map.Entry;
import me.thosea.badoptimizations.interfaces.EntityMethods;
import me.thosea.badoptimizations.interfaces.EntityTypeMethods;
import me.thosea.badoptimizations.other.PlayerModelRendererHolder;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {EntityRenderDispatcher.class},
   priority = 700
)
public abstract class MixinEntityRendererDispatcher {
   @Shadow
   private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
   @Shadow
   private Map<PlayerModelType, EntityRenderer<? extends Player, ?>> mannequinRenderers;

   @Overwrite
   public <T extends Entity & EntityMethods> EntityRenderer<? super T, ?> getRenderer(T entity) {
      EntityRenderer<Entity, ?> renderer = entity.bo$getRenderer();
      return renderer != null ? renderer : this.bo$getOtherRenderer(entity);
   }

   private <T extends Entity & EntityMethods> EntityRenderer<? super T, ?> bo$getOtherRenderer(T entity) {
      if (entity instanceof ClientAvatarEntity player) {
         EntityRenderer<? extends Player, ?> renderer = this.mannequinRenderers.get(player.getSkin().model());
         return (EntityRenderer<? super T, ?>)(renderer != null ? renderer : this.mannequinRenderers.get(PlayerModelType.WIDE));
      } else {
         return (EntityRenderer<? super T, ?>)this.renderers.get(entity.getType());
      }
   }

   @Inject(
      method = {"onResourceManagerReload"},
      at = {@At("RETURN")}
   )
   private void afterReload(ResourceManager manager, CallbackInfo ci) {
      for (Entry<EntityType<?>, EntityRenderer<?, ?>> entry : this.renderers.entrySet()) {
         ((EntityTypeMethods)entry.getKey()).bo$setRenderer(entry.getValue());
      }

      PlayerModelRendererHolder.WIDE_RENDERER = this.mannequinRenderers.get(PlayerModelType.WIDE);
      PlayerModelRendererHolder.SLIM_RENDERER = this.mannequinRenderers.get(PlayerModelType.SLIM);
   }
}
