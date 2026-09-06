# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client (headless launch into a world,
screenshot at tick 220). **Boots** means the ported mod loaded and the game reached the world with
it; a screen or overlay test is linked where one ran. "Unresolved" is the number of classes,
fields and methods the port still references that 26.2 no longer has — the honest measure of
how much of the mod is reachable. Generated from the harness ledgers at commit `d8d7fa4`
on 2026-09-06; the scripts are in `tools/`.

| Result | Mods |
|---|---|
| ✅ boots | 33 |
| ❌ crash | 13 |
| ❌ stall | 1 |

## Per mod

| Mod | Built for | Fox-Grade (client) | Unresolved | Server | Retromod | Screenshot | Notes |
|---|---|---|---|---|---|---|---|
| almanac | 26.1 | ✅ boots |  |  |  | [shot](shots/almanac.png) |  |
| ambient-environment | 26.1 | ✅ boots |  |  |  | [shot](shots/ambient-environment.png) |  |
| animatica | >=1.21 | ✅ boots |  |  |  |  |  |
| appleskin | 1.21 | ✅ boots | 5 |  | ❌ crash | [shot](shots/appleskin.png) |  |
| attributefix |  | ✅ boots |  |  |  | [shot](shots/attributefix.png) |  |
| badoptimizations | 26.1 | ✅ boots |  |  |  | [shot](shots/badoptimizations.png) |  |
| better-mount-hud |  | ✅ boots |  |  |  | [shot](shots/better-mount-hud.png) |  |
| better-stats |  | ✅ boots |  |  |  | [shot](shots/better-stats.png) |  |
| betterchunkborders | 1.21.x / 26.1.x | ✅ boots | 1 |  |  | [shot](shots/betterchunkborders.png) |  |
| betterf3 | >=1.21 | ✅ boots | 25 |  | ✅ boots | [shot](shots/betterf3.png) |  |
| betterhurtcam | >=1.21 | ✅ boots |  |  |  |  |  |
| boat-item-view | >=1.21 <=1.21.1 | ✅ boots |  |  |  | [shot](shots/boat-item-view.png) |  |
| carpet | 1.21 | ✅ boots |  | ✅ boots |  | [shot](shots/carpet.png) |  |
| chalk | >=1.21.1 | ❌ crash | 7 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/level/block/state/propertie |
| chat-heads | >=1.21 <=1.21.1 | ✅ boots | 3 |  |  | [shot](shots/chat-heads.png) |  |
| continuity | >=1.21 <=1.21.1 | ❌ crash |  |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/client/resources/model/BakedModel |
| duck | 1.21.1 | ❌ crash | 45 |  |  | [shot](shots/duck.png) | java.lang.NoSuchMethodError: 'void net.minecraft.world.entity.MobCategory.<init>(java.lang |
| dynamic-fps |  | ✅ boots |  |  | ✅ boots | [shot](shots/dynamic-fps.png) |  |
| eating-animation | >=1.21 | ❌ crash |  |  |  |  |  |
| entityculling | 1.21.1 | ✅ boots |  |  | ❌ crash | [shot](shots/entityculling.png) |  |
| fallingleaves |  | ✅ boots |  |  |  |  |  |
| ferrite-core |  | ✅ boots | 13 |  | ❌ crash | [shot](shots/ferrite-core.png) |  |
| friends-and-foes | >=1.21 | ❌ crash | 32 |  |  |  |  |
| highlightmobs | ~1.21.1 | ✅ boots | 2 |  |  | [shot](shots/highlightmobs.png) |  |
| inventory-profiles-next |  | ✅ boots |  |  | ❌ crash | [shot](shots/inventory-profiles-next.png) |  |
| jade | 1.21.1 | ❌ crash | 96 |  |  |  | Caused by: java.lang.NoSuchMethodError: 'void snownee.jade.mixin.KeyAccess.setDisplayName( |
| jei | 1.21.1 | ❌ crash | 77 |  | ❌ crash |  |  |
| krypton |  | ✅ boots |  | ✅ boots |  | [shot](shots/krypton.png) |  |
| lambdynamiclights |  | ✅ boots |  |  |  | [shot](shots/lambdynamiclights.png) |  |
| lighty | >=1.21.1 | ✅ boots | 15 |  |  | [shot](shots/lighty.png) |  |
| lithium | 1.21.1 | ✅ boots |  | ✅ boots | ❌ crash | [shot](shots/lithium.png) |  |
| llo | ~1.21.1 | ❌ crash | 1 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/InteractionResultHolder |
| modmenu |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/modmenu.png) |  |
| mouse-tweaks | 1.21 | ✅ boots |  |  |  | [shot](shots/mouse-tweaks.png) |  |
| mouse-wheelie |  | ✅ boots |  |  |  | [shot](shots/mouse-wheelie.png) |  |
| naturalist | ~1.21.1 | ❌ crash | 35 |  |  |  |  |
| no-chat-reports | 1.21.1 | ✅ boots | 18 |  | ✅ boots | [shot](shots/no-chat-reports.png) |  |
| rei |  | ❌ stall | 235 |  | ❌ crash |  | needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the dependency before either tool runs |
| shulkerboxtooltip | >=1.21.1 | ❌ crash | 18 |  |  |  | java.lang.NoSuchMethodError: 'net.minecraft.nbt.ListTag net.minecraft.world.inventory.Play |
| sound-physics-remastered | 1.21 / 1.21.1 | ✅ boots |  |  |  |  |  |
| spark |  | ❌ crash |  | ❌ crash |  |  |  |
| trinkets |  | ✅ boots |  |  | ❌ crash | [shot](shots/trinkets.png) |  |
| waystones | 1.21.1 | ❌ crash | 50 |  | ❌ crash |  |  |
| wthit | >=1.21-0 | ❌ crash | 72 |  |  | [shot](shots/wthit.png) | Caused by: java.lang.NoClassDefFoundError: net/minecraft/resources/ResourceLocation$Serial |
| xaeros-minimap |  | ✅ boots |  |  |  | [shot](shots/xaeros-minimap.png) |  |
| xaeros-world-map |  | ✅ boots |  |  | ❌ crash | [shot](shots/xaeros-world-map.png) |  |
| zoomify | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/zoomify.png) |  |

## Retromod head-to-head

Same mods, same instance, same base jars. Retromod ports on its first launch and asks for a
restart; the second launch is the verdict. Retromod 1.3.0-snapshot.10 for 26.2. Fox-Grade's
verdict requires the world to render and a screenshot to be taken; Retromod's requires the world
to load and the game to still be running.

| Mod | Fox-Grade | Retromod |
|---|---|---|
| appleskin | ✅ boots | ❌ crash |
| betterf3 | ✅ boots | ✅ boots |
| dynamic-fps | ✅ boots | ✅ boots |
| entityculling | ✅ boots | ❌ crash |
| ferrite-core | ✅ boots | ❌ crash — Caused by: java.lang.RuntimeException: java.lang.NoSuchFieldException: |
| inventory-profiles-next | ✅ boots | ❌ crash |
| jei | ❌ crash | ❌ crash — Description: Unexpected error |
| lithium | ✅ boots | ❌ crash |
| modmenu | ✅ boots | ✅ boots |
| no-chat-reports | ✅ boots | ✅ boots |
| rei | ❌ stall — needs cloth-config built for 1.21.1 next to the instance's 26.2 build; Fabric rejects the  | ❌ crash |
| trinkets | ✅ boots | ❌ crash — java.lang.NoClassDefFoundError: net/minecraft/world/level/GameRules$Ke |
| waystones | ❌ crash | ❌ crash — [20:22:44] [Render thread/INFO]: [STDERR]: [Retromod] A mod entry poin |
| xaeros-world-map | ✅ boots | ❌ crash — Description: Initializing game |
| zoomify | ✅ boots | ❌ crash |

Score: Fox-Grade 12 / Retromod 4 of 15 mods booting.

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.
