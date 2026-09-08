package foxgrade.shim;

import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.List;

public final class QuiltModContainerImpl implements QuiltModContainerShim {
  private final ModContainer fabric;
  public QuiltModContainerImpl(ModContainer fabric) { this.fabric = fabric; }
  public ModContainer fabric() { return fabric; }
  @Override public QuiltModMetadataShim metadata() { return new QuiltModMetadataImpl(fabric.getMetadata()); }
  @Override public Path rootPath() { List<Path> roots = fabric.getRootPaths(); return roots.isEmpty() ? null : roots.get(0); }
  @Override public Path getPath(String file) { return fabric.findPath(file).orElseGet(() -> { Path r = rootPath(); return r == null ? null : r.resolve(file); }); }
  @Override public List<List<Path>> getSourcePaths() {
    try { return List.of(fabric.getOrigin().getPaths()); } catch (RuntimeException e) { return List.of(); }
  }
  @Override public ClassLoader getClassLoader() { return QuiltModContainerImpl.class.getClassLoader(); }
  @Override public String toString() { return "QuiltModContainer[" + fabric.getMetadata().getId() + "]"; }
}
