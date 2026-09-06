package foxgrade.shim;

import java.util.HashMap;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;

public final class ModelCompat {
  private ModelCompat() { }
  /** A root for models 26.2 insists be constructed with one; children may be attached later. */
  public static ModelPart emptyRoot() { return new ModelPart(List.of(), new HashMap<>()); }
}
