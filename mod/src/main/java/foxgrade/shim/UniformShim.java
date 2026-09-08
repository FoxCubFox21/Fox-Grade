package foxgrade.shim;

/** Stand-in for 1.21's {@code Uniform} (and {@code AbstractUniform}): a value slot on a shader program. 26.2 has no
 *  such thing, so every setter is a sink. {@link #SINK} exists so {@code safeGetUniform} can hand back something a
 *  caller may chain onto without a null check. */
public class UniformShim implements AutoCloseable {
  public static final UniformShim SINK = new UniformShim();

  public void set(float v) { }
  public void set(float a, float b) { }
  public void set(float a, float b, float c) { }
  public void set(float a, float b, float c, float d) { }
  public void set(int v) { }
  public void set(int a, int b) { }
  public void set(int a, int b, int c) { }
  public void set(int a, int b, int c, int d) { }
  public void set(float[] values) { }
  public void set(org.joml.Matrix4f m) { }
  public void set(org.joml.Matrix3f m) { }
  public void set(org.joml.Vector3f v) { }
  public void setSafe(float a, float b, float c, float d) { }
  public void setMat4x4(float[] values) { }
  public void upload() { }
  @Override public void close() { }
}
