package foxgrade;

import java.util.ArrayList;
import java.util.List;

/** The UTF-8 entries of a class file's constant pool, read without ASM.
 *
 *  <p>Every name a class mentions — types it references, methods it calls, fields it reads — passes through here,
 *  which makes it the cheapest way to ask "does this class still talk about X" without building a full class model.
 *  Deliberately total: a pool it cannot walk returns what it read so far rather than throwing, because this is used
 *  by checks that must never be the reason a port fails. */
final class ConstantPool {
  private ConstantPool() { }

  static List<String> strings(byte[] d) {
    List<String> out = new ArrayList<>();
    if (d == null || d.length < 10) return out;
    int count = ((d[8] & 0xFF) << 8) | (d[9] & 0xFF);
    int i = 10;
    for (int k = 1; k < count; k++) {
      if (i >= d.length) return out;
      int tag = d[i] & 0xFF;
      switch (tag) {
        case 1 -> {                                   // CONSTANT_Utf8
          if (i + 3 > d.length) return out;
          int len = ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
          if (i + 3 + len > d.length) return out;
          out.add(new String(d, i + 3, len, java.nio.charset.StandardCharsets.UTF_8));
          i += 3 + len;
        }
        case 7, 8, 16, 19, 20 -> i += 3;              // Class, String, MethodType, Module, Package
        case 15 -> i += 4;                            // MethodHandle
        case 3, 4, 9, 10, 11, 12, 17, 18 -> i += 5;   // Integer, Float, refs, NameAndType, Dynamic
        case 5, 6 -> { i += 9; k++; }                 // Long, Double take two slots
        default -> { return out; }
      }
    }
    return out;
  }
}
