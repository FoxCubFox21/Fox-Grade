package foxgrade.shim;

/** Type stand-in for 1.21's {@code ShaderInstance}; 26.2's shader objects are not exposed to mods. Fields and casts of this
 *  type load; the old shader getters already hand back null. */
public class ShaderInstanceShim {
  public void apply() { }
  public void clear() { }
  public String getName() { return "foxgrade:missing"; }
}
