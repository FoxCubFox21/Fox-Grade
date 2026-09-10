// Client entrypoint.
//
// Deliberately empty of Minecraft types. Fabric loads this class to construct the entrypoint, and a class is linked
// before any of its code runs: an earlier version of this file held the panel itself and died with
// NoClassDefFoundError: Screen on 1.21.2, at Class.forName, before the first statement. The panel lives in
// ClientPanel now, named here only inside the branch that has already established the target speaks Mojang names.
//
// Porting is unaffected either way. The engine is bytecode work and touches no Minecraft class, so it runs on every
// supported target; the panel and the in-game autotest are 26.x-only.
package foxgrade;

import net.fabricmc.api.ClientModInitializer;

public final class FoxGradeClient implements ClientModInitializer {
  @Override public void onInitializeClient() {
    // A porter must never be the reason a game will not start. Everything below reports and steps aside:
    // a defect in Fox-Grade costs the user its features for one launch, never the launch itself.
    try {
      initializeClient();
    } catch (Throwable foxGradeFailedButTheGameShouldNot) {
      FoxGradePreLaunch.log("! Fox-Grade " + FoxGradePreLaunch.VERSION + " hit an internal error in its client stage and stopped there.");
      FoxGradePreLaunch.log("  " + foxGradeFailedButTheGameShouldNot);
      FoxGradePreLaunch.log("  The game is starting normally; the F8 panel may be unavailable.");
      FoxGradePreLaunch.log("  Please report this at https://github.com/FoxCubFox21/fox-grade/issues — the trace follows.");
      foxGradeFailedButTheGameShouldNot.printStackTrace();
    }
  }

  private void initializeClient() {
    String mc = Loaders.current().gameVersion();
    if (!Targets.namespace(mc).equals("official")) {
      System.err.println("[Fox-Grade] panel and in-game autotest are 26.x-only; on " + mc
          + " porting works and the panel stays closed");
      return;
    }
    new ClientPanel().install();
  }
}
