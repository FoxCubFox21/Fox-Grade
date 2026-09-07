package foxgrade.shim;

/** Fields that were a {@code File} in 1.21 and are a {@code Path} in 26.2 (SavedDataStorage.dataFolder): read as Path, hand back the File. */
public final class IoCompat {
  private IoCompat() {}
  public static java.io.File toFile(java.nio.file.Path p) { return p == null ? null : p.toFile(); }
  public static java.nio.file.Path toPath(java.io.File f) { return f == null ? null : f.toPath(); }
}
