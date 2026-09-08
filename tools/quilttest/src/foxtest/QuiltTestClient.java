package foxtest;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer;

public class QuiltTestClient implements ClientModInitializer {
  @Override public void onInitializeClient(ModContainer mod) {
    System.out.println("[QuiltTest] client init " + mod.metadata().id() + " env=" + MinecraftQuiltLoader.getEnvironmentType()
        + " self=" + org.quiltmc.loader.api.QuiltLoader.getModContainer(QuiltTestClient.class).map(c -> c.metadata().id()).orElse("?"));
  }
}
