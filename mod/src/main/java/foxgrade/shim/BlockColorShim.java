package foxgrade.shim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** 1.21.x {@code net.minecraft.client.color.block.BlockColor}: 26.2 tints blocks through data-driven tint sources.
 *  Mods implement this and register it; the registration is accepted but inactive (default tint). */
public interface BlockColorShim {
  int getColor(BlockState state, net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos, int tintIndex);
}
