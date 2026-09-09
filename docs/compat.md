# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client launched straight into a world.
**Boots** means the mod under test was loaded, the world started loading, and the game was still
running 8 seconds later, within a 150-second cap. Both tools in the head-to-head are graded by
exactly that rule, from the same launch command, base jars and instance layout; a boot in which
the tested mod was held back or failed to port is graded "held", not "boots". A screenshot is
linked where the game got far enough to take one. "Unresolved" is the number of classes,
fields and methods the port still references that 26.2 no longer has — the honest measure of
how much of the mod is reachable. Generated from the harness ledgers at commit `0843ea7`
on 2026-09-09; the scripts are in `tools/`.

| Result | Mods |
|---|---|
| ✅ boots | 86 |
| ❌ crash | 30 |
| ❌ stall | 14 |

## Per mod

| Mod | Built for | Fox-Grade (client) | Unresolved | Server | Retromod | Screenshot | Notes |
|---|---|---|---|---|---|---|---|
| 3dskinlayers | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/3dskinlayers.png) |  |
| almanac | 26.1 | ✅ boots |  |  |  | [shot](shots/almanac.png) |  |
| ambient-environment | 26.1 | ✅ boots |  |  |  | [shot](shots/ambient-environment.png) |  |
| ambientsounds | 1.21.1 | ✅ boots | 34 |  | ❌ crash | [shot](shots/ambientsounds.png) |  |
| amendments | 1.21 | ❌ crash | 253 |  | ❌ crash |  | moonlight's registry supplier implements Holder, which 26.2 sealed |
| animatica | >=1.21 | ✅ boots |  |  |  |  |  |
| appleskin | 1.21 | ✅ boots | 5 |  | ❌ crash | [shot](shots/appleskin.png) |  |
| attributefix | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/attributefix.png) |  |
| badoptimizations | 1.21.1 | ✅ boots | 11 |  | ✅ boots | [shot](shots/badoptimizations.png) |  |
| better-advancements | 1.21.1 | ✅ boots | 1 |  | ✅ boots | [shot](shots/better-advancements.png) |  |
| better-mount-hud |  | ✅ boots |  |  |  | [shot](shots/better-mount-hud.png) |  |
| better-stats |  | ✅ boots |  |  |  | [shot](shots/better-stats.png) |  |
| better-third-person | 1.21 | ✅ boots | 8 |  | ✅ boots | [shot](shots/better-third-person.png) |  |
| betterchunkborders | 1.21.x / 26.1.x | ✅ boots | 1 |  |  | [shot](shots/betterchunkborders.png) |  |
| betterf3 | >=1.21 | ✅ boots | 19 |  | ✅ boots | [shot](shots/betterf3.png) |  |
| betterhurtcam | >=1.21 | ✅ boots |  |  |  |  |  |
| biomes-o-plenty | 1.21.1 | ❌ crash | 6 |  | ❌ crash |  | passes registry keys where 26.2's villager types expect the type record; worldgen data changed shape |
| boat-item-view | >=1.21 <=1.21.1 | ✅ boots |  |  |  | [shot](shots/boat-item-view.png) |  |
| carpet | 1.21 | ✅ boots |  | ✅ boots |  | [shot](shots/carpet.png) |  |
| carry-on | 1.21.1 | ✅ boots | 11 |  | ❌ crash | [shot](shots/carry-on.png) |  |
| chalk | >=1.21.1 | ❌ crash | 7 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/level/block/state/propertie |
| chat-heads | >=1.21 <=1.21.1 | ✅ boots | 3 |  |  | [shot](shots/chat-heads.png) |  |
| chatanimation | 1.21 | ✅ boots | 0 |  | ✅ boots | [shot](shots/chatanimation.png) |  |
| cherished-worlds | 1.21.1 | ✅ boots | 1 |  | ✅ boots | [shot](shots/cherished-worlds.png) |  |
| chipped | 1.21.1 | ❌ stall | 21 |  | ❌ crash |  | the game never starts loading the world with it (silent stall after registration) |
| chloride | 1.21.1 | ❌ crash | 110 |  | ❌ crash |  |  |
| chunky |  | ✅ boots | 8 |  | ❌ crash | [shot](shots/chunky.png) |  |
| cit-resewn | 1.21 | ✅ boots | 0 |  | ✅ boots |  |  |
| clumps | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/clumps.png) |  |
| cobblemon | 1.21.1 | ❌ crash | 239 |  | ❌ crash |  | 251 unresolved references across 33 removed classes; a rewrite, not a port |
| comforts | 1.21.1 | ❌ crash | 27 |  | ❌ crash |  | its SleepStatus mixin captures the server from a hook that no longer fires, so the field stays null |
| continuity | >=1.21 <=1.21.1 | ❌ crash |  |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/resources/model/BakedModel |
| controlify | 1.21.1 | ✅ boots | 12 |  | ❌ crash |  |  |
| controlling | 1.21.1 | ✅ boots | 3 |  | ✅ boots | [shot](shots/controlling.png) |  |
| crash-assistant | 1.20.2 | ✅ boots | 9 |  | ⏸ held (library missing) | [shot](shots/crash-assistant.png) |  |
| cubes-without-borders | 1.21 | ✅ boots | 16 |  | ✅ boots | [shot](shots/cubes-without-borders.png) |  |
| customskinloader |  | ✅ boots | 2 |  | ✅ boots | [shot](shots/customskinloader.png) |  |
| cut-through | 1.21.1 | ❌ crash | 264 |  | ❌ crash |  | puzzleslib's sound mixin captures a local that 26.2 no longer has at that point |
| debugify | 1.21.1 | ✅ boots | 12 |  | ❌ crash | [shot](shots/debugify.png) |  |
| default-options | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/default-options.png) |  |
| distanthorizons | 1.21.1 | ✅ boots | 33 |  | ✅ boots |  |  |
| drippy-loading | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | java.lang.NoClassDefFoundError: com/mojang/blaze3d/pipeline/RenderCall |
| duck | 1.21.1 | ❌ crash | 45 |  |  | [shot](shots/duck.png) | java.lang.NoSuchMethodError: 'void net.minecraft.world.entity.MobCategory.<init>(java.lang |
| dungeons-and-taverns |  | ❌ stall | 0 |  | ❌ crash |  | its loot conditions use the 1.21 time_check shape without the clock key 26.2 requires |
| dynamic-fps | 1.21.0 | ✅ boots | 4 |  | ✅ boots | [shot](shots/dynamic-fps.png) |  |
| dynamiccrosshair | 1.21.1 | ✅ boots | 9 |  | ❌ crash |  |  |
| e4mc |  | ✅ boots | 3 |  | ✅ boots | [shot](shots/e4mc.png) |  |
| eating-animation | >=1.21 | ❌ crash |  |  |  |  |  |
| ebe | 1.21 | ❌ crash | 22 |  | ❌ crash |  | needs Fabric's FabricBakedModelManager, removed with the model-loading rewrite |
| enchantment-descriptions | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/enchantment-descriptions.png) |  |
| enhancedvisuals | 1.21.1 | ❌ crash | 15 |  | ❌ crash |  | builds a post-processing chain whose constructor 26.2 replaced |
| entity-model-features | 1.21 | ❌ stall | 40 |  | ❌ crash |  | hooks every vanilla entity model's mesh builder (WolfModel.createMeshDefinition and friends), rebuilt in 26.2 |
| entityculling | 1.21.1 | ✅ boots | 1 |  | ❌ crash | [shot](shots/entityculling.png) |  |
| entitytexturefeatures | 1.21 | ❌ stall | 40 |  | ❌ crash | [shot](shots/entitytexturefeatures.png) | a @Shadow method whose static modifier no longer matches the 26.2 target; boots intermittently |
| euphoria-patches |  | ✅ boots | 6 |  | ✅ boots | [shot](shots/euphoria-patches.png) |  |
| fabrishot |  | ✅ boots | 8 |  | ✅ boots | [shot](shots/fabrishot.png) |  |
| fallingleaves |  | ✅ boots |  |  |  |  |  |
| fancymenu | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | gets past the removed render-call queue and the pack-metadata change, then stops in the post-processing chain |
| fast-ip-ping | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/fast-ip-ping.png) |  |
| fastquit |  | ✅ boots | 7 |  | ✅ boots | [shot](shots/fastquit.png) |  |
| ferrite-core |  | ✅ boots | 9 |  | ❌ crash | [shot](shots/ferrite-core.png) |  |
| freecam | 1.21 | ✅ boots | 18 |  | ✅ boots | [shot](shots/freecam.png) |  |
| friends-and-foes | >=1.21 | ❌ crash | 32 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/item/ArmorItem |
| handcrafted | 1.21.1 | ❌ stall | 21 |  | ❌ crash | [shot](shots/handcrafted.png) | model baking fails on its block models (translucency out of bounds) and the world never loads |
| highlightmobs | ~1.21.1 | ✅ boots | 2 |  |  | [shot](shots/highlightmobs.png) |  |
| inventory-profiles-next | 1.21.1 | ✅ boots | 16 |  | ❌ crash | [shot](shots/inventory-profiles-next.png) |  |
| jade | 1.21.1 | ❌ crash | 96 |  |  |  | Caused by: java.lang.NoSuchMethodError: 'void net.minecraft.world.phys.Vec3.<init>(org.jom |
| jei | 1.21.1 | ✅ boots | 68 |  | ❌ crash | [shot](shots/jei.png) |  |
| krypton |  | ✅ boots |  | ✅ boots |  | [shot](shots/krypton.png) |  |
| lambdynamiclights | 1.21.1 | ❌ crash | 0 |  | ❌ crash | [shot](shots/lambdynamiclights.png) | its LevelRenderer duck interface mixin cannot apply; renderer-tier |
| language-reload | 1.21.1 | ✅ boots | 11 |  | ✅ boots | [shot](shots/language-reload.png) |  |
| lighty | >=1.21.1 | ✅ boots | 15 |  |  | [shot](shots/lighty.png) |  |
| lithium | 1.21.1 | ✅ boots | 20 | ✅ boots | ❌ crash | [shot](shots/lithium.png) |  |
| llo | ~1.21.1 | ❌ crash | 1 |  |  |  |  |
| lmd | 1.21.1 | ✅ boots | 0 |  | ✅ boots | [shot](shots/lmd.png) |  |
| lootr |  | ❌ crash | 86 |  | ❌ crash | [shot](shots/lootr.png) | needs Fabric's BuiltinItemRendererRegistry (custom item renderers), removed with the 26.2 rendering rewrite |
| mixintrace |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/mixintrace.png) |  |
| modelfix | 1.21 | ✅ boots | 1 |  | ✅ boots | [shot](shots/modelfix.png) |  |
| modernfix | 1.21.1 | ❌ stall | 29 |  | ❌ crash | [shot](shots/modernfix.png) | texture-stitcher internals (Stitcher.SpriteLoader) changed shape in 26.2; renderer-tier |
| modmenu |  | ✅ boots | 2 |  | ✅ boots | [shot](shots/modmenu.png) |  |
| morechathistory |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/morechathistory.png) |  |
| mouse-tweaks | 1.21 | ✅ boots |  |  |  | [shot](shots/mouse-tweaks.png) |  |
| mouse-wheelie | 1.21.1 | ✅ boots | 14 |  | ❌ crash | [shot](shots/mouse-wheelie.png) |  |
| naturalist | ~1.21.1 | ❌ crash | 35 |  |  |  | Caused by: java.lang.NoSuchMethodError: 'net.minecraft.world.entity.EntityType net.minecra |
| natures-compass | 1.21.1 | ✅ boots | 7 |  | ❌ crash | [shot](shots/natures-compass.png) |  |
| netherportalfix | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/netherportalfix.png) |  |
| no-chat-reports | 1.21.1 | ✅ boots | 12 |  | ✅ boots | [shot](shots/no-chat-reports.png) |  |
| not-enough-animations | 1.21.1 | ✅ boots | 9 |  | ✅ boots | [shot](shots/not-enough-animations.png) |  |
| optigui | 1.21 | ✅ boots | 7 |  | ❌ crash | [shot](shots/optigui.png) |  |
| packet-fixer | 1.20.5 | ✅ boots | 0 |  | ✅ boots | [shot](shots/packet-fixer.png) |  |
| paginatedadvancements |  | ✅ boots | 5 |  | ✅ boots | [shot](shots/paginatedadvancements.png) |  |
| particle-rain | 1.21.1 | ❌ crash | 22 |  | ❌ crash |  | declares its own particle render type, which 26.2 sealed |
| physicsmod | 1.21.1 | ❌ stall | 131 |  | ❌ crash |  | renderer-tier: its own render pipeline reads shader resources 26.2 no longer ships |
| polymorph | 1.21.1 | ✅ boots | 13 |  | ❌ crash | [shot](shots/polymorph.png) |  |
| presence-footsteps | 1.21.1 | ✅ boots | 5 |  | ❌ crash | [shot](shots/presence-footsteps.png) |  |
| rei |  | ❌ stall | 109 |  | ❌ crash |  | needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the dependency before either tool runs |
| rrls |  | ✅ boots | 8 |  | ❌ crash | [shot](shots/rrls.png) |  |
| shulkerboxtooltip | >=1.21.1 | ❌ crash | 18 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/networking/v1/S2CPlayCh |
| sodium-dynamic-lights | 1.21.1 | ❌ stall | 0 |  | ❌ crash |  | no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal |
| sodium-options-api | 1.21.1 | ❌ stall | 0 |  | ❌ crash |  | no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options below 1.8.0, and Reese's 2.x requires Sodium 0.8, which rejects this add-on; both tools hit the same loader refusal |
| sodium-shadowy-path-blocks | 1.21.1 | ❌ crash | 4 |  | ❌ crash |  | needs Sodium, and Sodium itself cannot be ported: its particle mixin targets a class hierarchy 26.2 rewrote |
| sound | 1.21 / 1.21.1 | ❌ stall | 12 |  | ❌ crash |  | resolves an item tag during construction, which 26.2 forbids |
| sound-physics-remastered | 1.21 / 1.21.1 | ✅ boots |  |  |  |  |  |
| spark |  | ❌ crash |  | ❌ crash |  |  |  |
| supplementaries | 1.21.1 | ❌ crash | 253 |  | ❌ crash |  | moonlight's registry supplier implements Holder, which 26.2 sealed |
| terralith |  | ❌ stall | 0 |  | ❌ crash |  | its worldgen datapack uses the 1.21 JSON shapes (feature keys, carver lists) that 26.2's registry loader rejects |
| toms-storage | 1.21 | ❌ crash | 71 |  | ❌ crash |  | draws through Gui.layers (LayeredDraw), removed in 26.2's HUD rewrite |
| towns-and-towers | 1.21.1 | ❌ crash | 0 |  | ❌ crash |  | touches Fabric's internal ModNioResourcePack, which the 26.2 resource loader renamed |
| travelersbackpack |  | ❌ crash | 135 |  | ❌ crash |  | Cardinal Components attaches its containers from constructor injections whose parameter lists changed in 26.2; the handlers are stripped and the container is never created (run with Cardinal Components and Cloth Config present) |
| trinkets |  | ❌ crash | 11 |  | ❌ crash | [shot](shots/trinkets.png) | Cardinal Components attaches its containers from constructor injections whose parameter lists changed in 26.2; the handlers are stripped and the container is never created |
| veinminer | 1.21.1 | ✅ boots | 2 |  | ❌ crash | [shot](shots/veinminer.png) |  |
| veinminer-client | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/veinminer-client.png) |  |
| visual-workbench | 1.21.1 | ❌ crash | 12 |  | ❌ crash |  | puzzleslib's sound mixin captures a local that 26.2 no longer has at that point (same as cut-through); boots intermittently |
| visuality |  | ✅ boots | 5 |  | ✅ boots | [shot](shots/visuality.png) |  |
| wavey-capes | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/wavey-capes.png) |  |
| waystones | 1.21.1 | ✅ boots | 45 |  | ❌ crash | [shot](shots/waystones.png) |  |
| wthit | >=1.21-0 | ✅ boots | 27 |  | ❌ crash | [shot](shots/wthit.png) |  |
| xaeros-minimap | 1.21.1 | ❌ stall | 90 |  | ❌ crash | [shot](shots/xaeros-minimap.png) | draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier |
| xaeros-world-map | 1.21.1 | ❌ stall | 62 |  | ❌ crash | [shot](shots/xaeros-world-map.png) | draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier |
| yeetus-experimentus |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/yeetus-experimentus.png) |  |
| yosbr |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/yosbr.png) |  |
| yungs-better-dungeons |  | ✅ boots | 7 |  | ❌ crash | [shot](shots/yungs-better-dungeons.png) |  |
| yungs-better-end-island |  | ✅ boots | 13 |  | ❌ crash | [shot](shots/yungs-better-end-island.png) |  |
| yungs-better-jungle-temples |  | ✅ boots | 4 |  | ❌ crash | [shot](shots/yungs-better-jungle-temples.png) |  |
| yungs-better-mineshafts |  | ✅ boots | 4 |  | ✅ boots | [shot](shots/yungs-better-mineshafts.png) |  |
| yungs-better-nether-fortresses |  | ✅ boots | 4 |  | ✅ boots | [shot](shots/yungs-better-nether-fortresses.png) |  |
| yungs-better-ocean-monuments |  | ✅ boots | 1 |  | ✅ boots | [shot](shots/yungs-better-ocean-monuments.png) |  |
| yungs-better-strongholds |  | ✅ boots | 0 |  | ❌ crash | [shot](shots/yungs-better-strongholds.png) |  |
| yungs-better-witch-huts |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/yungs-better-witch-huts.png) |  |
| zoomify | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/zoomify.png) |  |

## Retromod head-to-head

Same mods, same instance, same base jars. Retromod ports on its first launch and asks for a
restart; the second launch is the verdict. Retromod 1.3.0-snapshot.10 for 26.2. Fox-Grade's
verdict requires the world to render and a screenshot to be taken; Retromod's requires the world
to load and the game to still be running.

| Mod | Fox-Grade | Retromod |
|---|---|---|
| 3dskinlayers | ✅ boots | ✅ boots |
| ambientsounds | ✅ boots | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| amendments | ❌ crash — moonlight's registry supplier implements Holder, which 26.2 sealed | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| appleskin | ✅ boots | ❌ crash |
| attributefix | ✅ boots | ❌ crash — Description: Unexpected error |
| badoptimizations | ✅ boots | ✅ boots |
| better-advancements | ✅ boots | ✅ boots |
| better-third-person | ✅ boots | ✅ boots |
| betterf3 | ✅ boots | ✅ boots |
| biomes-o-plenty | ❌ crash — passes registry keys where 26.2's villager types expect the type record; worldgen data cha | ❌ crash — Description: Bootstrap |
| carry-on | ✅ boots | ❌ crash — Description: Initializing game |
| chatanimation | ✅ boots | ✅ boots |
| cherished-worlds | ✅ boots | ✅ boots |
| chipped | ❌ stall — the game never starts loading the world with it (silent stall after registration) | ❌ crash — [23:02:26] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| chloride | ❌ crash | ❌ crash |
| chunky | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| cit-resewn | ✅ boots | ✅ boots |
| clumps | ✅ boots | ✅ boots |
| cobblemon | ❌ crash — 251 unresolved references across 33 removed classes; a rewrite, not a port | ❌ crash |
| comforts | ❌ crash — its SleepStatus mixin captures the server from a hook that no longer fires, so the field s | ❌ crash — Description: Initializing game |
| controlify | ✅ boots | ❌ crash — Description: Initializing game |
| controlling | ✅ boots | ✅ boots |
| crash-assistant | ✅ boots | ⏸ held (library missing) — mod under test never loaded (held or port failed) |
| cubes-without-borders | ✅ boots | ✅ boots |
| customskinloader | ✅ boots | ✅ boots |
| cut-through | ❌ crash — puzzleslib's sound mixin captures a local that 26.2 no longer has at that point | ❌ crash — Description: Bootstrap |
| debugify | ✅ boots | ❌ crash — Description: Initializing game |
| default-options | ✅ boots | ❌ crash — [07:32:44] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| distanthorizons | ✅ boots | ✅ boots |
| drippy-loading | ❌ crash — java.lang.NoClassDefFoundError: com/mojang/blaze3d/pipeline/RenderCall | ❌ crash — Description: Initializing game |
| dungeons-and-taverns | ❌ stall — its loot conditions use the 1.21 time_check shape without the clock key 26.2 requires | ❌ crash |
| dynamic-fps | ✅ boots | ✅ boots |
| dynamiccrosshair | ✅ boots | ❌ crash — Description: Unexpected error |
| e4mc | ✅ boots | ✅ boots |
| ebe | ❌ crash — needs Fabric's FabricBakedModelManager, removed with the model-loading rewrite | ❌ crash — Description: Initializing game |
| enchantment-descriptions | ✅ boots | ❌ crash — Description: Unexpected error |
| enhancedvisuals | ❌ crash — builds a post-processing chain whose constructor 26.2 replaced | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| entity-model-features | ❌ stall — hooks every vanilla entity model's mesh builder (WolfModel.createMeshDefinition and friend | ❌ crash |
| entityculling | ✅ boots | ❌ crash |
| entitytexturefeatures | ❌ stall — a @Shadow method whose static modifier no longer matches the 26.2 target; boots intermitte | ❌ crash |
| euphoria-patches | ✅ boots | ✅ boots |
| fabrishot | ✅ boots | ✅ boots |
| fancymenu | ❌ crash — gets past the removed render-call queue and the pack-metadata change, then stops in the po | ❌ crash — Description: Initializing game |
| fast-ip-ping | ✅ boots | ✅ boots |
| fastquit | ✅ boots | ✅ boots |
| ferrite-core | ✅ boots | ❌ crash — Caused by: java.lang.RuntimeException: java.lang.NoSuchFieldException: |
| freecam | ✅ boots | ✅ boots |
| handcrafted | ❌ stall — model baking fails on its block models (translucency out of bounds) and the world never lo | ❌ crash — Description: Initializing game |
| inventory-profiles-next | ✅ boots | ❌ crash — Description: Unexpected error |
| jei | ✅ boots | ❌ crash — Description: Unexpected error |
| lambdynamiclights | ❌ crash — its LevelRenderer duck interface mixin cannot apply; renderer-tier | ❌ crash — Description: Bootstrap |
| language-reload | ✅ boots | ✅ boots |
| lithium | ✅ boots | ❌ crash |
| lmd | ✅ boots | ✅ boots |
| lootr | ❌ crash — needs Fabric's BuiltinItemRendererRegistry (custom item renderers), removed with the 26.2  | ❌ crash — [23:03:38] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| mixintrace | ✅ boots | ✅ boots |
| modelfix | ✅ boots | ✅ boots |
| modernfix | ❌ stall — texture-stitcher internals (Stitcher.SpriteLoader) changed shape in 26.2; renderer-tier | ❌ crash |
| modmenu | ✅ boots | ✅ boots |
| morechathistory | ✅ boots | ✅ boots |
| mouse-wheelie | ✅ boots | ❌ crash — Description: Initializing game |
| natures-compass | ✅ boots | ❌ crash — Description: Initializing game |
| netherportalfix | ✅ boots | ❌ crash — [07:08:41] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| no-chat-reports | ✅ boots | ✅ boots |
| not-enough-animations | ✅ boots | ✅ boots |
| optigui | ✅ boots | ❌ crash — Description: Initializing game |
| packet-fixer | ✅ boots | ✅ boots |
| paginatedadvancements | ✅ boots | ✅ boots |
| particle-rain | ❌ crash — declares its own particle render type, which 26.2 sealed | ❌ crash — Description: Initializing game |
| physicsmod | ❌ stall — renderer-tier: its own render pipeline reads shader resources 26.2 no longer ships | ❌ crash — Caused by: java.lang.ClassNotFoundException: The specified mixin 'net. |
| polymorph | ✅ boots | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/class_2960 |
| presence-footsteps | ✅ boots | ❌ crash — Description: Initializing game |
| rei | ❌ stall — needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the  | ❌ crash |
| rrls | ✅ boots | ❌ crash — Caused by: java.lang.VerifyError: Bad local variable type |
| sodium-dynamic-lights | ❌ stall — no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options belo | ❌ crash |
| sodium-options-api | ❌ stall — no consistent 1.21.1 dependency set exists: Sodium 0.6 rejects Reese's Sodium Options belo | ❌ crash |
| sodium-shadowy-path-blocks | ❌ crash — needs Sodium, and Sodium itself cannot be ported: its particle mixin targets a class hiera | ❌ crash |
| sound | ❌ stall — resolves an item tag during construction, which 26.2 forbids | ❌ crash — Description: Initializing game |
| supplementaries | ❌ crash — moonlight's registry supplier implements Holder, which 26.2 sealed | ❌ crash — Caused by: java.lang.RuntimeException: Mixin transformation of net.min |
| terralith | ❌ stall — its worldgen datapack uses the 1.21 JSON shapes (feature keys, carver lists) that 26.2's r | ❌ crash — Description: Bootstrap |
| toms-storage | ❌ crash — draws through Gui.layers (LayeredDraw), removed in 26.2's HUD rewrite | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/class_2960 |
| towns-and-towers | ❌ crash — touches Fabric's internal ModNioResourcePack, which the 26.2 resource loader renamed | ❌ crash — java.lang.NoSuchMethodError: 'int net.minecraft.WorldVersion.getPackVe |
| travelersbackpack | ❌ crash — Cardinal Components attaches its containers from constructor injections whose parameter li | ❌ crash — Caused by: java.lang.NullPointerException: Cannot invoke "net.minecraf |
| trinkets | ❌ crash — Cardinal Components attaches its containers from constructor injections whose parameter li | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/world/level/GameRules$Ke |
| veinminer | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| veinminer-client | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| visual-workbench | ❌ crash — puzzleslib's sound mixin captures a local that 26.2 no longer has at that point (same as c | ❌ crash — Description: Bootstrap |
| visuality | ✅ boots | ✅ boots |
| wavey-capes | ✅ boots | ❌ crash — Description: Ticking entity |
| waystones | ✅ boots | ❌ crash — [20:22:44] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| wthit | ✅ boots | ❌ crash — Description: Initializing game |
| xaeros-minimap | ❌ stall — draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier | ❌ crash — Description: Initializing game |
| xaeros-world-map | ❌ stall — draws through ShaderInstance objects 26.2 no longer exposes; renderer-tier | ❌ crash — Description: Initializing game |
| yeetus-experimentus | ✅ boots | ✅ boots |
| yosbr | ✅ boots | ✅ boots |
| yungs-better-dungeons | ✅ boots | ❌ crash |
| yungs-better-end-island | ✅ boots | ❌ crash — Description: Exception in server tick loop |
| yungs-better-jungle-temples | ✅ boots | ❌ crash |
| yungs-better-mineshafts | ✅ boots | ✅ boots |
| yungs-better-nether-fortresses | ✅ boots | ✅ boots |
| yungs-better-ocean-monuments | ✅ boots | ✅ boots |
| yungs-better-strongholds | ✅ boots | ❌ crash |
| yungs-better-witch-huts | ✅ boots | ✅ boots |
| zoomify | ✅ boots | ❌ crash — Description: Initializing game |

Score: Fox-Grade 70 / Retromod 37 of 104 mods booting.

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.


## NeoForge

Fox-Grade ports NeoForge mods too, and on NeoForge it does it without a restart: the loader asks
registered locators for candidates while the mod set is still open, so a ported jar goes straight
into the launch that ported it.

40 of the most-downloaded mods with a real NeoForge build for 1.21.1, each run on a NeoForge
26.2 client launched into a world, graded by the same rule as everything above — the world starts
loading, the game is still up 8 seconds later, and the mod is in the loaded-mod list. NeoForge's own
26.2 libraries are supplied, except the real 26.2 build of whichever mod is under test.

**15 of 40 boot.**

| Mod | Result | Why not |
|---|---|---|
| 3dskinlayers | ✅ boots |  |
| appleskin | ❌ crash | java.lang.NoSuchFieldError: Class net.minecraft.client.gui.Gui does not have member field 'int leftHeight' |
| architectury-api | ❌ crash | java.lang.NoSuchMethodError: 'net.minecraft.client.gui.screens.inventory.AbstractContainerScreen net.neoforged |
| balm | ✅ boots |  |
| bookshelf-lib | ✅ boots |  |
| chat-heads | ✅ boots |  |
| cloth-config | ✅ boots |  |
| collective | ❌ stall | net.neoforged.fml.ModLoadingException: Loading errors encountered: |
| continuity | ⏸ held (library missing) | Missing or unsupported mandatory dependencies: |
| creativecore | ❌ stall | java.lang.NoClassDefFoundError: net/neoforged/neoforge/client/model/geometry/IGeometryLoader |
| dynamic-fps | ✅ boots |  |
| entity-model-features | ⏸ held (library missing) | Missing or unsupported mandatory dependencies: |
| entityculling | ❌ crash | java.lang.IllegalAccessError: class dev.tr7zw.entityculling.EntityCullingModBase tried to access private field |
| entitytexturefeatures | ✅ boots |  |
| fancymenu | ⏸ held (library missing) | Missing or unsupported mandatory dependencies: |
| ferrite-core | ❌ stall | java.lang.RuntimeException: java.lang.ExceptionInInitializerError |
| forge-config-api-port | ✅ boots |  |
| geckolib | ❌ crash | Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/resources/metadata/animation/AnimationMetadata |
| immediatelyfast | ❌ crash |  |
| jade | ❌ stall | java.lang.NoSuchFieldError: Class com.mojang.blaze3d.platform.InputConstants$Key does not have member field 'f |
| jei | ❌ stall | Failed to create mod instance. ModID: jei, |
| konkrete | ✅ boots |  |
| kotlin-for-forge | ❌ stall | java.lang.NoSuchMethodError: 'java.lang.String net.neoforged.neoforgespi.language.IModFileInfo.moduleName()' |
| lambdynamiclights | ❌ stall | Failed to start FML: java.lang.module.ResolutionException: Modules dev.yumi.commons.event and dev.yumi.commons |
| lithium | ❌ stall | Mixin apply for mod lithium failed lithium.mixins.json:block.fluid.flow.FlowingFluidMixin |
| melody | ✅ boots |  |
| modernfix | ❌ stall | client shut down during startup with no error logged |
| moreculling | ✅ boots |  |
| mouse-tweaks | ❌ stall | Failed to create mod instance. ModID: mousetweaks, |
| no-chat-reports | ✅ boots |  |
| not-enough-animations | ✅ boots |  |
| owo-lib | ❌ stall | Failed to start FML: java.lang.module.ResolutionException: Modules fabric_api_base and owo export package net. |
| puzzles-lib | ❌ stall | java.lang.NoClassDefFoundError: net/minecraft/client/renderer/entity/player/PlayerRenderer |
| simple-voice-chat | ❌ stall | net.neoforged.fml.ModLoadingException: Loading errors encountered: |
| sound-physics-remastered | ✅ boots |  |
| veinminer | ❌ stall | client shut down during startup with no error logged |
| veinminer-client | ⏸ held (library missing) | Missing or unsupported mandatory dependencies: |
| xaeros-minimap | ❌ stall | java.lang.NoSuchMethodError: 'int net.minecraft.nbt.IntTag.getAsInt()' |
| xaeros-world-map | ❌ stall | java.lang.NoSuchMethodError: 'int net.minecraft.nbt.IntTag.getAsInt()' |
| yacl | ✅ boots |  |

Reproduce with `tools/run-neoforge.sh`; the corpus is built by `tools/fetch-nf-corpus.py`.


## A second target version: 26.1.2

Everything above targets Minecraft 26.2. These are the same kind of mods ported for **26.1.2** instead,
on a 26.1.2 Fabric client launched into a 26.1.2 world, graded by the same rule.

**7 of 13 boot.**

The tables for a second target are derived from the ones already shipped and then checked against that
version's own class inventory, so a rename that does not resolve there is dropped and named rather than
shipped on the assumption that it is close enough. The shim package is compiled again per target, and a
shim that will not build for a version is absent for that version alone.

26.1.2 is **not a supported target**. `Targets` refuses it unless `-Dfoxgrade.target=26.1.2` opens it for
a single run, which is how these numbers were taken. Having tables is most of the work and none of the
evidence; a version joins the supported list on the strength of a table like this one, not before.

| Mod | Result | Why not |
|---|---|---|
| appleskin | ✅ boots |  |
| architectury-api | ✅ boots |  |
| balm | ❌ crash | Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_3665 |
| cloth-config | ✅ boots |  |
| dynamic-fps | ✅ boots |  |
| ferrite-core | ✅ boots |  |
| inventory-profiles-next | ⏸ held (library missing) |  |
| jei | ❌ crash | java.lang.NoClassDefFoundError: net/minecraft/class_4075 |
| no-chat-reports | ✅ boots |  |
| rei | ❌ stall |  |
| trinkets | ❌ crash | Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_151 |
| waystones | ✅ boots |  |
| xaeros-world-map | ❌ crash | java.lang.NoClassDefFoundError: net/minecraft/class_1921 |

Reproduce with `tools/run-2612.sh`.
