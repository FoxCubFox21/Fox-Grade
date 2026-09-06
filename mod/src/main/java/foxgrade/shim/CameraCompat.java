package foxgrade.shim;

import net.minecraft.client.Camera;
import org.joml.Vector3f;

public final class CameraCompat {
  private CameraCompat() { }
  /** 1.21.x getLookVector() returned a mutable Vector3f; 26.2 exposes forwardVector() as Vector3fc. */
  public static Vector3f getLookVector(Camera c) { return new Vector3f(c.forwardVector()); }
  public static Vector3f getUpVector(Camera c) { return new Vector3f(c.upVector()); }
  public static Vector3f getLeftVector(Camera c) { return new Vector3f(c.leftVector()); }
}
