package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/** Fabric's 1.21.x WorldRenderContext (removed), served from the 26.2 level render context. */
public interface WorldRenderContextShim {
  LevelRenderer worldRenderer();
  PoseStack matrixStack();
  DeltaTracker tickCounter();
  boolean blockOutlines();
  Camera camera();
  GameRenderer gameRenderer();
  Matrix4f projectionMatrix();
  Matrix4f positionMatrix();
  ClientLevel world();
  ProfilerFiller profiler();
  boolean advancedTranslucency();
  MultiBufferSourceShim consumers();
  Frustum frustum();

  interface BlockOutlineContextShim {
    VertexConsumer vertexConsumer();
    Entity entity();
    double cameraX();
    double cameraY();
    double cameraZ();
    BlockPos blockPos();
    BlockState blockState();
  }
}
