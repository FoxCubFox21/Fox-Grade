package me.thosea.badoptimizations.utils.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import me.thosea.badoptimizations.config.Config;
import me.thosea.badoptimizations.hook.CacheHooks;
import me.thosea.badoptimizations.hook.HookCreator;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.CustomValue.CvObject;
import net.fabricmc.loader.api.metadata.CustomValue.CvType;

public final class PlatformMethodsImpl {
   private PlatformMethodsImpl() {
   }

   public static String getVersion() {
      return ownModContainer().getMetadata().getVersion().getFriendlyString();
   }

   public static Path getConfigFolder() {
      return FabricLoader.getInstance().getConfigDir();
   }

   public static boolean isModLoaded(String id) {
      return FabricLoader.getInstance().isModLoaded(id);
   }

   public static boolean isOnServer() {
      return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
   }

   public static InputStream streamConfigTemplate() throws IOException {
      return Files.newInputStream(
         (Path)ownModContainer().findPath("bo-config-template.txt").orElseThrow(() -> new RuntimeException("BadOptimizations config template not found"))
      );
   }

   private static ModContainer ownModContainer() {
      return (ModContainer)FabricLoader.getInstance()
         .getModContainer("badoptimizations")
         .orElseThrow(() -> new RuntimeException("BadOptimizations mod container not found"));
   }

   public static Map<String, List<String>> getModIncompatibilities() {
      Map<String, List<String>> result = new HashMap<>(1);

      for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
         ModMetadata meta = mod.getMetadata();
         String id = meta.getId();
         CustomValue object = meta.getCustomValue("badoptimizations:incompatibilities");
         if (object != null) {
            if (object.getType() != CvType.ARRAY) {
               Config.LOGGER.warn("Mod {} specifies invalid BadOptimizations incompatibilities, ignoring", id);
               Config.LOGGER.warn("JSON is not an array");
            } else {
               List<String> entries = new ArrayList<>();

               for (CustomValue entry : object.getAsArray()) {
                  if (entry != null && entry.getType() == CvType.STRING) {
                     entries.add(entry.getAsString());
                  } else {
                     Config.LOGGER.warn("Mod {} specifies invalid BadOptimizations incompatibilities", id);
                     Config.LOGGER.warn("JSON array contained non-string component");
                  }
               }

               result.put(id, entries);
            }
         }
      }

      return result;
   }

   public static List<CacheHooks.CacheHookEntry> getModCacheHooks() {
      List<CacheHooks.CacheHookEntry> result = new ArrayList<>();

      for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
         ModMetadata meta = mod.getMetadata();
         String modId = meta.getId();
         CustomValue value = meta.getCustomValue("badoptimizations:cache_hooks");
         if (value != null) {
            if (value.getType() != CvType.OBJECT) {
               Config.LOGGER.warn("Mod {} specifies invalid BadOptimizations caching hooks, ignoring", modId);
               Config.LOGGER.warn("JSON is not an object");
            } else {
               CvObject object = value.getAsObject();
               BooleanSupplier common = getEntry(modId, object, "common");
               BooleanSupplier lightmap = getEntry(modId, object, "lightmap");
               BooleanSupplier skyColor = getEntry(modId, object, "skycolor");
               if (common != null || lightmap != null || skyColor != null) {
                  CacheHooks.CacheHookEntry entry = new CacheHooks.CacheHookEntry(common, lightmap, skyColor);
                  result.add(entry);
               }
            }
         }
      }

      return result;
   }

   private static BooleanSupplier getEntry(String modId, CvObject object, String key) {
      CustomValue value = object.get(key);
      if (value == null) {
         return null;
      } else if (value.getType() != CvType.STRING) {
         Config.LOGGER.warn("Mod {} specifies an invalid BadOptimizations caching hook", modId);
         Config.LOGGER.warn("JSON key {} is not a string", key);
         return null;
      } else {
         String clazz = value.getAsString();
         BooleanSupplier hook = HookCreator.tryCreateHook(modId, clazz);
         if (hook != null) {
            Config.LOGGER.info("Mod {} added a {} caching hook: {}", new Object[]{modId, key, clazz});
         }

         return hook;
      }
   }
}
