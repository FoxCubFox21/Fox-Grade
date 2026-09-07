# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client launched straight into a world.
**Boots** means the mod under test was loaded, the world started loading, and the game was still
running 8 seconds later, within a 150-second cap. Both tools in the head-to-head are graded by
exactly that rule, from the same launch command, base jars and instance layout; a boot in which
the tested mod was held back or failed to port is graded "held", not "boots". A screenshot is
linked where the game got far enough to take one. "Unresolved" is the number of classes,
fields and methods the port still references that 26.2 no longer has — the honest measure of
how much of the mod is reachable. Generated from the harness ledgers at commit `d8bf6ae`
on 2026-09-07; the scripts are in `tools/`.

| Result | Mods |
|---|---|
| ✅ boots | 66 |
| ❌ crash | 39 |
| ❌ stall | 18 |
| ⏸ held (library missing) | 7 |

## Per mod

| Mod | Built for | Fox-Grade (client) | Unresolved | Server | Retromod | Screenshot | Notes |
|---|---|---|---|---|---|---|---|
| 3dskinlayers | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/3dskinlayers.png) |  |
| almanac | 26.1 | ✅ boots |  |  |  | [shot](shots/almanac.png) |  |
| ambient-environment | 26.1 | ✅ boots |  |  |  | [shot](shots/ambient-environment.png) |  |
| ambientsounds | 1.21.1 | ❌ crash | 34 |  | ❌ crash |  | java.lang.NoSuchMethodError: 'java.util.concurrent.ExecutorService net.minecraft.util.Util |
| amendments | 1.21 | ❌ crash | 273 |  | ❌ crash |  | moonlight depends on WeightedRandomList/WeightedEntry, replaced by WeightedList in 26.2 |
| animatica | >=1.21 | ✅ boots |  |  |  |  |  |
| appleskin | 1.21 | ✅ boots | 5 |  | ❌ crash | [shot](shots/appleskin.png) |  |
| attributefix | 1.21.1 | ❌ crash | 0 |  | ❌ crash | [shot](shots/attributefix.png) | bookshelf reads LootPoolEntryType, removed with 26.2's loot rewrite |
| badoptimizations | 1.21.1 | ✅ boots | 11 |  | ✅ boots | [shot](shots/badoptimizations.png) |  |
| better-advancements | 1.21.1 | ✅ boots | 1 |  | ✅ boots | [shot](shots/better-advancements.png) |  |
| better-mount-hud |  | ✅ boots |  |  |  | [shot](shots/better-mount-hud.png) |  |
| better-stats |  | ✅ boots |  |  |  | [shot](shots/better-stats.png) |  |
| better-third-person | 1.21 | ✅ boots | 3 |  | ✅ boots | [shot](shots/better-third-person.png) |  |
| betterchunkborders | 1.21.x / 26.1.x | ✅ boots | 1 |  |  | [shot](shots/betterchunkborders.png) |  |
| betterf3 | >=1.21 | ✅ boots | 25 |  | ✅ boots | [shot](shots/betterf3.png) |  |
| betterhurtcam | >=1.21 | ✅ boots |  |  |  |  |  |
| biomes-o-plenty | 1.21.1 | ❌ crash | 9 |  | ❌ crash |  | villager types moved to registry keys and WeightedEntry is gone; worldgen data changed shape |
| boat-item-view | >=1.21 <=1.21.1 | ✅ boots |  |  |  | [shot](shots/boat-item-view.png) |  |
| carpet | 1.21 | ✅ boots |  | ✅ boots |  | [shot](shots/carpet.png) |  |
| carry-on | 1.21.1 | ✅ boots | 13 |  | ❌ crash | [shot](shots/carry-on.png) |  |
| chalk | >=1.21.1 | ❌ crash | 7 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/level/block/state/propertie |
| chat-heads | >=1.21 <=1.21.1 | ✅ boots | 3 |  |  | [shot](shots/chat-heads.png) |  |
| chatanimation | 1.21 | ✅ boots | 0 |  | ✅ boots | [shot](shots/chatanimation.png) |  |
| cherished-worlds | 1.21.1 | ✅ boots | 1 |  | ✅ boots | [shot](shots/cherished-worlds.png) |  |
| chipped | 1.21.1 | ❌ crash | 23 |  | ❌ crash |  | Caused by: java.lang.NoSuchMethodError: net.minecraft.world.level.block.LeavesBlock: metho |
| chloride | 1.21.1 | ❌ crash | 120 |  | ❌ crash |  |  |
| chunky |  | ✅ boots | 8 |  | ❌ crash | [shot](shots/chunky.png) |  |
| cit-resewn | 1.21 | ❌ crash | 2 |  | ✅ boots |  | built on item-model overrides (ItemOverride), which 26.2 replaced with item model definitions |
| clumps | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/clumps.png) |  |
| cobblemon | 1.21.1 | ❌ crash | 246 |  | ❌ crash |  | 251 unresolved references across 33 removed classes; a rewrite, not a port |
| comforts | 1.21.1 | ❌ stall | 27 |  | ❌ crash |  | listens to EntitySleepEvents.ALLOW_SLEEP_TIME, removed from Fabric API together with its callback interface |
| continuity | >=1.21 <=1.21.1 | ❌ crash |  |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/resources/model/BakedModel |
| controlify | 1.21.1 | ❌ crash | 14 |  | ❌ crash |  | its resource-reload listener implements the 1.21 reload signature through a path the reload adapter does not cover |
| controlling | 1.21.1 | ✅ boots | 3 |  | ✅ boots | [shot](shots/controlling.png) |  |
| crash-assistant | 1.20.2 | ✅ boots | 9 |  | ⏸ held (library missing) | [shot](shots/crash-assistant.png) |  |
| cubes-without-borders | 1.21 | ✅ boots | 17 |  | ✅ boots | [shot](shots/cubes-without-borders.png) |  |
| customskinloader |  | ✅ boots | 2 |  | ✅ boots | [shot](shots/customskinloader.png) |  |
| cut-through | 1.21.1 | ❌ crash | 283 |  | ❌ crash |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/gui/screens/inventory/Effe |
| debugify | 1.21.1 | ✅ boots | 14 |  | ❌ crash | [shot](shots/debugify.png) |  |
| default-options | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/default-options.png) |  |
| distanthorizons | 1.21.1 | ❌ crash | 51 |  | ✅ boots |  | java.lang.NoSuchFieldError: Class net.minecraft.world.level.storage.SavedDataStorage does  |
| drippy-loading | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | java.lang.NoClassDefFoundError: com/mojang/blaze3d/pipeline/RenderCall |
| duck | 1.21.1 | ❌ crash | 45 |  |  | [shot](shots/duck.png) | java.lang.NoSuchMethodError: 'void net.minecraft.world.entity.MobCategory.<init>(java.lang |
| dungeons-and-taverns |  | ❌ stall | 0 |  | ❌ crash |  | datapack structures in the 1.21 JSON shape; 26.2 rejects them at registry load |
| dynamic-fps |  | ⏸ held (library missing) |  |  | ✅ boots | [shot](shots/dynamic-fps.png) | they get ported alongside) |
| dynamiccrosshair | 1.21.1 | ❌ stall | 9 |  | ❌ crash |  | reads private Inventory.selected; widened in 1.1.0 |
| e4mc |  | ✅ boots | 3 |  | ✅ boots | [shot](shots/e4mc.png) |  |
| eating-animation | >=1.21 | ❌ crash |  |  |  |  |  |
| ebe | 1.21 | ❌ crash | 24 |  | ❌ crash |  | needs Fabric's FabricBakedModelManager, removed with the model-loading rewrite |
| enchantment-descriptions | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/level/storage/loot/entries/ |
| enhancedvisuals | 1.21.1 | ❌ crash | 18 |  | ❌ crash |  | renders straight into the main render target, which 26.2 no longer exposes |
| entity-model-features | 1.21 | ❌ stall | 43 |  | ❌ crash |  | hooks every vanilla entity model's mesh builder (WolfModel.createMeshDefinition and friends), rebuilt in 26.2 |
| entityculling | 1.21.1 | ✅ boots |  |  | ❌ crash | [shot](shots/entityculling.png) |  |
| entitytexturefeatures | 1.21 | ❌ stall | 43 |  | ❌ crash | [shot](shots/entitytexturefeatures.png) | entity-render layer internals reshaped in 26.2 (render → submit); the layer hooks it needs are gone |
| euphoria-patches |  | ✅ boots | 6 |  | ✅ boots | [shot](shots/euphoria-patches.png) |  |
| fabrishot |  | ✅ boots | 9 |  | ✅ boots | [shot](shots/fabrishot.png) |  |
| fallingleaves |  | ✅ boots |  |  |  |  |  |
| fancymenu | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | 177 unresolved references in its own GUI framework |
| fast-ip-ping | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/fast-ip-ping.png) |  |
| fastquit |  | ✅ boots | 7 |  | ✅ boots | [shot](shots/fastquit.png) |  |
| ferrite-core |  | ✅ boots | 13 |  | ❌ crash | [shot](shots/ferrite-core.png) |  |
| freecam | 1.21 | ✅ boots | 18 |  | ✅ boots | [shot](shots/freecam.png) |  |
| friends-and-foes | >=1.21 | ❌ crash | 32 |  |  |  |  |
| handcrafted | 1.21.1 | ❌ stall | 23 |  | ❌ crash | [shot](shots/handcrafted.png) | model baking fails on its block models (translucency out of bounds) and the world never loads |
| highlightmobs | ~1.21.1 | ✅ boots | 2 |  |  | [shot](shots/highlightmobs.png) |  |
| inventory-profiles-next |  | ⏸ held (library missing) |  |  | ❌ crash | [shot](shots/inventory-profiles-next.png) | they get ported alongside) |
| jade | 1.21.1 | ❌ crash | 96 |  |  |  | Caused by: java.lang.NoSuchMethodError: 'void snownee.jade.mixin.KeyAccess.setDisplayName( |
| jei | 1.21.1 | ✅ boots | 72 |  | ❌ crash | [shot](shots/jei.png) |  |
| krypton |  | ✅ boots |  | ✅ boots |  | [shot](shots/krypton.png) |  |
| lambdynamiclights |  | ⏸ held (library missing) |  |  |  | [shot](shots/lambdynamiclights.png) | they get ported alongside) |
| language-reload | 1.21.1 | ✅ boots | 13 |  | ✅ boots | [shot](shots/language-reload.png) |  |
| lighty | >=1.21.1 | ✅ boots | 15 |  |  | [shot](shots/lighty.png) |  |
| lithium | 1.21.1 | ✅ boots |  | ✅ boots | ❌ crash | [shot](shots/lithium.png) |  |
| llo | ~1.21.1 | ❌ crash | 1 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/InteractionResultHolder |
| lmd | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/lmd.png) |  |
| lootr |  | ❌ crash | 96 |  | ❌ crash | [shot](shots/lootr.png) | java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provi |
| mixintrace |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/mixintrace.png) |  |
| modelfix | 1.21 | ✅ boots | 1 |  | ✅ boots | [shot](shots/modelfix.png) |  |
| modernfix | 1.21.1 | ❌ stall | 30 |  | ❌ crash | [shot](shots/modernfix.png) | texture-stitcher internals (Stitcher.SpriteLoader) changed shape in 26.2; renderer-tier |
| modmenu |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/modmenu.png) |  |
| morechathistory |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/morechathistory.png) |  |
| mouse-tweaks | 1.21 | ✅ boots |  |  |  | [shot](shots/mouse-tweaks.png) |  |
| mouse-wheelie |  | ⏸ held (library missing) |  |  |  | [shot](shots/mouse-wheelie.png) | they get ported alongside) |
| naturalist | ~1.21.1 | ❌ crash | 35 |  |  |  |  |
| natures-compass | 1.21.1 | ✅ boots | 7 |  | ❌ crash | [shot](shots/natures-compass.png) |  |
| netherportalfix | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/netherportalfix.png) |  |
| no-chat-reports | 1.21.1 | ✅ boots | 18 |  | ✅ boots | [shot](shots/no-chat-reports.png) |  |
| not-enough-animations | 1.21.1 | ✅ boots | 9 |  | ✅ boots | [shot](shots/not-enough-animations.png) |  |
| optigui | 1.21 | ✅ boots | 8 |  | ❌ crash | [shot](shots/optigui.png) |  |
| packet-fixer | 1.20.5 | ✅ boots | 0 |  | ✅ boots | [shot](shots/packet-fixer.png) |  |
| paginatedadvancements |  | ✅ boots | 5 |  | ✅ boots | [shot](shots/paginatedadvancements.png) |  |
| particle-rain | 1.21.1 | ❌ crash | 22 |  | ❌ crash |  | custom particle render types: ParticleRenderType is a named record in 26.2, nothing can implement it |
| physicsmod | 1.21.1 | ❌ stall | 136 |  | ❌ crash |  | renderer-tier: its own render pipeline reads shader resources 26.2 no longer ships |
| polymorph | 1.21.1 | ✅ boots | 13 |  | ❌ crash | [shot](shots/polymorph.png) |  |
| presence-footsteps | 1.21.1 | ✅ boots | 8 |  | ❌ crash | [shot](shots/presence-footsteps.png) |  |
| rei |  | ❌ stall | 118 |  | ❌ crash |  | needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the dependency before either tool runs |
| rrls |  | ❌ crash | 8 |  | ❌ crash |  | its resource-reload mixin mutates 26.2's listener list while it is iterated |
| shulkerboxtooltip | >=1.21.1 | ❌ crash | 18 |  |  |  | java.lang.NoSuchMethodError: 'net.minecraft.nbt.ListTag net.minecraft.world.inventory.Play |
| sodium-dynamic-lights | 1.21.1 | ❌ stall | 0 |  | ❌ crash |  | no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal |
| sodium-options-api | 1.21.1 | ❌ stall | 0 |  | ❌ crash |  | no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal |
| sodium-shadowy-path-blocks | 1.21.1 | ❌ crash | 4 |  | ❌ crash |  | needs Sodium, and Sodium itself cannot be ported: its particle mixin targets a class hierarchy 26.2 rewrote |
| sound | 1.21 / 1.21.1 | ❌ stall | 14 |  | ❌ crash |  | java.util.concurrent.CompletionException: java.lang.NoSuchMethodError: 'net.minecraft.core |
| sound-physics-remastered | 1.21 / 1.21.1 | ✅ boots |  |  |  |  |  |
| spark |  | ❌ crash |  | ❌ crash |  |  |  |
| supplementaries | 1.21.1 | ❌ crash | 273 |  | ❌ crash |  | moonlight depends on WeightedRandomList/WeightedEntry, replaced by WeightedList in 26.2 |
| terralith |  | ❌ crash | 0 |  | ❌ crash |  | its library lithostitched injects into VillagerType with a handler 26.2's verifier rejects |
| toms-storage | 1.21 | ❌ crash | 76 |  | ❌ crash |  | draws through Gui.layers (LayeredDraw), removed in 26.2's HUD rewrite |
| towns-and-towers | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | java.lang.NoSuchMethodError: 'java.lang.Object com.mojang.serialization.Dynamic.value()' |
| travelersbackpack |  | ❌ crash | 86 |  | ❌ crash |  | Cardinal Components' entity hooks are among the mixins 26.2 cannot apply (run with Cardinal Components and Cloth Config present) |
| trinkets |  | ⏸ held (library missing) |  |  | ❌ crash | [shot](shots/trinkets.png) | they get ported alongside) |
| veinminer | 1.21.1 | ✅ boots | 1 |  | ❌ crash | [shot](shots/veinminer.png) |  |
| veinminer-client | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/veinminer-client.png) |  |
| visual-workbench | 1.21.1 | ❌ crash | 14 |  | ❌ crash |  |  |
| visuality |  | ✅ boots | 5 |  | ✅ boots | [shot](shots/visuality.png) |  |
| wavey-capes | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/wavey-capes.png) |  |
| waystones | 1.21.1 | ✅ boots | 45 |  | ❌ crash | [shot](shots/waystones.png) |  |
| wthit | >=1.21-0 | ❌ crash | 72 |  |  | [shot](shots/wthit.png) | Caused by: java.lang.NoClassDefFoundError: net/minecraft/resources/ResourceLocation$Serial |
| xaeros-minimap |  | ⏸ held (library missing) |  |  |  | [shot](shots/xaeros-minimap.png) | they get ported alongside) |
| xaeros-world-map |  | ⏸ held (library missing) |  |  | ❌ crash | [shot](shots/xaeros-world-map.png) | they get ported alongside) |
| yeetus-experimentus |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/yeetus-experimentus.png) |  |
| yosbr |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/yosbr.png) |  |
| yungs-better-dungeons |  | ❌ stall | 7 |  | ❌ crash | [shot](shots/yungs-better-dungeons.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| yungs-better-end-island |  | ❌ crash | 13 |  | ❌ crash | [shot](shots/yungs-better-end-island.png) | died 6s after the world started loading |
| yungs-better-jungle-temples |  | ❌ stall | 4 |  | ❌ crash | [shot](shots/yungs-better-jungle-temples.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| yungs-better-mineshafts |  | ❌ crash | 5 |  | ✅ boots | [shot](shots/yungs-better-mineshafts.png) | died 4s after the world started loading |
| yungs-better-nether-fortresses |  | ❌ stall | 5 |  | ✅ boots | [shot](shots/yungs-better-nether-fortresses.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| yungs-better-ocean-monuments |  | ❌ stall | 1 |  | ✅ boots | [shot](shots/yungs-better-ocean-monuments.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| yungs-better-strongholds |  | ❌ stall | 0 |  | ❌ crash | [shot](shots/yungs-better-strongholds.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| yungs-better-witch-huts |  | ❌ stall | 0 |  | ✅ boots | [shot](shots/yungs-better-witch-huts.png) | YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessorType wants MapCodecs, so registry loading fails and the world never opens (run with YUNG's API present) |
| zoomify | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/zoomify.png) |  |

## Retromod head-to-head

Same mods, same instance, same base jars. Retromod ports on its first launch and asks for a
restart; the second launch is the verdict. Retromod 1.3.0-snapshot.10 for 26.2. Fox-Grade's
verdict requires the world to render and a screenshot to be taken; Retromod's requires the world
to load and the game to still be running.

| Mod | Fox-Grade | Retromod |
|---|---|---|
| 3dskinlayers | ✅ boots | ✅ boots |
| ambientsounds | ❌ crash — java.lang.NoSuchMethodError: 'java.util.concurrent.ExecutorService net.minecraft.util.Util | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| amendments | ❌ crash — moonlight depends on WeightedRandomList/WeightedEntry, replaced by WeightedList in 26.2 | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| appleskin | ✅ boots | ❌ crash |
| attributefix | ❌ crash — bookshelf reads LootPoolEntryType, removed with 26.2's loot rewrite | ❌ crash — Description: Unexpected error |
| badoptimizations | ✅ boots | ✅ boots |
| better-advancements | ✅ boots | ✅ boots |
| better-third-person | ✅ boots | ✅ boots |
| betterf3 | ✅ boots | ✅ boots |
| biomes-o-plenty | ❌ crash — villager types moved to registry keys and WeightedEntry is gone; worldgen data changed sha | ❌ crash — Description: Bootstrap |
| carry-on | ✅ boots | ❌ crash — Description: Initializing game |
| chatanimation | ✅ boots | ✅ boots |
| cherished-worlds | ✅ boots | ✅ boots |
| chipped | ❌ crash — Caused by: java.lang.NoSuchMethodError: net.minecraft.world.level.block.LeavesBlock: metho | ❌ crash — [23:02:26] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| chloride | ❌ crash | ❌ crash |
| chunky | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| cit-resewn | ❌ crash — built on item-model overrides (ItemOverride), which 26.2 replaced with item model definiti | ✅ boots |
| clumps | ✅ boots | ✅ boots |
| cobblemon | ❌ crash — 251 unresolved references across 33 removed classes; a rewrite, not a port | ❌ crash |
| comforts | ❌ stall — listens to EntitySleepEvents.ALLOW_SLEEP_TIME, removed from Fabric API together with its c | ❌ crash — Description: Initializing game |
| controlify | ❌ crash — its resource-reload listener implements the 1.21 reload signature through a path the reloa | ❌ crash — Description: Initializing game |
| controlling | ✅ boots | ✅ boots |
| crash-assistant | ✅ boots | ⏸ held (library missing) — mod under test never loaded (held or port failed) |
| cubes-without-borders | ✅ boots | ✅ boots |
| customskinloader | ✅ boots | ✅ boots |
| cut-through | ❌ crash — Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/gui/screens/inventory/Effe | ❌ crash — Description: Bootstrap |
| debugify | ✅ boots | ❌ crash — Description: Initializing game |
| default-options | ✅ boots | ❌ crash — [07:32:44] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| distanthorizons | ❌ crash — java.lang.NoSuchFieldError: Class net.minecraft.world.level.storage.SavedDataStorage does  | ✅ boots |
| drippy-loading | ❌ crash — java.lang.NoClassDefFoundError: com/mojang/blaze3d/pipeline/RenderCall | ❌ crash — Description: Initializing game |
| dungeons-and-taverns | ❌ stall — datapack structures in the 1.21 JSON shape; 26.2 rejects them at registry load | ❌ crash |
| dynamic-fps | ⏸ held (library missing) — they get ported alongside) | ✅ boots |
| dynamiccrosshair | ❌ stall — reads private Inventory.selected; widened in 1.1.0 | ❌ crash — Description: Unexpected error |
| e4mc | ✅ boots | ✅ boots |
| ebe | ❌ crash — needs Fabric's FabricBakedModelManager, removed with the model-loading rewrite | ❌ crash — Description: Initializing game |
| enchantment-descriptions | ❌ crash — Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/level/storage/loot/entries/ | ❌ crash — Description: Unexpected error |
| enhancedvisuals | ❌ crash — renders straight into the main render target, which 26.2 no longer exposes | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| entity-model-features | ❌ stall — hooks every vanilla entity model's mesh builder (WolfModel.createMeshDefinition and friend | ❌ crash |
| entityculling | ✅ boots | ❌ crash |
| entitytexturefeatures | ❌ stall — entity-render layer internals reshaped in 26.2 (render → submit); the layer hooks it needs | ❌ crash |
| euphoria-patches | ✅ boots | ✅ boots |
| fabrishot | ✅ boots | ✅ boots |
| fancymenu | ❌ crash — 177 unresolved references in its own GUI framework | ❌ crash — Description: Initializing game |
| fast-ip-ping | ✅ boots | ✅ boots |
| fastquit | ✅ boots | ✅ boots |
| ferrite-core | ✅ boots | ❌ crash — Caused by: java.lang.RuntimeException: java.lang.NoSuchFieldException: |
| freecam | ✅ boots | ✅ boots |
| handcrafted | ❌ stall — model baking fails on its block models (translucency out of bounds) and the world never lo | ❌ crash — Description: Initializing game |
| inventory-profiles-next | ⏸ held (library missing) — they get ported alongside) | ❌ crash |
| jei | ✅ boots | ❌ crash — Description: Unexpected error |
| language-reload | ✅ boots | ✅ boots |
| lithium | ✅ boots | ❌ crash |
| lmd | ✅ boots | ✅ boots |
| lootr | ❌ crash — java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provi | ❌ crash — [23:03:38] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| mixintrace | ✅ boots | ✅ boots |
| modelfix | ✅ boots | ✅ boots |
| modernfix | ❌ stall — texture-stitcher internals (Stitcher.SpriteLoader) changed shape in 26.2; renderer-tier | ❌ crash |
| modmenu | ✅ boots | ✅ boots |
| morechathistory | ✅ boots | ✅ boots |
| natures-compass | ✅ boots | ❌ crash — Description: Initializing game |
| netherportalfix | ✅ boots | ❌ crash — [07:08:41] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| no-chat-reports | ✅ boots | ✅ boots |
| not-enough-animations | ✅ boots | ✅ boots |
| optigui | ✅ boots | ❌ crash — Description: Initializing game |
| packet-fixer | ✅ boots | ✅ boots |
| paginatedadvancements | ✅ boots | ✅ boots |
| particle-rain | ❌ crash — custom particle render types: ParticleRenderType is a named record in 26.2, nothing can im | ❌ crash — Description: Initializing game |
| physicsmod | ❌ stall — renderer-tier: its own render pipeline reads shader resources 26.2 no longer ships | ❌ crash — Caused by: java.lang.ClassNotFoundException: The specified mixin 'net. |
| polymorph | ✅ boots | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/class_2960 |
| presence-footsteps | ✅ boots | ❌ crash — Description: Initializing game |
| rei | ❌ stall — needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the  | ❌ crash |
| rrls | ❌ crash — its resource-reload mixin mutates 26.2's listener list while it is iterated | ❌ crash — Caused by: java.lang.VerifyError: Bad local variable type |
| sodium-dynamic-lights | ❌ stall — no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options belo | ❌ crash |
| sodium-options-api | ❌ stall — no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options belo | ❌ crash |
| sodium-shadowy-path-blocks | ❌ crash — needs Sodium, and Sodium itself cannot be ported: its particle mixin targets a class hiera | ❌ crash |
| sound | ❌ stall — java.util.concurrent.CompletionException: java.lang.NoSuchMethodError: 'net.minecraft.core | ❌ crash — Description: Initializing game |
| supplementaries | ❌ crash — moonlight depends on WeightedRandomList/WeightedEntry, replaced by WeightedList in 26.2 | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| terralith | ❌ crash — its library lithostitched injects into VillagerType with a handler 26.2's verifier rejects | ❌ crash — Description: Bootstrap |
| toms-storage | ❌ crash — draws through Gui.layers (LayeredDraw), removed in 26.2's HUD rewrite | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/class_2960 |
| towns-and-towers | ❌ crash — java.lang.NoSuchMethodError: 'java.lang.Object com.mojang.serialization.Dynamic.value()' | ❌ crash — java.lang.NoSuchMethodError: 'int net.minecraft.WorldVersion.getPackVe |
| travelersbackpack | ❌ crash — Cardinal Components' entity hooks are among the mixins 26.2 cannot apply (run with Cardina | ❌ crash — Caused by: java.lang.NullPointerException: Cannot invoke "net.minecraf |
| trinkets | ⏸ held (library missing) — they get ported alongside) | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/world/level/GameRules$Ke |
| veinminer | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| veinminer-client | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| visual-workbench | ❌ crash | ❌ crash — Description: Bootstrap |
| visuality | ✅ boots | ✅ boots |
| wavey-capes | ✅ boots | ❌ crash — Description: Ticking entity |
| waystones | ✅ boots | ❌ crash — [20:22:44] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| xaeros-world-map | ⏸ held (library missing) — they get ported alongside) | ❌ crash — Description: Initializing game |
| yeetus-experimentus | ✅ boots | ✅ boots |
| yosbr | ✅ boots | ✅ boots |
| yungs-better-dungeons | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ❌ crash |
| yungs-better-end-island | ❌ crash — died 6s after the world started loading | ❌ crash — Description: Exception in server tick loop |
| yungs-better-jungle-temples | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ❌ crash |
| yungs-better-mineshafts | ❌ crash — died 4s after the world started loading | ✅ boots |
| yungs-better-nether-fortresses | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ✅ boots |
| yungs-better-ocean-monuments | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ✅ boots |
| yungs-better-strongholds | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ❌ crash |
| yungs-better-witch-huts | ❌ stall — YUNG's API registers structure processor types as Codec lambdas; 26.2's StructureProcessor | ✅ boots |
| zoomify | ✅ boots | ❌ crash |

Score: Fox-Grade 50 / Retromod 37 of 100 mods booting.

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.
