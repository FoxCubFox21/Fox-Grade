package foxgrade.shim;

import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;

/**
 * 1.21.x {@code HierarchicalModel} (removed): an EntityModel whose parts hang off one root. The
 * root now comes in through the constructor (the port passes the subclass's own root parameter);
 * the animation helpers bake keyframes lazily, once per definition.
 */
public abstract class HierarchicalModelShim extends EntityModel<EntityRenderState> {
  private final WeakHashMap<AnimationDefinition, KeyframeAnimation> baked = new WeakHashMap<>();

  public HierarchicalModelShim() { super(ModelCompat.emptyRoot()); }
  public HierarchicalModelShim(ModelPart root) { super(root); }
  public HierarchicalModelShim(Function<Identifier, RenderType> renderType) { super(ModelCompat.emptyRoot(), renderType); }
  public HierarchicalModelShim(ModelPart root, Function<Identifier, RenderType> renderType) { super(root, renderType); }

  private static java.lang.reflect.Method bakeMethod;
  private KeyframeAnimation bake(AnimationDefinition def) {
    return baked.computeIfAbsent(def, d -> {
      try {
        if (bakeMethod == null) { bakeMethod = KeyframeAnimation.class.getDeclaredMethod("bake", ModelPart.class, AnimationDefinition.class); bakeMethod.setAccessible(true); }
        return (KeyframeAnimation) bakeMethod.invoke(null, root(), d);
      } catch (Throwable t) { throw new IllegalStateException("keyframe bake unavailable", t); }
    });
  }
  public void animate(AnimationState state, AnimationDefinition def, float ageInTicks) { bake(def).apply(state, ageInTicks); }
  public void animate(AnimationState state, AnimationDefinition def, float ageInTicks, float speed) { bake(def).apply(state, ageInTicks, speed); }
  public void animateWalk(AnimationDefinition def, float limbSwing, float limbSwingAmount, float maxSpeed, float scale) { bake(def).applyWalk(limbSwing, limbSwingAmount, maxSpeed, scale); }
  public void applyStatic(AnimationDefinition def) { bake(def).applyStatic(); }
  public Optional<ModelPart> getAnyDescendantWithName(String name) {
    return root().getAllParts().stream().filter(p -> p.hasChild(name)).findFirst().map(p -> p.getChild(name));
  }

  /** 1.21.x {@code KeyframeAnimations.animate(model, def, millis, scale, vec)} entry. */
  public void animateMillis(AnimationDefinition def, long accumulatedMillis, float scale) { bake(def).apply(accumulatedMillis, scale); }
}
