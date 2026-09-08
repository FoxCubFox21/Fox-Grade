package foxtest;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

public class QuiltTestMod implements ModInitializer {
  @Override public void onInitialize(ModContainer mod) {
    System.out.println("[QuiltTest] init " + mod.metadata().id() + " v" + mod.metadata().version().raw()
        + " name=" + mod.metadata().name() + " gameDir=" + QuiltLoader.getGameDir().getFileName()
        + " mc=" + QuiltLoader.getNormalizedGameVersion() + " fabricApiLoaded=" + QuiltLoader.isModLoaded("fabric-api")
        + " root=" + (mod.rootPath() != null));
  }
}
