package me.thosea.badoptimizations.other;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;

public final class PlayerModelRendererHolder {
   public static EntityRenderer<? extends Player, ?> WIDE_RENDERER;
   public static EntityRenderer<? extends Player, ?> SLIM_RENDERER;

   private PlayerModelRendererHolder() {
   }

   public static EntityRenderer<? extends Player, ?> forModel(PlayerModelType model) {
      if (model == PlayerModelType.WIDE) {
         return WIDE_RENDERER;
      } else {
         return model == PlayerModelType.SLIM ? SLIM_RENDERER : null;
      }
   }
}
