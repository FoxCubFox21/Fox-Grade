package me.thosea.badoptimizations.config;

public final class ConfigOptimization {
   public final boolean userValue;
   public final boolean effectiveValue;

   public ConfigOptimization(ConfigLoadContext ctx, String option, boolean loadCondition) {
      this.userValue = !loadCondition || ctx.boolOrDefault(option, true);
      this.effectiveValue = this.userValue && !ctx.incompats.isIncompatible(option);
   }

   @Override
   public String toString() {
      return Boolean.toString(this.userValue);
   }
}
