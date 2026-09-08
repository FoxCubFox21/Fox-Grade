package foxgrade.shim;

import java.nio.file.Path;

/** Quilt Loader's {@code org.quiltmc.loader.api.ModContainer}, over a Fabric mod container. */
public interface QuiltModContainerShim {
  QuiltModMetadataShim metadata();
  Path rootPath();
  default Path getPath(String file) { Path r = rootPath(); return r == null ? null : r.resolve(file); }
  java.util.List<java.util.List<Path>> getSourcePaths();
  ClassLoader getClassLoader();
  default Path getMutableFileOverlay() { return null; }
}
