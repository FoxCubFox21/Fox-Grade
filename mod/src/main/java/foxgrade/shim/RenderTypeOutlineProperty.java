package foxgrade.shim;

/** 1.21's {@code RenderType.OutlineProperty}. Emitted into the port under the old nested name. */
public enum RenderTypeOutlineProperty {
  NONE("none"), IS_OUTLINE("is_outline"), AFFECTS_OUTLINE("affects_outline");

  private final String name;
  RenderTypeOutlineProperty(String name) { this.name = name; }
  @Override public String toString() { return name; }
}
