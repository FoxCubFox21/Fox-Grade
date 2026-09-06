package foxgrade.shim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/** 1.21.x game-rule access (RULE_* keys + getBoolean) over 26.2's typed rules. */
public final class GameRulesCompat {
  private GameRulesCompat() {}
  public static GameRulesKeyShim ruleMobGriefing() { return new GameRulesKeyShim(GameRules.MOB_GRIEFING); }
  public static GameRulesKeyShim ruleDoMobLoot() { return new GameRulesKeyShim(GameRules.MOB_DROPS); }
  public static GameRulesKeyShim ruleDoMobSpawning() { return new GameRulesKeyShim(GameRules.SPAWN_MOBS); }
  public static GameRulesKeyShim ruleDoTileDrops() { return new GameRulesKeyShim(GameRules.BLOCK_DROPS); }
  public static GameRulesKeyShim ruleDoEntityDrops() { return new GameRulesKeyShim(GameRules.ENTITY_DROPS); }
  public static GameRulesKeyShim ruleKeepInventory() { return new GameRulesKeyShim(GameRules.KEEP_INVENTORY); }
  @SuppressWarnings("unchecked")
  public static boolean getBoolean(GameRules rules, GameRulesKeyShim key) { return rules != null && key != null && Boolean.TRUE.equals(rules.get(key.rule)); }
  /** {@code Level.getGameRules()}: only a server level owns rules in 26.2. */
  public static GameRules getGameRules(Level level) { return level instanceof ServerLevel sl ? sl.getGameRules() : null; }
}
