# Fox-Grade

Port Minecraft mod source across versions — and get told plainly when it doesn't know.

**156,478 mappings**, derived from Mojang's own published data and verified by compiling against real
Minecraft jars. Not inferred, not guessed.

```bash
# no AI, no account, nothing to install beyond Node
node fox-grade.mjs ./src --from 1.16.5 --to 26.2 --rules-dry
```

## Why it's different

Most porting tools advertise instant AI conversion. This one publishes its error count.

| Measured against real human ports | |
|---|---|
| Mod–version pairs tested | 22 |
| Mappings checked | 1,192 |
| Correct / wrong | 1,180 / 12 |
| Package moves | 99.5% |
| True renames | 90.1% |
| Handled with **no AI at all** | ~79% |

Ground truth is what maintainers actually wrote when they ported the same mod — not self-assessment.
Raw results are in the repo.

The rule set briefly reached 1,995 entries until compiling revealed that ~800 were plausible-looking
inference. They were deleted. What remains is what could be proven.

## Three layers, only the last one costs anything

1. **Mapping tables** — free, no account. Mojang mappings joined on Fabric's stable intermediary names,
   plus MCPConfig for Forge-era sources. ~79% of real changes, instantly.
2. **Local model** — free, offline. `./setup-free-ai.sh` then `--foxai`. A 1 GB model handles most of
   the rest; it never needs to know Minecraft, because the tables supply the facts.
3. **Your own API key** — optional, for genuine API redesigns. No service in the middle.

## Verify against the real jars

```bash
node fox-grade.mjs ./src --from 1.16.5 --to 26.2 \
  --verify --classpath "$MC/versions/26.2/26.2.jar" --repair 3
```

Compiles the port, and on failure feeds the true signatures from `javap` back in to fix it.

## Patch a compiled jar, no source needed

```bash
node jar-remap.mjs mod.jar --from 1.16.5 --to 26.2 --classpath "$MC_JARS"   # report
node jar-remap.mjs mod.jar --from 1.16.5 --to 26.2 --classpath ... --out new.jar
```

Class, method and field names don't live in bytecode — they live in the constant pool, and the
instructions only hold indices into it. So a rename is a **string edit**, and nothing else in the
file refers to a byte offset. No decompiler, no javac, no ASM, no stack map recomputation, and no
access to the mod's build environment. It works on jars whose own code was obfuscated by their
author, because it only touches names that appear in the tables.

Bundled `META-INF/jars/` dependencies are remapped too. Entries it doesn't change keep their
original compressed bytes, so assets and configs stay bit-identical. The input is never written to.

| Measured | |
|---|---|
| Classes parsed across 28 production jars | 13,853 — 0 failures |
| Remapped classes accepted by `javap` | 260 / 260 |
| Round-trip recovery vs. known ground truth | 52 / 53 |
| Names invented that don't exist in the target | 1 |

A rename whose destination isn't in the target jars is **declined**, not written — that alone killed
two of the three bad names in the run above. The survivor is real ambiguity: `Biomes` maps to two
classes that both exist in 26.2.

**Check it before you launch it.** `jar-verify.mjs` resolves every method and field the jar reaches
for against the real target jars, walking superclasses the way the JVM does. Each unresolved link is
a crash waiting on that code path.

```bash
node jar-verify.mjs ported.jar --classpath "$MC_JARS"
```

On an unmodified mod built for the target it reports 87/87 links resolved — that control is what
makes a non-zero count meaningful.

**A worked example, including the part that failed.** AppleSkin 1.21.1 → 26.2: 87.8% of type
references remapped and all 315 member tokens resolved, but verification found **72 links that would
still crash**. 26.2 rewrote rendering (`GuiGraphics` is gone, 17 links into it), turned
`InteractionResult` from an enum into an interface, and changed `ClickEvent`'s constructor. No
renaming fixes any of that — it is exactly the Tier 2 work described above. The mod is not portable
by remapping alone, and the tool says so instead of writing a jar that loads and then dies.

**What this does not prove.** No remapped mod has been launched in-game yet. Link resolution and
`javap` prove a jar is well-formed and internally consistent, not that the port behaves correctly. Renaming also cannot touch a genuine API redesign, and
a mixin whose target method was deleted needs a new injection point, not a new name — both are
reported rather than guessed at. Patch jars you own, for yourself; most mod licences don't permit
redistributing a modified build.

## Tier 2: what renaming cannot express

Some changes are not renames. In 26.2 `GuiGraphics` was not moved — it was dissolved into
`GuiGraphicsExtractor`, `Hud` and `RenderPipelines`, and `MultiBufferSource` became
`SubmitNodeCollector`. One class becoming three is not something a rename table can hold.

```bash
node jar-tier2.mjs remapped.jar --classpath "$MC_JARS" --to 26.2 --dry   # what it would attempt
```

It decompiles **only** the class files that still have broken links, so lossy decompilation cannot
touch code that already worked, and gives the model the mined advisories plus the real `javap`
signatures of the replacement API. Ports are limited to method bodies: every other class in the jar
is still compiled bytecode linking to the exact existing signatures.

Three things are refused rather than attempted:

- **Mixins.** A mixin whose target method was deleted needs a new injection point. That is a design
  decision, not a repair.
- **Signature-level blockers.** If a class that no longer exists appears in the file's own method
  signatures, no body edit can fix it — the signature must change, and callers across the jar link
  to the old one. Detected up front, so it costs no model calls.
- **Ports that delete the feature.** See below.

### Compiling is not porting

The obvious gates — javac accepts it, and the link checker shows fewer broken links — are both
satisfiable by *deleting the broken code*. On AppleSkin the model did exactly that: `isRotten()`
became a constant `false` and the natural-regeneration check began assuming the vanilla default.
Both gates went green while the mod quietly stopped working.

The model was not misbehaving; it was told not to invent APIs, and it left precise notes about what
was missing. The gates were wrong. A broken link at least crashes where it is reached — loud and
traceable. A stub returns plausible wrong data forever.

So a port that removes behaviour is **refused by default**, the original bytecode is kept, and what
would have been lost is printed. `--allow-stubs` overrides it, and still names every stub.

Measured on AppleSkin 1.21.1 → 26.2, all 8 files with broken links:

| Outcome | Files |
|---|---|
| Refused — the port removed behaviour instead of porting it | 4 |
| Signature-blocked (`GuiGraphics` in the file's own parameters) | 2 |
| Never compiled / no decompiled source | 2 |
| **Genuinely ported** | **0** |

Zero. And the reason is specific rather than a limit of the approach: 26.2 stopped exposing
`FoodData.getExhaustionLevel()`, `setExhaustion()` and `FoodProperties.effects()` — precisely the
data AppleSkin exists to display. The mod needs redesigning against a new API, which is not porting.

That gap also spans a whole rendering rewrite. A narrower hop is a fairer test of the machinery and
has not been run yet, so treat this as one honest data point, not a verdict.

## What it won't do

- **Invent replacements.** When an API was deleted rather than renamed, it says so and explains the
  new pattern instead of guessing a class that compiles but is wrong.
- **Handle pre-1.14 confidently.** Mojang published no mappings before 1.14.4; coverage drops to ~65%
  there. It declines rather than guesses, so accuracy holds.
- **Replace testing.** Compiling clean is necessary, not sufficient. Every port ships a report.

## Rules get better as people use it

Every install can contribute what it discovers — see [`contrib/`](contrib/README.md). Submissions carry
proof, contain only class-name pairs, and are re-verified by CI against the real jars before merging.

Merging is a second gate, not a formality: `merge-contrib.mjs` re-runs every check that depends on the
current rule set, refuses a batch that disagrees with itself, tags what it lands so it can be reverted
in one command, and deliberately never re-exports a contributed rule — so one mistake can't come back
looking like independent corroboration.

## Licence

MIT — see [LICENSE](LICENSE). Data sources and their terms: [NOTICE.md](NOTICE.md).
