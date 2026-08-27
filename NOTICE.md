# Third-party data and attributions

Fox-Grade's mapping tables are **derived from published data**, not copied source.

| Source | Used for | Terms |
|---|---|---|
| Mojang official mappings | class + member names per version | Mojang's mapping licence — free to use for modding; **not redistributable verbatim**, so Fox-Grade ships only *derived* old→new pairs |
| Fabric **intermediary** | stable IDs that join versions together | Apache-2.0 |
| Fabric **Yarn** | Yarn→mojmap scheme translation | Apache-2.0 |
| **MCPConfig** (Forge) | SRG→mojmap for pre-1.17 sources | LGPL-2.1 / Forge terms — used as *input data*, no code included |
| Public mod repositories | ground truth mined from human ports | each repo's own licence; only *facts about renames* are retained, never code |
| `qwen2.5-coder` 0.5–32B | optional local AI backend (downloaded by the user, not bundled) | Apache-2.0 |
| `tiny-remapper` | optional bytecode remapping | Apache-2.0 |

## If Retromod code is ever used

[Retromod](https://github.com/Bownlux/Retromod) is MIT-licensed. Any use of its code
requires reproducing its copyright notice and MIT text here, and saying clearly which
parts came from it. As of now **no Retromod code is included** — Fox-Grade solves a
different problem (source porting vs runtime bytecode patching).
