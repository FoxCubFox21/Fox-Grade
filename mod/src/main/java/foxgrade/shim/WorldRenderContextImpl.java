package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4f;

public final class WorldRenderContextImpl implements WorldRenderContextShim {
  // Everything the events class touches is public: after injection the two live in different packages.
  public final LevelRenderContext ctx;
  public final RecordingBufferSource buffers;
  public final PoseStack pose;
  public static Camera camera;
  public static DeltaTracker deltaTracker;
  public static ClientLevel level;

  public WorldRenderContextImpl(LevelRenderContext ctx) {
    this.ctx = ctx;
    this.pose = ctx == null ? new PoseStack() : ctx.poseStack();
    this.buffers = new RecordingBufferSource(ctx == null ? null : ctx.submitNodeCollector(), pose).makeCurrent();
  }
  public void flush() { buffers.flush(); }

  @Override public LevelRenderer worldRenderer() { return ctx != null ? ctx.levelRenderer() : Minecraft.getInstance().levelRenderer; }
  @Override public PoseStack matrixStack() { return pose; }
  @Override public DeltaTracker tickCounter() { return deltaTracker != null ? deltaTracker : Minecraft.getInstance().getDeltaTracker(); }
  @Override public boolean blockOutlines() { return true; }
  @Override public Camera camera() { return camera; }
  @Override public GameRenderer gameRenderer() { return ctx != null ? ctx.gameRenderer() : Minecraft.getInstance().gameRenderer; }
  @Override public Matrix4f projectionMatrix() { return ctx != null ? ctx.levelState().cameraRenderState.projectionMatrix : new Matrix4f(); }
  @Override public Matrix4f positionMatrix() { return pose.last().pose(); }
  @Override public ClientLevel world() { return level != null ? level : Minecraft.getInstance().level; }
  @Override public ProfilerFiller profiler() { return Profiler.get(); }
  @Override public boolean advancedTranslucency() { return false; }
  @Override public MultiBufferSourceShim consumers() { return buffers; }
  @Override public Frustum frustum() { return ctx != null ? ctx.levelState().cameraRenderState.cullFrustum : null; }

  public BlockOutlineContextShim outline(BlockHitResult hit) {
    ClientLevel lvl = world();
    BlockPos pos = hit.getBlockPos();
    BlockState state = lvl == null ? null : lvl.getBlockState(pos);
    VertexConsumer vc = buffers.getBuffer(RenderTypes.lines());
    Entity cam = Minecraft.getInstance().getCameraEntity();
    double cx = camera == null ? 0 : camera.position().x, cy = camera == null ? 0 : camera.position().y, cz = camera == null ? 0 : camera.position().z;
    return new BlockOutlineContextShim() {
      @Override public VertexConsumer vertexConsumer() { return vc; }
      @Override public Entity entity() { return cam; }
      @Override public double cameraX() { return cx; }
      @Override public double cameraY() { return cy; }
      @Override public double cameraZ() { return cz; }
      @Override public BlockPos blockPos() { return pos; }
      @Override public BlockState blockState() { return state; }
    };
  }
}
