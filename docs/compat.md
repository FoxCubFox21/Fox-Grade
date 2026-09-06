# Fox-Grade compatibility results

Every mod the harness has run, on a real Minecraft 26.2 client (headless launch into a world,
screenshot at tick 220). **Boots** means the ported mod loaded and the game reached the world with
it; a screen or overlay test is linked where one ran. "Unresolved" is the number of classes,
fields and methods the port still references that 26.2 no longer has — the honest measure of
how much of the mod is reachable. Generated from the harness ledgers at commit `03fe521`
on 2026-09-06; the scripts are in `tools/`.

| Result | Mods |
|---|---|
| ✅ boots | 26 |
| ❌ crash | 11 |

## Per mod

| Mod | Built for | Fox-Grade (client) | Unresolved | Server | Retromod | Screenshot | Notes |
|---|---|---|---|---|---|---|---|
| almanac | 26.1 | ✅ boots |  |  |  | [shot](shots/almanac.png) |  |
| ambient-environment | 26.1 | ✅ boots |  |  |  | [shot](shots/ambient-environment.png) |  |
| animatica | >=1.21 | ✅ boots |  |  |  |  |  |
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
| eating-animation | >=1.21 | ❌ crash |  |  |  |  |  |
| entityculling | 1.21.1 | ✅ boots |  |  | ❌ crash | [shot](shots/entityculling.png) |  |
| fallingleaves |  | ✅ boots |  |  |  |  |  |
| friends-and-foes | >=1.21 | ❌ crash | 32 |  |  |  |  |
| highlightmobs | ~1.21.1 | ✅ boots | 2 |  |  | [shot](shots/highlightmobs.png) |  |
| jade | 1.21.1 | ❌ crash | 96 |  |  |  | Caused by: java.lang.NoSuchMethodError: 'void snownee.jade.mixin.KeyAccess.setDisplayName( |
| krypton |  | ✅ boots |  | ✅ boots |  | [shot](shots/krypton.png) |  |
| lambdynamiclights |  | ✅ boots |  |  |  | [shot](shots/lambdynamiclights.png) |  |
| lighty | >=1.21.1 | ✅ boots | 15 |  |  | [shot](shots/lighty.png) |  |
| lithium | 1.21.1 | ✅ boots |  | ✅ boots | ❌ crash | [shot](shots/lithium.png) |  |
| llo | ~1.21.1 | ❌ crash | 1 |  |  |  | Caused by: java.lang.NoClassDefFoundError: net/minecraft/world/InteractionResultHolder |
| modmenu |  | ✅ boots | 0 |  | ✅ boots | [shot](shots/modmenu.png) |  |
| mouse-tweaks | 1.21 | ✅ boots |  |  |  | [shot](shots/mouse-tweaks.png) |  |
| mouse-wheelie |  | ✅ boots |  |  |  | [shot](shots/mouse-wheelie.png) |  |
| naturalist | ~1.21.1 | ❌ crash | 35 |  |  |  |  |
| shulkerboxtooltip | >=1.21.1 | ❌ crash | 18 |  |  |  | java.lang.NoSuchMethodError: 'net.minecraft.nbt.ListTag net.minecraft.world.inventory.Play |
| sound-physics-remastered | 1.21 / 1.21.1 | ✅ boots |  |  |  |  |  |
| spark |  | ❌ crash |  | ❌ crash |  |  |  |
| wthit | >=1.21-0 | ❌ crash | 72 |  |  | [shot](shots/wthit.png) | Caused by: java.lang.NoClassDefFoundError: net/minecraft/resources/ResourceLocation$Serial |
| xaeros-minimap |  | ✅ boots |  |  |  | [shot](shots/xaeros-minimap.png) |  |
| zoomify | 1.21.1 | ✅ boots | 0 |  | ❌ crash | [shot](shots/zoomify.png) |  |

## Retromod head-to-head

Same five mods, same instance, same base jars, two launches each (Retromod ports on the first and
asks for a restart). Retromod 1.3.0-snapshot.10 for 26.2.

| Mod | Fox-Grade | Retromod |
|---|---|---|
| Mod Menu | boots; mod-list screen renders fully | boots; screen fails to open (`I18n.exists`, `render`→`extract` rename missing) |
| Zoomify + YACL | boots | crash (reload-listener signature) |
| BetterF3 | boots | boots |
| Lithium | boots | crash (mixin targets changed) |
| Entity Culling | boots | crash (renderer mixin) |

Reproduce with `batch2/run-retromod.sh` next to the Fox-Grade harness scripts.
