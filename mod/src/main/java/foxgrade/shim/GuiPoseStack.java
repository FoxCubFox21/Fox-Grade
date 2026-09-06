package foxgrade.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

/**
 * The PoseStack a 1.21.x mod gets from {@code GuiGraphics.pose()}. 26.2's GUI transform is a 2D
 * {@code Matrix3x2fStack}; this keeps the old 3D stack in step with it, so a mod's
 * push/translate/scale/pop drives the real GUI transform, and {@code last().pose()} — the 4×4
 * matrix mods hand to immediate-mode vertex calls — still reflects the same transforms.
 */
public final class GuiPoseStack extends PoseStack {
  private final Matrix3x2fStack m;

  GuiPoseStack(Matrix3x2fStack m) { this.m = m; }

  @Override public void pushPose() { super.pushPose(); m.pushMatrix(); }
  @Override public void popPose() { super.popPose(); m.popMatrix(); }
  @Override public void translate(float x, float y, float z) { super.translate(x, y, z); m.translate(x, y); }
  @Override public void translate(double x, double y, double z) { translate((float) x, (float) y, (float) z); }
  @Override public void scale(float x, float y, float z) { super.scale(x, y, z); m.scale(x, y); }
  @Override public void mulPose(Quaternionfc q) { super.mulPose(q); m.rotate(zAngle(q)); }
  @Override public void mulPose(Matrix4fc mat) {
    super.mulPose(mat);
    m.mul(new Matrix3x2f(mat.m00(), mat.m01(), mat.m10(), mat.m11(), mat.m30(), mat.m31()));
  }
  @Override public void rotateAround(Quaternionfc q, float x, float y, float z) { translate(x, y, z); mulPose(q); translate(-x, -y, -z); }
  @Override public void setIdentity() { super.setIdentity(); m.identity(); }

  private static float zAngle(Quaternionfc q) { return (float) (2.0 * Math.atan2(q.z(), q.w())); }
}
