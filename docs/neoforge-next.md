# NeoForge — next batch (found while the corpus of 2026-09-09 was running, deliberately not applied to it)

1. ~~**`FMLLoader` statics became instance methods.**~~ **Done 2026-09-09**, after the corpus run started, so it is
   not in that run's numbers. A mod calling `FMLLoader.isProduction()` gets
   `Expected static method 'boolean net.neoforged.fml.loading.FMLLoader.isProduction()'` on 26.2, because FML 11
   moved it onto the instance from `getCurrent()`. It is not a rename, so `gen-neoforge-bridges.py` does not see it:
   the generator only converts a *field* into an accessor. The fix is a call redirect to
   `FMLEnvironment.isProduction()`, which is still static on the target and returns the same thing. Same shape
   probably applies to the other FMLLoader statics.

2. **Supply only the dependencies the mod under test declares, not all eleven base libraries.** Reading the TOML
   dependency tables across the corpus: cloth_config has three real dependents and every other base library has
   none or one. Loading all eleven into all forty runs buys almost nothing and costs startup time on each, plus
   noise — `creativecore` logs `Could not load default style` on every single run. `nf_run` already reads the mod's
   own modId out of its manifest, so reading its dependency list is the same few lines.

3. ~~`Gui.leftHeight` / `Gui.rightHeight`~~ **Decided: leave unresolved, do not shim.** They were the HUD's
   status-bar stacking offsets, which mods read and write to stack extra bars, and 26.2's HUD rewrite removed them.
   A shim could hold a number, but nothing in 26.2 would consult it, so the mod would draw in the wrong place and
   believe it had not — the loads-and-quietly-misbehaves case this project refuses. The port report naming
   `Gui#leftHeight` is the correct outcome. AppleSkin crashing there after the stand-in gets it past the deleted
   debug-overlay event is the system working as designed.

4. ~~Fabric numbers are stale-conservative.~~ **Checked, and they are not.** Grepping every harness log for
   "LVT ... has incompatible changes" turns up only Retromod's runs — rm-ambientsounds, rm-amendments,
   rm-enchantment-descriptions, rm-enhancedvisuals, rm-sound, rm-supplementaries and their Quilt equivalents. No
   Fox-Grade Fabric run ever hit that failure, so the capture softening changes nothing there and 86/130 stands.
   Worth noting the other way round: those are six mods Retromod loses to a failure Fox-Grade now survives.
