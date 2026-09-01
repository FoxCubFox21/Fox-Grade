// Load every class in a jar through a real ClassLoader so the JVM's own verifier passes judgement.
// javap only proves a class file parses; VerifyError is what a wrong bytecode edit actually causes,
// and only the verifier can say. Resolution is forced so the StackMapTable is genuinely checked.
import java.io.*; import java.net.*; import java.util.*; import java.util.zip.*;

public class V {
  static class Loader extends ClassLoader {
    Loader(ClassLoader p) { super(p); }
    Class<?> def(String n, byte[] b) { return defineClass(n, b, 0, b.length); }
  }
  public static void main(String[] a) throws Exception {
    List<URL> urls = new ArrayList<>();
    for (String p : a[1].split(":")) if (!p.isEmpty()) urls.add(new File(p).toURI().toURL());
    urls.add(new File(a[0]).toURI().toURL());
    Loader ld = new Loader(new URLClassLoader(urls.toArray(new URL[0]), V.class.getClassLoader()));
    int ok = 0, verifyErrors = 0, other = 0;
    List<String> bad = new ArrayList<>();
    try (ZipFile z = new ZipFile(a[0])) {
      for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
        ZipEntry en = e.nextElement();
        if (!en.getName().endsWith(".class")) continue;
        byte[] buf = z.getInputStream(en).readAllBytes();
        String n = en.getName().replace('/', '.').replaceAll("\\.class$", "");
        try { Class<?> c = ld.def(n, buf); c.getDeclaredMethods(); ok++; }
        catch (VerifyError ve) { verifyErrors++; if (bad.size() < 5) bad.add(n + ": " + ve.getMessage()); }
        catch (Throwable t) { other++; }   // NoClassDefFound etc: a missing dependency, not a bad edit
      }
    }
    System.out.println("  verified OK   : " + ok);
    System.out.println("  VERIFY ERRORS : " + verifyErrors);
    System.out.println("  other (missing deps, not bytecode) : " + other);
    for (String s : bad) System.out.println("    ✗ " + s);
  }
}
