package foxgrade;

import net.fabricmc.api.ModInitializer;

/** Common-side init, client and server alike: anything a port needs registered before the registries freeze. */
public final class FoxGradeMain implements ModInitializer {
  @Override public void onInitialize() {
    // DataComponents.HIDE_ADDITIONAL_TOOLTIP is gone in 26.2; ports that used it get a Fox-Grade-owned Unit component.
    // It has to exist before the data-component registry freezes, which is long before any ported mod first touches it.
    // Shims are served from Fox-Grade's own jar (one copy for every port), so "a port is loaded" is the signal.
    boolean wanted = false;
    for (var mod : Loaders.current().mods()) if (mod.id().endsWith("_fgport")) { wanted = true; break; }
    if (!wanted) return;
    try {
      foxgrade.shim.ItemCompat.hideAdditionalTooltip();
      FoxGradePreLaunch.log("registered foxgrade:hide_additional_tooltip (a port reads DataComponents.HIDE_ADDITIONAL_TOOLTIP)");
    } catch (Throwable t) {
      FoxGradePreLaunch.log("hide_additional_tooltip registration failed: " + t);
    }
  }
}
