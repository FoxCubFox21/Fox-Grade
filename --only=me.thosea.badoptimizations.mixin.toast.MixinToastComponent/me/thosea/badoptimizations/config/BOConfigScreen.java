package me.thosea.badoptimizations.config;

import java.nio.file.Files;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class BOConfigScreen extends Screen {
   private final Screen parent;

   public BOConfigScreen(Screen parent) {
      super(Component.empty());
      this.parent = parent;
   }

   protected void init() {
      if (!Files.exists(Config.FILE)) {
         try {
            Config.writeConfig();
         } catch (Exception var2) {
            throw new RuntimeException("Failed to generate BadOptimizations config", var2);
         }
      }

      Util.getPlatform().openUri(Config.FILE.toUri());
      this.minecraft.setScreenAndShow(this.parent);
   }
}
