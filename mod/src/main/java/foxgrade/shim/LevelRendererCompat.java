package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The static line/box/shape helpers 1.21.x mods called on LevelRenderer, as they were written then. */
public final class LevelRendererCompat {
  private LevelRendererCompat() { }

  public static void renderLineBox(PoseStack ps, VertexConsumer vc, AABB box, float r, float g, float b, float a) {
    renderLineBox(ps, vc, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, a, r, g, b);
  }
  public static void renderLineBox(VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a) {
    renderLineBox(new PoseStack(), vc, x1, y1, z1, x2, y2, z2, r, g, b, a, r, g, b);
  }
  public static void renderLineBox(PoseStack ps, VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a) {
    renderLineBox(ps, vc, x1, y1, z1, x2, y2, z2, r, g, b, a, r, g, b);
  }
  public static void renderLineBox(PoseStack ps, VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a, float xr, float yg, float zb) {
    Matrix4f m = ps.last().pose();
    float ax = (float) x1, ay = (float) y1, az = (float) z1, bx = (float) x2, by = (float) y2, bz = (float) z2;
    line(vc, m, ax, ay, az, bx, ay, az, xr, g, b, a, 1, 0, 0); line(vc, m, ax, ay, az, ax, by, az, r, yg, b, a, 0, 1, 0); line(vc, m, ax, ay, az, ax, ay, bz, r, g, zb, a, 0, 0, 1);
    line(vc, m, bx, ay, az, bx, by, az, r, g, b, a, 0, 1, 0); line(vc, m, bx, by, az, ax, by, az, r, g, b, a, -1, 0, 0); line(vc, m, ax, by, az, ax, by, bz, r, g, b, a, 0, 0, 1);
    line(vc, m, ax, by, bz, ax, ay, bz, r, g, b, a, 0, -1, 0); line(vc, m, ax, ay, bz, bx, ay, bz, r, g, b, a, 1, 0, 0); line(vc, m, bx, ay, bz, bx, ay, az, r, g, b, a, 0, 0, -1);
    line(vc, m, ax, by, bz, bx, by, bz, r, g, b, a, 1, 0, 0); line(vc, m, bx, ay, bz, bx, by, bz, r, g, b, a, 0, 1, 0); line(vc, m, bx, by, az, bx, by, bz, r, g, b, a, 0, 0, 1);
  }
  private static void line(VertexConsumer vc, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a, float nx, float ny, float nz) {
    Vector3f p = new Vector3f(x1, y1, z1).mulPosition(m), q = new Vector3f(x2, y2, z2).mulPosition(m);
    vc.addVertex(p.x, p.y, p.z).setColor((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255)).setNormal(nx, ny, nz);
    vc.addVertex(q.x, q.y, q.z).setColor((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255)).setNormal(nx, ny, nz);
  }
  public static void renderShape(PoseStack ps, VertexConsumer vc, VoxelShape shape, double x, double y, double z, float r, float g, float b, float a) {
    renderVoxelShape(ps, vc, shape, x, y, z, r, g, b, a, true);
  }
  public static void renderVoxelShape(PoseStack ps, VertexConsumer vc, VoxelShape shape, double x, double y, double z, float r, float g, float b, float a, boolean highContrast) {
    Matrix4f m = ps.last().pose();
    shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
      float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
      float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz); if (len == 0) len = 1;
      line(vc, m, (float) (x1 + x), (float) (y1 + y), (float) (z1 + z), (float) (x2 + x), (float) (y2 + y), (float) (z2 + z), r, g, b, a, dx / len, dy / len, dz / len);
    });
  }
  public static int getLightColor(BlockAndTintGetter level, BlockPos pos) { return LightCoordsUtil.getLightCoords(level, pos); }
  public static int getLightColor(BlockAndTintGetter level, BlockState state, BlockPos pos) { return LightCoordsUtil.getLightCoords(level, pos); }
}
