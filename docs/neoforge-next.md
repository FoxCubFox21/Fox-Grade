# NeoForge — next batch (found while the corpus of 2026-09-09 was running, deliberately not applied to it)

1. ~~**`FMLLoader` statics became instance methods.**~~ **Done 2026-09-09**, after the corpus run started, so it is
   not in that run's numbers. A mod calling `FMLLoader.isProduction()` gets
   `Expected static method 'boolean net.neoforged.fml.loading.FMLLoader.isProduction()'` on 26.2, because FML 11
   moved it onto the instance from `getCurrent()`. It is not a rename, so `gen-neoforge-bridges.py` does not see it:
   the generator only converts a *field* into an accessor. The fix is a call redirect to
   `FMLEnvironment.isProduction()`, which is still static on the target and returns the same thing. Same shape
   probably applies to the other FMLLoader statics.

2. ~~Supply only the dependencies the mod under test declares~~ **Done 2026-09-09, and it was not a tidy-up — it was
   invalidating results.** Chat Heads shut the client down during startup with nothing logged when all eleven base
   libraries were present, and reaches a world when given only what it declares (which is nothing). The same shape
   applies to immediatelyfast, modernfix, owo-lib and veinminer, all five of which declare no dependencies at all.
   Original reasoning below.

   **Supply only the dependencies the mod under test declares, not all eleven base libraries.** Reading the TOML
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

5. ~~A silent-abort failure mode, cause unknown.~~ **Explained: it was item 2.** The mods are fine; the harness was
   loading eleven unrelated libraries into every run. Original note below.

   **A silent-abort failure mode, cause unknown.** chat-heads and modernfix print their mod list, finish mixin
   setup, and then log `Closing FML Loader` about four seconds in with no exception anywhere — `Client.main` appears
   to return normally and the client exits before a window is created. The java process lingers, so the harness
   grades it a stall. Both mods are in the loaded-mod list at that point, so discovery and the port itself
   succeeded. Worth reproducing with a visible window and `-verbose:class`, since whatever it is happens before
   anything Fox-Grade writes gets a chance to run.

6. **A mod's language loader is a dependency, and the harness does not know it.** VeinMiner declares
   `modLoader = "klf"` in its manifest — it is a Kotlin mod — and dies with
   `Missing language loader klf wanted by ...veinminer`. That string is not in any `[[dependencies]]` table, so
   reading dependency tables alone misses it, and the mod is failed for a library the harness simply did not hand
   it. `nf_deps` should also read `modLoader` and treat anything other than `javafml`/`lowcodefml` as a dependency
   to satisfy from the base set (`klf` is provided by kotlin-for-forge). Affects veinminer and veinminer-client.

7. **The three silent aborts have three different causes, not one.** Worth writing down because they looked
   identical from the outside:
   - **modernfix** runs its own mixin config plugin — "Applying Nashorn fix", "Configuring Minecraft's
     max.bg.threads" — and FML closes on the next line. A startup-optimisation mod doing invasive things during
     early loading is the least portable thing in the corpus.
   - **owo-lib** closes while its bundled jars (endec, jankson, gson) are being listed, so the jar-in-jar porting
     added today is the thing to look at first.
   - **veinminer** is item 6 above and is not really silent at all; the cause was one line further up than the
     earlier scan looked.
