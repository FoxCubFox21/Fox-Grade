package me.thosea.badoptimizations.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.thosea.badoptimizations.utils.PlatformMethods;

public final class ModIncompatibilities {
   public static final String KEY = "badoptimizations:incompatibilities";
   private final Map<String, List<ModIncompatibilities.IncompatibleMod>> incompats = new HashMap<>();
   private boolean ignoreIncompatibilities;

   public ModIncompatibilities() {
      this.builtIn("enable_entity_renderer_caching", "twilightforest", "bedrockskinutility", "lazyyyyy");
      this.builtIn("enable_block_entity_renderer_caching", "lazyyyyy");
      this.builtIn("enable_sky_color_caching", "polytone");
      this.builtIn("enable_lightmap_caching", "polytone");
      this.builtIn("enable_entity_flag_caching", "biomeswevegone", "performant");
      this.builtIn("enable_remove_redundant_fov_calculations", "camera_lock_on");
      PlatformMethods.getModIncompatibilities().forEach((id, options) -> {
         ModIncompatibilities.IncompatibleMod mod = new ModIncompatibilities.IncompatibleMod(false, id);
         options.forEach(option -> this.getList(option).add(mod));
      });
   }

   private void builtIn(String option, String... mods) {
      for (String mod : mods) {
         if (PlatformMethods.isModLoaded(mod)) {
            this.getList(option).add(new ModIncompatibilities.IncompatibleMod(true, mod));
         }
      }
   }

   private List<ModIncompatibilities.IncompatibleMod> getList(String option) {
      return this.incompats.computeIfAbsent(option, k -> new ArrayList<>());
   }

   public void ignoreIncompatibilities() {
      this.ignoreIncompatibilities = true;
      Config.LOGGER.warn("Ignore incompatibilities is enabled!");
   }

   public boolean isIncompatible(String option) {
      if (this.ignoreIncompatibilities) {
         return false;
      } else {
         List<ModIncompatibilities.IncompatibleMod> list = this.incompats.get(option);
         if (list == null) {
            return false;
         } else {
            for (ModIncompatibilities.IncompatibleMod mod : list) {
               if (mod.builtIn) {
                  Config.LOGGER.info("Disabling {} because mod \"{}\" is incompatible with it (built-in)", option, mod.mod);
               } else {
                  Config.LOGGER.info("Disabling {} because mod \"{}\" marks itself as incompatible with it (external)", option, mod.mod);
               }
            }

            return true;
         }
      }
   }

   private record IncompatibleMod(boolean builtIn, String mod) {
   }
}
