// Post-port verification: walk the ported bytecode and collect every reference to a Minecraft
// class that does not exist in the target MC. These are the honest "this port is not finished"
// signals — the jar will load, but any code path reaching one of these references will throw
// NoClassDefFoundError at runtime.
//
// Only CLASS-level existence is checked. Method-level checking against a flat per-class
// inventory would flag inherited calls as missing (the inventory has no hierarchy), and a
// verifier that cries wolf teaches people to ignore it.
//
// Collection rides ASM's ClassRemapper with an identity Remapper that records every class name
// it is asked about — the same traversal the real remap uses, so coverage is identical.
package foxgrade;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Set;
import java.util.TreeSet;

public final class PortVerifier {
  private final Set<String> known;
  private final Set<String> missing = new TreeSet<>();

  public PortVerifier(AutoBlocklistFromRefmap inventory) { this.known = inventory.classNames(); }

  public boolean isActive() { return !known.isEmpty(); }

  public void scan(byte[] classBytes) {
    if (known.isEmpty()) return;
    ClassReader r = new ClassReader(classBytes);
    r.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
      @Override public String map(String internalName) {
        // com/mojang covers several EXTERNAL libraries too (authlib, brigadier, serialization);
        // only the subpackages that ship inside the client jar are checkable here.
        boolean checkable = internalName.startsWith("net/minecraft/")
            || internalName.startsWith("com/mojang/blaze3d/")
            || internalName.startsWith("com/mojang/math/")
            || internalName.startsWith("com/mojang/realmsclient/");
        if (checkable && internalName.indexOf('$') < 0 && !known.contains(internalName)) {
          missing.add(internalName);
        }
        return internalName;
      }
    }), 0);
  }

  public Set<String> missing() { return missing; }
}
