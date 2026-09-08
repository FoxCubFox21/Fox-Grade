package foxgrade.shim;

public final class QuiltVersionImpl implements QuiltVersionShim {
  private final String raw;
  public QuiltVersionImpl(String raw) { this.raw = raw == null ? "" : raw; }
  @Override public String raw() { return raw; }
  @Override public int compareTo(QuiltVersionShim other) {
    try { return net.fabricmc.loader.api.Version.parse(raw).compareTo(net.fabricmc.loader.api.Version.parse(other.raw())); }
    catch (Exception e) { return raw.compareTo(other.raw()); }
  }
  @Override public String toString() { return raw; }
  @Override public boolean equals(Object o) { return o instanceof QuiltVersionShim v && v.raw().equals(raw); }
  @Override public int hashCode() { return raw.hashCode(); }
}
