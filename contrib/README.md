# Contributing rules

Run `node export-learned.mjs --to <version> --out contrib/<your-name>.json`, then open a pull request.

Your file contains **only** `old class name → new class name` pairs plus how each was proven.
No mod source, no file names, no paths.

CI re-verifies every entry against the real Minecraft jars. A rule is accepted only if:

- its target class genuinely exists in the target version,
- it does not contradict Mojang/Fabric/MCPConfig mapping data,
- its source class is not a known-deleted class,
- and its proof is one we accept (compile-verified repair, jar resolution, official mappings,
  or a port a human actually shipped).

Inference-derived guesses are refused by the exporter and never leave your machine.
