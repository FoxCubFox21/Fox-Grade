package foxgrade.shim;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;

public final class SoundCompat {
  private SoundCompat() { }
  /** play() gained a result in 26.2; the old call discarded nothing. */
  public static void play(SoundManager m, SoundInstance s) { m.play(s); }
}
