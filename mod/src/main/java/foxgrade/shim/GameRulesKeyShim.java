package foxgrade.shim;

import net.minecraft.world.level.gamerules.GameRule;

/** 1.21.x {@code GameRules$Key}: a handle on a rule, over 26.2's {@link GameRule} objects. */
@SuppressWarnings("rawtypes")
public final class GameRulesKeyShim {
  public final GameRule rule;
  public GameRulesKeyShim(GameRule rule) { this.rule = rule; }
  public String getId() { return String.valueOf(rule); }
  @Override public String toString() { return getId(); }
}
