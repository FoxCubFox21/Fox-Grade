package foxgrade.shim;

/** Type stand-in for 1.21's {@code ItemOverride} (item model predicate overrides); 26.2 item models are defined differently. */
public final class ItemOverrideShim {
  private final net.minecraft.resources.Identifier model; private final java.util.List<Predicate> predicates;
  public ItemOverrideShim(net.minecraft.resources.Identifier model, java.util.List<Predicate> predicates) { this.model = model; this.predicates = predicates; }
  public net.minecraft.resources.Identifier model() { return model; }
  public net.minecraft.resources.Identifier getModel() { return model; }
  public java.util.List<Predicate> predicates() { return predicates; }
  public java.util.stream.Stream<Predicate> getPredicates() { return predicates.stream(); }
  public static final class Predicate {
    private final net.minecraft.resources.Identifier property; private final float value;
    public Predicate(net.minecraft.resources.Identifier property, float value) { this.property = property; this.value = value; }
    public net.minecraft.resources.Identifier property() { return property; }
    public net.minecraft.resources.Identifier getProperty() { return property; }
    public float value() { return value; }
    public float getValue() { return value; }
  }
}
