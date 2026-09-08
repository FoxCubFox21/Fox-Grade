package foxgrade.shim;

/** Stand-in for 1.21's {@code ShaderInstance}. 26.2 replaced hand-built shader programs with render pipelines and
 *  removed the whole type, along with {@code Uniform}. Mods that construct their own shader (Xaero's map mods build
 *  several) get an object that accepts every call and does nothing, so the mod loads and its non-shader features work;
 *  the shader effect itself is inert, which the port report states. */
public class ShaderInstanceShim implements AutoCloseable {
  private final String name;

  public ShaderInstanceShim() { this.name = "foxgrade:missing"; }
  public ShaderInstanceShim(Object resourceProvider, String name, Object vertexFormat) { this.name = name; }
  public ShaderInstanceShim(Object resourceProvider, net.minecraft.resources.Identifier id, Object vertexFormat) {
    this.name = id == null ? "foxgrade:missing" : id.toString();
  }

  public void apply() { }
  public void clear() { }
  @Override public void close() { }
  public String getName() { return name; }

  /** 1.21 handed out Uniform objects to push values at the shader; there is nothing to push to now. */
  public UniformShim getUniform(String uniformName) { return null; }
  public UniformShim safeGetUniform(String uniformName) { return UniformShim.SINK; }
  public void setSampler(String samplerName, Object sampler) { }
  public void setDefaultUniforms(Object mode, org.joml.Matrix4f modelView, org.joml.Matrix4f projection, Object window) { }
  public Object getVertexFormat() { return null; }
}
