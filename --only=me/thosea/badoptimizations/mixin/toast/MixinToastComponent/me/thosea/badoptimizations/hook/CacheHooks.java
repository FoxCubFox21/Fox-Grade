package me.thosea.badoptimizations.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import me.thosea.badoptimizations.config.Config;
import me.thosea.badoptimizations.utils.PlatformMethods;
import org.jetbrains.annotations.Nullable;

public final class CacheHooks {
   public static final String ROOT_KEY = "badoptimizations:cache_hooks";
   public static final String COMMON_KEY = "common";
   public static final String LIGHTMAP_KEY = "lightmap";
   public static final String SKYCOLOR_KEY = "skycolor";
   private static final BooleanSupplier[] COMMON_COLOR_HOOKS;
   private static final BooleanSupplier[] LIGHTMAP_HOOKS;
   private static final BooleanSupplier[] SKYCOLOR_HOOKS;

   private CacheHooks() {
   }

   public static void init() {
   }

   public static boolean invokeCommon() {
      if (Config.ignoreCacheHooks) {
         return false;
      } else {
         for (BooleanSupplier hook : COMMON_COLOR_HOOKS) {
            if (hook.getAsBoolean()) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean invokeLightmap() {
      if (Config.ignoreCacheHooks) {
         return false;
      } else {
         for (BooleanSupplier hook : LIGHTMAP_HOOKS) {
            if (hook.getAsBoolean()) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean invokeSkyColor() {
      if (Config.ignoreCacheHooks) {
         return false;
      } else {
         for (BooleanSupplier hook : SKYCOLOR_HOOKS) {
            if (hook.getAsBoolean()) {
               return true;
            }
         }

         return false;
      }
   }

   static {
      if (Config.ignoreCacheHooks) {
         Config.LOGGER.warn("Ignore mod cache hooks is enabled!");
         COMMON_COLOR_HOOKS = new BooleanSupplier[0];
         LIGHTMAP_HOOKS = new BooleanSupplier[0];
         SKYCOLOR_HOOKS = new BooleanSupplier[0];
      } else {
         List<BooleanSupplier> commonHooks = new ArrayList<>();
         List<BooleanSupplier> lightmapHooks = new ArrayList<>();
         List<BooleanSupplier> skyColorHooks = new ArrayList<>();
         PlatformMethods.getModCacheHooks().forEach(hook -> {
            if (hook.commonHook != null) {
               commonHooks.add(hook.commonHook);
            }

            if (hook.lightmapHook != null) {
               lightmapHooks.add(hook.lightmapHook);
            }

            if (hook.skyColorHook != null) {
               skyColorHooks.add(hook.skyColorHook);
            }
         });
         BooleanSupplier[] dummy = new BooleanSupplier[0];
         COMMON_COLOR_HOOKS = commonHooks.toArray(dummy);
         LIGHTMAP_HOOKS = lightmapHooks.toArray(dummy);
         SKYCOLOR_HOOKS = skyColorHooks.toArray(dummy);
      }
   }

   public record CacheHookEntry(@Nullable BooleanSupplier commonHook, @Nullable BooleanSupplier lightmapHook, @Nullable BooleanSupplier skyColorHook) {
   }
}
