# Fox-Grade

Port Minecraft mods across versions — source or compiled jars — and get told plainly when it
doesn't know.

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

## The narrow-hop test: where the real work turns out to be

Wide gaps break classes. Narrow ones do not — and that changes what a porter needs.

Testing 18 mods from **26.1 → 26.2** (one Minecraft release apart, no rendering rewrite in between):

| | |
|---|---|
| Unported 26.1 jars on 26.2 | 1–18 broken links each |
| Class renames mined from 18 jar pairs | 4 |
| Class renames that applied to any test mod | **0** |
| Broken links fixed by Tier 1 | **0** |

Every failure was a **member** change: `Minecraft.setScreen` → `setScreenAndShow`,
`Gui.getGuiTicks()` removed, `net.minecraft.util.Tuple` deleted. Class-level remapping is a no-op on
exactly the hops most people want, because Mojang barely moves classes between point releases.

`member-mine.mjs` diffs two Minecraft jars directly and recovers member renames — 98 across 26.1→26.2,
71 methods and 27 fields, including a systematic drop of `get` prefixes (`getMainCamera` →
`mainCamera`, 23 such). Unlike SRG or intermediary tokens, readable member names are **not** globally
unique — `tick` exists on hundreds of classes — so every rule is keyed by `(owner, name, descriptor)`.

Four gates, each added because the previous version produced a specific wrong answer:

| Gate | What it killed |
|---|---|
| Descriptor unique across the whole class | `closed → canPersistentMap` (every boolean shares `Z`) |
| Name overlap must corroborate | `tick → setClientLevelTeardownInProgress` |
| No two members may claim one destination | `FOG_SNIPPET` and `MATRICES_PROJECTION_SNIPPET` both → `WORLD_TEXT_SNIPPET` |
| Primitive-typed fields need the strict path | `elementsMask → MAX_VERTEX_ELEMENTS` |

**One known error survives** in the 98: `FOG_SNIPPET → WORLD_TEXT_SNIPPET`, matched on the shared
`_SNIPPET` suffix. Tightening further costs correct pairs like `updateNarration →
updateWidgetNarration`, so it is documented rather than tuned away.

### Applying them

A class name is globally unique in a constant pool, so rewriting its UTF-8 entry in place is sound.
A member name is not — `setScreen` may be a method on three unrelated classes and a string literal
besides, all sharing one entry. So nothing is edited in place: for each call site whose
`(owner, name, descriptor)` matches, a new `NameAndType` is appended to the pool and only that
reference is repointed. Everything after the pool is copied byte for byte.

The result is checked by the JVM itself — every rewritten class is fed to a `ClassLoader` and must
define without `ClassFormatError` or `VerifyError`. 260 classes across four mods: zero malformed.

| 26.1 → 26.2 | call sites rewritten | broken links |
|---|---|---|
| freecam | 1 | 7 → 6 |
| entityculling | 2 | 15 → 13 |
| moreculling | 1 | 14 → 13 |
| appleskin, bobby, continuity | 0 | unchanged |

Modest, and the reason is the interesting part. What remains is not renames the table missed —
`Minecraft.getChatListener()`, `getToastManager()`, `renderBuffers()` and `Gui.getGuiTicks()` have no
counterpart in 26.2 at all. They were removed, not renamed, and no mapping fixes that.

### When you want the guess anyway

Refusing outright is the right default and the wrong only option — it leaves you with a jar that
crashes rather than one worth testing. `--best-guess` applies the uncertain matches too:

```bash
node jar-remap.mjs mod.jar --from 26.1 --to 26.2 --classpath "$MC" --best-guess --out new.jar
```

The rule is that a guess is never silent. Every one is written to `new.guesses.md` beside the jar,
with the candidates it beat and their scores:

```
## net.minecraft.client.renderer.state.level.LevelRenderState.haveGlowingEntities
- descriptor: `Z`
- **chosen: shouldShowEntityOutlines** (5 characters shared)
- why not certain: 4 members in the new class share this descriptor
- rejected: render3dCrosshair (2), shouldResetChunkLayerSampler (2), shouldResetSkyRenderer (2)
```

That one is almost certainly right — glowing entities *are* the outline effect — which the character
score alone could never tell you. A human reading the report can see that in a second; the miner
cannot. That is the point of the report.

483 such candidates exist for 26.1 → 26.2 against 72 confident rules. They buy a little: bobby
5 → 4 broken links, freecam 6 → 5. Not more, because most of what remains was never a rename —
`Gui.getGuiTicks()` has no counterpart in 26.2 at all, so no setting recovers it.

**The confident table still refuses to guess.** `Minecraft.setScreen` has three same-descriptor candidates in 26.2,
so it is reported as unresolvable rather than matched to the best-scoring name. An earlier version
did guess: `ClientChunkCache.getLoadedEmptySections()` became four methods in 26.2 —
added/removed × sections/chunks — and name similarity picked `addedEmptySections` (16 characters
shared) over `removedEmptySections` (15). It links cleanly and means the opposite. That is the same
silently-wrong failure Tier 2 refuses, so it is refused here too.

## A member that looks deleted is often just moved

`Gui.getGuiTicks()` appears to be gone from 26.2, so a within-class diff files it under "removed, no
replacement". It was not removed. It moved to a new `Hud` class and is reached through a field:

```java
26.1:  gui.getGuiTicks()
26.2:  gui.hud.getGuiTicks()
```

Same name, same descriptor, one field hop away. `member-mine.mjs` detects this — method gone from
class A, present on class B with the same descriptor, and A holds exactly one field of type B —
and found **71 such relocations** across 26.1 → 26.2, all previously written off as unfixable.

They cannot be applied as renames. Inserting a `getfield` before the call shifts every later
bytecode offset and invalidates the stack map frames. But in source it is a one-token edit, so the
relocation is handed to Tier 2 as a precise instruction rather than something to guess at.

### Porting a jar is more than porting its code

Four things broke a jar that had already passed every check, and none were about mappings:

| | why it passed anyway |
|---|---|
| Data-descriptor flag left set on copied entries | `unzip -t` and any central-directory reader accept it; Fabric streams nested jars with `ZipInputStream`, which does not |
| `fabric.mod.json` still declaring the old version range | the bytecode was checked; the manifest never was |
| A mixin retargeted at a delegate class | one relocated method is not the whole mixin moving |
| The guard against that, blind to fields | it collected method names only, and the shadow was a field |

The jars are now validated the way the consumer reads them — streamed, recursing into nested jars —
because reading a file correctly through one API says nothing about another.

### Link checking is blind to mixins

A mixin does not call its target — it names it as a string inside an annotation. Constant-pool link
resolution cannot see that, so a jar this project had already declared clean crashed on launch:

```
@Inject on renderFoodPre could not find any targets matching 'extractFood' in Gui
```

Same relocation as before: `Gui.extractFood` and `Gui.extractHearts` had moved to `Hud`, so the
mixin had to target `Hud`. `mixin-check.mjs` reads every mixin config, verifies each injection point
still exists, and uses the relocation table to say what to do about it:

```bash
node mixin-check.mjs mod.jar --classpath "$MC" --out fixed.jar
```

Retargeting is a single constant edit — `@Mixin` stores its target once, and the exact-string match
means `GuiGraphicsExtractor` cannot be hit by accident. It only fires when **every** broken target in
a mixin moved to the same class; split across two, it says so and stops, because that means splitting
the mixin, which is a decision.

### The result: a mod that verifies clean



AppleSkin 26.1 → 26.2, its one remaining broken link:

| | |
|---|---|
| Broken links | 1 → **0** |
| Behaviour stubbed out | **0** |
| Classes rejected by the JVM loader | **0** of 98 |
| Minecraft members called, vs the author's own 26.2 build | **30 of 30 identical** |

That last row is the one that matters. The port independently arrived at exactly the API the
maintainer used, verified against their real build — not by copying it, but from the relocation
table and a compile loop.

**It runs, and it behaves.** Launched on 26.2: the saturation overlay renders on the hunger bar,
held food previews what it would restore, and the F3 additions are present. All three of the mod's
features, not just the one that proved it loaded. That is one mod across one narrow hop, not a general claim, but it is the whole pipeline
working end to end on a compiled jar: intermediary→mojmap translation, class remapping, member
remapping, a relocation found by diffing two game jars, one source-level fix compiled back in, and a
mixin retargeted at the class its injection point moved to.

No source, no build environment, no maintainer involvement.

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

### Injection points that were merely renamed

A mixin names its target method as a plain string in an annotation, not as a method reference. So
member remapping never saw it, and a rename sitting in the table went unapplied while Fabric refused
to start:

```
@Redirect on redirectGetViewXRot: no target 'renderHandsWithItems' in ItemInHandRenderer
```

`ItemInHandRenderer.renderHandsWithItems → submitHandsWithItems` was already known. It just was not
written into the annotation. Rewriting those selectors fixes **28 of 168** mixin problems across the
corpus (16.7%).

The remaining 140 are genuinely deleted hooks, and no table fixes those: choosing a new injection
point means understanding what the mod is trying to do.

### The corpus, honestly

18 mods, 26.1 → 26.2, everything applied:

| verdict | count |
|---|---|
| **CLEAN** — would launch | **0** |
| **Links only** — mixins fine, some references broken | 6 |
| **Blocked** — injection points gone | 12 |

Iris alone accounts for 78 broken injection points, sodium 19, immediatelyfast 10. The split is
about what a mod *does*: mods that read game state and draw an overlay are reachable; mods that
modify rendering are not, because 26.2 rewrote rendering and every hook into it is a design
decision.

One mod has been ported end to end and verified in game: AppleSkin, whose single remaining broken
link was closed by Tier 2. That is 1 of 18.

**An earlier version of this table was wrong.** It reported 77 mixin problems where there are 167,
because the checker only flagged failures it could explain via a relocation and stayed silent on
anything simply deleted. A check that cannot observe a failure mode reports success.

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
