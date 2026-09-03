package me.thosea.badoptimizations.hook;

import java.util.function.BooleanSupplier;
import me.thosea.badoptimizations.config.Config;
import org.jetbrains.annotations.Nullable;

public final class HookCreator {
   public static final String INVALID_HOOK_MESSAGE = "Mod {} specifies an invalid BadOptimizations caching hook";

   private HookCreator() {
   }

   @Nullable
   public static BooleanSupplier tryCreateHook(String modId, String clazzName) {
      if (clazzName == null) {
         return null;
      } else {
         Class<?> clazz;
         try {
            clazz = Class.forName(clazzName);
         } catch (ClassNotFoundException var6) {
            Config.LOGGER.warn("Mod {} specifies an invalid BadOptimizations caching hook", modId);
            Config.LOGGER.warn("No class named \"{}\"", clazzName);
            return null;
         }

         if (!BooleanSupplier.class.isAssignableFrom(clazz)) {
            Config.LOGGER.warn("Mod {} specifies an invalid BadOptimizations caching hook", modId);
            Config.LOGGER.warn("Class {} does not implement java.util.function.BooleanSupplier", clazzName);
            return null;
         } else {
            try {
               return (BooleanSupplier)clazz.getConstructor().newInstance();
            } catch (NoSuchMethodException var4) {
               Config.LOGGER.warn("Mod {} specifies an invalid BadOptimizations caching hook", modId);
               Config.LOGGER.warn("Class {} does not have a public empty default constructor", clazzName);
               return null;
            } catch (Exception var5) {
               Config.LOGGER.warn("Failed to create caching hooks for mod {}", modId);
               Config.LOGGER.warn("", var5);
               return null;
            }
         }
      }
   }
}
