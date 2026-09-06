package foxgrade.shim;

/** {@code GlStateManager.SourceFactor} (removed with the GL state calls): named GL blend constants. */
public final class SourceFactorShim {
  public static final SourceFactorShim CONSTANT_ALPHA = new SourceFactorShim("CONSTANT_ALPHA", 32771), CONSTANT_COLOR = new SourceFactorShim("CONSTANT_COLOR", 32769),
      DST_ALPHA = new SourceFactorShim("DST_ALPHA", 772), DST_COLOR = new SourceFactorShim("DST_COLOR", 774), ONE = new SourceFactorShim("ONE", 1),
      ONE_MINUS_CONSTANT_ALPHA = new SourceFactorShim("ONE_MINUS_CONSTANT_ALPHA", 32772), ONE_MINUS_CONSTANT_COLOR = new SourceFactorShim("ONE_MINUS_CONSTANT_COLOR", 32770),
      ONE_MINUS_DST_ALPHA = new SourceFactorShim("ONE_MINUS_DST_ALPHA", 773), ONE_MINUS_DST_COLOR = new SourceFactorShim("ONE_MINUS_DST_COLOR", 775),
      ONE_MINUS_SRC_ALPHA = new SourceFactorShim("ONE_MINUS_SRC_ALPHA", 771), ONE_MINUS_SRC_COLOR = new SourceFactorShim("ONE_MINUS_SRC_COLOR", 769),
      SRC_ALPHA = new SourceFactorShim("SRC_ALPHA", 770), SRC_ALPHA_SATURATE = new SourceFactorShim("SRC_ALPHA_SATURATE", 776), SRC_COLOR = new SourceFactorShim("SRC_COLOR", 768),
      ZERO = new SourceFactorShim("ZERO", 0);
  public final int value;
  private final String name;
  private SourceFactorShim(String name, int value) { this.name = name; this.value = value; }
  public String name() { return name; }
  @Override public String toString() { return name; }
}
