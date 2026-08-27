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

## For maintainers: landing an approved PR

Verification decides what is *acceptable*; merging decides what actually *lands*. They are separate
steps because the rule set moves in between — an anti-fact may have been added, or an authoritative
mapping may now contradict a claim that was fine last week. So the merge re-runs every check that
depends on the current `rules.json` rather than trusting the verifier's verdict.

```bash
node verify-contrib.mjs contrib/their-rules.json --classpath "$MC_JARS"   # writes accepted-contrib.json
node merge-contrib.mjs                                                    # dry run: what would land
node merge-contrib.mjs --apply                                            # write it
```

| | |
|---|---|
| `--strict` | all-or-nothing: one rejected entry and none of the batch merges |
| `--classpath` / `--target` | the jars to check against, and which version they are |
| `--allow-unchecked` | land rules whose version jar you don't have (see below) |
| `--undo --apply` | remove every community rule ever merged |
| `--rules <file>` | operate on a copy instead of the live rule set |

Both tools share one gate (`contrib-gate.mjs`) so they cannot disagree about what is trustworthy.
An earlier split implementation drifted twice — CI was rejecting correct cross-version rules while
accepting self-maps and `__proto__` as a block name.

### Held is not accepted

A rule can only be checked against the jar for the version it produces names for; a 1.16.5 rule
checked against a 26.2 jar looks wrong when it isn't. Anything aimed at a version we have no jar for
is **held**, never accepted — the central claim, that the target class exists, was untested. CI
fetches a jar per version claimed (up to 8, and it names any it skipped). `--allow-unchecked` lands
held rules anyway; it exists for merging a CI-verified batch on a machine without the jars, and it
says what it's doing.

Several files can be merged at once (`node merge-contrib.mjs a.json b.json --apply`). If two entries
in one batch disagree about the same class, **both** are rejected — at most one can be right and
there is no way to tell which.

Three properties worth knowing:

- **The diff is small.** `rules.json` round-trips byte-for-byte through the writer, so merging three
  rules into a 23 MB file shows up in git as 31 added lines, not a whole-file reformat.
- **It is reversible.** Merged rules are tagged `source: "community:<proof>"`; `--undo` removes
  exactly those and restores the file byte-for-byte.
- **Contributed rules are never re-exported.** `export-learned.mjs` matches proofs by prefix, so
  `community:*` matches none of them. This is deliberate: it stops a rule that slipped through from
  being re-asserted by every install that merged it, which would make one mistake look like
  independent corroboration.

Merging writes a rolling `rules.json.bak` first, and writes atomically via a temp file, so an
interrupted merge cannot truncate the rule set.
