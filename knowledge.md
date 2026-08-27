
<!-- ItemGem.java  Forge 1.7.10 -> Fabric 26.2 -->
## Learned
- 1.7.10 MCP: `func_77667_c(ItemStack)` = `Item#getUnlocalizedName(ItemStack)`; `func_150895_a(Item, CreativeTabs, List)` = `Item#getSubItems`. Both were removed by the flattening/creative-tab rewrites and have no direct modern counterpart.
- Pre-1.13 numeric item id **264 = `minecraft:diamond`** (`Items.DIAMOND`).
- Pre-1.13 metadata subtypes on an item always become N separate registered items post-1.13; each needs its own model JSON, texture, lang key, and (1.21.4+) item-definition JSON.
- Asset path rename at 1.13: `assets/<ns>/textures/items/` (plural) -> `assets/<ns>/textures/item/` (singular). Same for `models/item`.
- Default item translation key format is `item.<namespace>.<path>` derived from the registry id; `setUnlocalizedName` no longer exists (use `Item.Properties#overrideDescription` in recent versions if a custom key is required).
- Creative tab mapping: 1.7.10 `CreativeTabs.tabMaterials` -> modern `CreativeModeTabs.INGREDIENTS`; since 1.19.3 items don't declare their tab, contents are contributed via events (Fabric: `ItemGroupEvents.modifyEntriesEvent(key).register(entries -> entries.accept(item))`).
- Since 1.21, `new ResourceLocation(ns, path)` is private — use `ResourceLocation.fromNamespaceAndPath(ns, path)` or `ResourceLocation.parse(str)`. **VERSION CAVEAT:** this class is named `ResourceLocation` only up to ~1.21.x; in **MC 26.2 (mojmap) it was RENAMED to `net.minecraft.resources.Identifier`** (same package, same `fromNamespaceAndPath`/`of` static factories). Pick the class name that matches the TARGET version — do not assume `ResourceLocation` exists in 26.2+.
- Since ~1.21.2, every `Item` must be built with `new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))`, and that key must match the one used in `Registry.register(BuiltInRegistries.ITEM, key, item)`.
- 1.21.4 added client "item definition" files at `assets/<ns>/items/<name>.json` that select the model; the `models/item/<name>.json` file is still needed as the model itself.
- `ItemStack` lost its damage/meta constructor argument at 1.13: `new ItemStack(item, count, meta)` -> `new ItemStack(item, count)` plus a data component / distinct item for what the meta encoded.
- FML `GameRegistry.registerItem(item, name)` maps to `Registry.register(BuiltInRegistries.ITEM, ...)` on Fabric (Mojmap); the old `cpw.mods.fml` package prefix is 1.7.10-era Forge only.

<!-- en_US.lang  1.7.10 -> 26.2 -->
## Learned

- Pre-1.13 lang files are `assets/<modid>/lang/en_US.lang` with `key=value` lines; 1.13+ use `assets/<modid>/lang/en_us.json` with a JSON object and lowercase locale codes.
- Translation key scheme changed in 1.13: `tile.<name>.name` → `block.<modid>.<name>`, `item.<name>.name` → `item.<modid>.<name>`; the `.name` suffix is dropped and the mod id becomes the middle segment.
- `itemGroup.<modid>` still works as a creative-tab key in modern versions only because the mod explicitly passes that translatable component as the tab title — it is not derived automatically like block/item keys.
- The 1.13 Flattening made texture folders singular: `textures/items/` → `textures/item/`, `textures/blocks/` → `textures/block/`; registry names and file names must be lowercase snake_case.
- Renaming a registry id to snake_case during a port silently breaks every translation key, model path, blockstate filename, recipe and loot-table reference tied to the old id — decide once and update all of them together.
- Every modern item needs `models/item/<name>.json`; every block needs `blockstates/<name>.json` + a block model + an item model that parents to the block model.
- 1.21.4+ added `assets/<modid>/items/<name>.json` client item definitions as a layer above item models; check whether the target version still requires them.
- `pack_format` changes nearly every release — never guess it; use a placeholder and flag it loudly.
- Duplicate/extra unused translation keys in a lang JSON are harmless, so shipping a fallback key for an uncertain registry id is a safe hedge (but JSON forbids two identical keys, so the ids must actually differ).

<!-- VERIFIED against the real MC 26.2 jars (javac-compiled clean), mbe01_block_simple 1.8 -> Fabric 26.2, 2026-08-24 -->
## Learned (MC 26.2, mojmap — compile-verified)
- **`net.minecraft.resources.ResourceLocation` was renamed to `net.minecraft.resources.Identifier` in 26.2.** The static factories are unchanged: `Identifier.fromNamespaceAndPath(ns, path)` and `Identifier.of(...)`. `ResourceKey.create(Registries.X, Identifier)` takes the new type. (This is the exact rename a pure-LLM port gets wrong for an unseen version; the compile-repair loop can only recover it when given the package's real class inventory.)
- `net.minecraft.block.material.Material` was DELETED (around 1.20). A block's old `Material.rock`/etc. is now expressed via `BlockBehaviour.Properties`: `.mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).sound(SoundType.STONE)`.
- A `Block` subclass MUST override `codec()` returning a `MapCodec` since 1.20.5; `Block.simpleCodec(Ctor::new)` works for a properties-only constructor. Modern blocks take a `BlockBehaviour.Properties` constructor arg (no more no-arg constructor).
- Since ~1.21.2 a block's `Properties` must carry `.setId(ResourceKey.create(Registries.BLOCK, id))`, matching the key used in `Registry.register(BuiltInRegistries.BLOCK, id, block)`; the game throws at registration otherwise.
- 1.8's `Block` render/occlusion hooks are gone and are now defaults derived from properties/shape: `getBlockLayer()`+`EnumWorldBlockLayer` (removed), `isOpaqueCube()`, `isFullCube()`, and `getRenderType()` (the old magic `3` = `RenderShape.MODEL`) all deleted — usually just drop them.
- 1.8 `GameRegistry.registerBlock` also created the `ItemBlock`; on modern Fabric register the `BlockItem` yourself: `Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))))`, then add it to a tab via `ItemGroupEvents`.
