package foxgrade.shim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.Identifier;

/** Stands in for 1.21's {@code net.minecraft.client.gui.GuiSpriteManager}. 26.2 folded the GUI sprite atlas into the
 *  shared {@code AtlasManager}; the manager object mods used to fetch from {@code Minecraft.getGuiSprites()} is gone,
 *  but every sprite it served is still in the "gui" atlas. */
public class GuiSpriteManagerShim {
  private static final GuiSpriteManagerShim INSTANCE = new GuiSpriteManagerShim();
  private static final Identifier GUI_ATLAS = lookupGuiAtlas();

  public static GuiSpriteManagerShim of(Minecraft mc) { return INSTANCE; }

  public TextureAtlasSprite getSprite(Identifier id) {
    return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(GUI_ATLAS).getSprite(id);
  }

  public GuiSpriteScaling getSpriteScaling(TextureAtlasSprite sprite) {
    return sprite.contents().getAdditionalMetadata(GuiMetadataSection.TYPE)
        .map(m -> ((GuiMetadataSection) m).scaling()).orElse(GuiMetadataSection.DEFAULT.scaling());
  }

  private static Identifier lookupGuiAtlas() {
    try {
      Class<?> ids = Class.forName("net.minecraft.data.AtlasIds");
      for (java.lang.reflect.Field f : ids.getFields())
        if (f.getName().equals("GUI") && Identifier.class.isAssignableFrom(f.getType())) return (Identifier) f.get(null);
    } catch (Throwable ignored) { }
    return Identifier.withDefaultNamespace("gui");
  }
}
