package foxgrade.shim;

import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Same bridge as EntityRenderCompat, for block entity renderers. */
public final class BlockEntityRenderCompat {
  private BlockEntityRenderCompat() { }
  private record Extracted(BlockEntity blockEntity, float partial) { }
  private static final WeakHashMap<BlockEntityRenderState, Extracted> EXTRACTED = new WeakHashMap<>();
  private static final WeakHashMap<BlockEntityRenderState, RecordingBufferSource> OPEN = new WeakHashMap<>();

  public static void remember(Object renderer, BlockEntity be, BlockEntityRenderState state, float partial, Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
    BlockEntityRenderState.extractBase(be, state, crumbling);
    EXTRACTED.put(state, new Extracted(be, partial));
  }
  public static BlockEntity blockEntity(BlockEntityRenderState s) { Extracted e = EXTRACTED.get(s); return e == null ? null : e.blockEntity(); }
  public static float partial(BlockEntityRenderState s) { Extracted e = EXTRACTED.get(s); return e == null ? 1f : e.partial(); }
  public static int light(BlockEntityRenderState s) { return s.lightCoords; }
  public static int overlay() { return OverlayTexture.NO_OVERLAY; }
  public static MultiBufferSourceShim begin(Object renderer, BlockEntityRenderState s, PoseStack ps, SubmitNodeCollector c, CameraRenderState cam) {
    RecordingBufferSource buffers = new RecordingBufferSource(c, ps).makeCurrent(); OPEN.put(s, buffers); return buffers;
  }
  public static void end(BlockEntityRenderState s) { RecordingBufferSource b = OPEN.remove(s); if (b != null) b.flush(); }
  public static BlockEntityRenderState newState(Object renderer) { return new BlockEntityRenderState(); }
}
