package foxgrade.shim;

import java.util.HashMap;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;

public final class ModelCompat {
  private ModelCompat() { }
  /** A root for models 26.2 insists be constructed with one; children may be attached later. */
  public static ModelPart emptyRoot() { return new ModelPart(List.of(), new HashMap<>()); }

  public static java.util.stream.Stream<net.minecraft.client.model.geom.ModelPart> getAllParts(net.minecraft.client.model.geom.ModelPart part) { return part.getAllParts().stream(); }
  // 1.21.x Fabric ModelLoadingPlugin.Context.addModels(...): 26.2 registers extra models through typed keys; the old
  // "load these ids" form has no equivalent, so the ids are noted and the models stay unloaded (missing-model where used).
  private static boolean saidModels;
  private static void noteModels(int n) { if (!saidModels) { saidModels = true; System.err.println("[Fox-Grade] " + n + "+ extra models requested through the 1.21.x model-loading API are not loaded on 26.2 (typed extra-model keys now)"); } }
  public static void addModels(Object context, java.util.Collection<?> ids) { noteModels(ids == null ? 0 : ids.size()); }
  public static void addModels(Object context, net.minecraft.resources.Identifier[] ids) { noteModels(ids == null ? 0 : ids.length); }
}
