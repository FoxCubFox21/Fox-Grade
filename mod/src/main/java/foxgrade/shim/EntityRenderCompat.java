package foxgrade.shim;

import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Drives a 1.21.x entity renderer from 26.2's two-phase design. Extraction remembers the entity
 * and tick delta behind the render state; submit re-creates the old render(...) call with a
 * recording buffer source that flushes into the frame's collector afterwards.
 */
public final class EntityRenderCompat {
  private EntityRenderCompat() { }
  private record Extracted(Entity entity, float partial) { }
  private record Frame(EntityRenderState state, SubmitNodeCollector collector, CameraRenderState camera, RecordingBufferSource buffers) { }
  private static final WeakHashMap<EntityRenderState, Extracted> EXTRACTED = new WeakHashMap<>();
  private static final WeakHashMap<Entity, Frame> FRAMES = new WeakHashMap<>();
  private static final WeakHashMap<EntityRenderState, Frame> OPEN = new WeakHashMap<>();

  public static void remember(Object renderer, Entity entity, EntityRenderState state, float partial) { EXTRACTED.put(state, new Extracted(entity, partial)); }
  public static Entity entity(EntityRenderState s) { Extracted e = EXTRACTED.get(s); return e == null ? null : e.entity(); }
  public static float partial(EntityRenderState s) { Extracted e = EXTRACTED.get(s); return e == null ? 1f : e.partial(); }
  public static float yaw(EntityRenderState s) { Extracted e = EXTRACTED.get(s); return e == null || e.entity() == null ? 0f : Mth.rotLerp(e.partial(), e.entity().yRotO, e.entity().getYRot()); }
  public static int light(EntityRenderState s) { return s.lightCoords; }

  public static MultiBufferSourceShim begin(Object renderer, EntityRenderState s, PoseStack ps, SubmitNodeCollector c, CameraRenderState cam) {
    RecordingBufferSource buffers = new RecordingBufferSource(c, ps).makeCurrent();
    Frame f = new Frame(s, c, cam, buffers);
    Entity e = entity(s);
    if (e != null) FRAMES.put(e, f);
    OPEN.put(s, f);
    return buffers;
  }
  public static void end(EntityRenderState s) { Frame f = OPEN.remove(s); if (f != null) f.buffers().flush(); }

  // For a ported renderer's super.render(entity, …) → super.submit(state, pose, collector, camera).
  public static EntityRenderState state(Entity e) { Frame f = FRAMES.get(e); return f == null ? null : f.state(); }
  public static SubmitNodeCollector collector(Entity e) { Frame f = FRAMES.get(e); return f == null ? null : f.collector(); }
  public static CameraRenderState camera(Entity e) { Frame f = FRAMES.get(e); return f == null ? null : f.camera(); }

  public static EntityRenderState newState(Object renderer) { return renderer instanceof LivingEntityRenderer ? new LivingEntityRenderState() : new EntityRenderState(); }

  // ---- batch 3: super-calls from 1.21.x renderer overrides need a render state for the entity
  public static EntityRenderState stateOf(Entity e, Object renderer) { EntityRenderState s = state(e); return s != null ? s : newState(renderer); }
  public static LivingEntityRenderState livingState(Entity e) { EntityRenderState s = state(e); return s instanceof LivingEntityRenderState l ? l : new LivingEntityRenderState(); }
  public static double distSq(Entity e) { return net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(e); }
  public static float age(EntityRenderState s) { return s == null ? 0f : s.ageInTicks; }
  public static LivingEntity living(EntityRenderState s) { Entity e = entity(s); return e instanceof LivingEntity l ? l : null; }
  public static LivingEntityRenderState livingStateOf(Entity e, Object renderer) { EntityRenderState s = stateOf(e, renderer); return s instanceof LivingEntityRenderState l ? l : new LivingEntityRenderState(); }
}
