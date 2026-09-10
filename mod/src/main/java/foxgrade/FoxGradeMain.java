package foxgrade;

import net.fabricmc.api.ModInitializer;

/** Common-side init, client and server alike: anything a port needs registered before the registries freeze. */
public final class FoxGradeMain implements ModInitializer {
  @Override public void onInitialize() {
    // A porter must never be the reason a game will not start. Everything below reports and steps aside:
    // a defect in Fox-Grade costs the user its features for one launch, never the launch itself.
    try {
      initialize();
    } catch (Throwable foxGradeFailedButTheGameShouldNot) {
      FoxGradePreLaunch.log("! Fox-Grade " + FoxGradePreLaunch.VERSION + " hit an internal error in its startup stage and stopped there.");
      FoxGradePreLaunch.log("  " + foxGradeFailedButTheGameShouldNot);
      FoxGradePreLaunch.log("  The game is starting normally; the port panel may be unavailable.");
      FoxGradePreLaunch.log("  Please report this at https://github.com/FoxCubFox21/fox-grade/issues — the trace follows.");
      foxGradeFailedButTheGameShouldNot.printStackTrace();
    }
  }

  private void initialize() {
    // DataComponents.HIDE_ADDITIONAL_TOOLTIP is gone in 26.2; ports that used it get a Fox-Grade-owned Unit component.
    // It has to exist before the data-component registry freezes, which is long before any ported mod first touches it.
    // Shims are served from Fox-Grade's own jar (one copy for every port), so "a port is loaded" is the signal.
    boolean wanted = false;
    for (var mod : Loaders.current().mods()) if (mod.id().endsWith("_fgport")) { wanted = true; break; }
    if (!wanted) return;
    // Only 26.x lost the component. On an older target it is still there under its own name, the shim is not needed,
    // and trying anyway just logs a failure that reads like a real one.
    if (!Targets.namespace(Loaders.current().gameVersion()).equals("official")) return;
    try {
      foxgrade.shim.ItemCompat.hideAdditionalTooltip();
      FoxGradePreLaunch.log("registered foxgrade:hide_additional_tooltip (a port reads DataComponents.HIDE_ADDITIONAL_TOOLTIP)");
    } catch (Throwable t) {
      FoxGradePreLaunch.log("hide_additional_tooltip registration failed: " + t);
    }
  }
}
