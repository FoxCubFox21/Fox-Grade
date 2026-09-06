package foxgrade.shim;

/** {@code GlStateManager.DestFactor} (removed with the GL state calls): named GL blend constants. */
public final class DestFactorShim {
  public static final DestFactorShim CONSTANT_ALPHA = new DestFactorShim("CONSTANT_ALPHA", 32771), CONSTANT_COLOR = new DestFactorShim("CONSTANT_COLOR", 32769),
      DST_ALPHA = new DestFactorShim("DST_ALPHA", 772), DST_COLOR = new DestFactorShim("DST_COLOR", 774), ONE = new DestFactorShim("ONE", 1),
      ONE_MINUS_CONSTANT_ALPHA = new DestFactorShim("ONE_MINUS_CONSTANT_ALPHA", 32772), ONE_MINUS_CONSTANT_COLOR = new DestFactorShim("ONE_MINUS_CONSTANT_COLOR", 32770),
      ONE_MINUS_DST_ALPHA = new DestFactorShim("ONE_MINUS_DST_ALPHA", 773), ONE_MINUS_DST_COLOR = new DestFactorShim("ONE_MINUS_DST_COLOR", 775),
      ONE_MINUS_SRC_ALPHA = new DestFactorShim("ONE_MINUS_SRC_ALPHA", 771), ONE_MINUS_SRC_COLOR = new DestFactorShim("ONE_MINUS_SRC_COLOR", 769),
      SRC_ALPHA = new DestFactorShim("SRC_ALPHA", 770), SRC_ALPHA_SATURATE = new DestFactorShim("SRC_ALPHA_SATURATE", 776), SRC_COLOR = new DestFactorShim("SRC_COLOR", 768),
      ZERO = new DestFactorShim("ZERO", 0);
  public final int value;
  private final String name;
  private DestFactorShim(String name, int value) { this.name = name; this.value = value; }
  public String name() { return name; }
  @Override public String toString() { return name; }
}
