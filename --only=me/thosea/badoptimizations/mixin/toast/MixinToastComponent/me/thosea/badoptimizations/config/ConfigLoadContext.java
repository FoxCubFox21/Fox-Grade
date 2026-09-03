package me.thosea.badoptimizations.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import org.jetbrains.annotations.Nullable;

public final class ConfigLoadContext {
   public final ModIncompatibilities incompats = new ModIncompatibilities();
   public final int version;
   @Nullable
   public final Properties properties;

   public ConfigLoadContext() {
      if (Files.exists(Config.FILE)) {
         this.properties = this.loadProp();
      } else {
         this.properties = null;
      }

      this.version = this.intOrDefault("config_version", 6);
   }

   private Properties loadProp() {
      try {
         Properties prop = new Properties();

         Properties var3;
         try (InputStream stream = Files.newInputStream(Config.FILE)) {
            prop.load(stream);
            var3 = prop;
         }

         return var3;
      } catch (Exception var7) {
         Config.LOGGER.error("Failed to load config from " + Config.FILE + ". If you need to, you can delete the file to generate a new one.", var7);
         System.exit(1);
         return null;
      }
   }

   public boolean boolOrDefault(String name, boolean def) {
      String value = this.str(name);
      if (value == null) {
         return def;
      } else if (value.equalsIgnoreCase("true")) {
         return true;
      } else if (value.equalsIgnoreCase("false")) {
         return false;
      } else {
         throw new IllegalStateException("Config option " + name + " is not \"true\" or \"false\" (\"" + name + "\")");
      }
   }

   public int intOrDefault(String name, int def) {
      String value = this.str(name);
      if (value == null) {
         return def;
      } else {
         int result;
         try {
            result = Integer.parseInt(value);
         } catch (NumberFormatException var6) {
            throw new IllegalStateException("Config number " + name + " is not a valid number (\"" + value + "\")");
         }

         if (result < 0) {
            throw new IllegalStateException("Config number " + name + " is negative (" + result + ")");
         } else {
            return result;
         }
      }
   }

   private String str(String name) {
      if (this.properties == null) {
         return null;
      } else {
         String str = this.properties.getProperty(name);
         if (str == null) {
            throw new IllegalStateException("Config option " + name + " not found");
         } else {
            return str;
         }
      }
   }

   public boolean fromExistingFile() {
      return this.properties != null;
   }

   public void dumpConfig() {
      if (this.properties == null) {
         Config.LOGGER.info("BadOptimizations is generating the config with default values");
      } else {
         Config.LOGGER.info("BadOptimizations config dump:");
         this.properties.forEach((key, value) -> Config.LOGGER.info("{}: {}", key, value));
      }
   }

   public ConfigOptimization optimization(String option) {
      return new ConfigOptimization(this, option, true);
   }

   public ConfigOptimization optimization(String option, boolean loadCondition) {
      return new ConfigOptimization(this, option, loadCondition);
   }

   public boolean option(String option, boolean def) {
      return this.option(option, true, def);
   }

   public boolean option(String option, boolean loadCondition, boolean def) {
      return loadCondition ? this.boolOrDefault(option, def) : def;
   }
}
