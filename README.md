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

**Two mods run, and behave.** AppleSkin on 26.2: saturation overlay on the hunger bar, held food
previewing what it restores, F3 additions present — all three features, not just the one that proved
it loaded. Continuity: connected textures joining correctly.

Continuity is the more interesting of the two, because its last blocker could not be settled by
evidence. `LevelRenderer.allChanged()` is gone in 26.2, and 16 corpus mods that called it did not
agree on a replacement — only one switched to anything comparable. The method bodies decided it:
`resetLevelRenderData()` shares three of its four calls with the old `allChanged()`. Recorded as a
hand-verified override with the caveat that `allChanged()` did strictly more — it also rebuilt
`ViewArea` and `SectionRenderDispatcher` and re-read render-distance options. That caveat still
stands; it simply does not matter for a texture mod forcing a re-render. Running it is what confirmed
the judgement, which is why the override files carry their reasoning rather than just an answer. That is one mod across one narrow hop, not a general claim, but it is the whole pipeline
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

### Deleted hooks: read the answer out of the maintainer's own port

Choosing a new injection point does require knowing what the mod is trying to do — but that decision
has already been made, in the maintainer's released build for the target version. `mixin-mine.mjs`
diffs each mixin class across a mod's two versions and reads the substitution back out.

A single mod's choice is that author's decision. The **same** substitution across unrelated mods is a
fact about the game, and only those apply generally:

| corroborated | mods agreeing |
|---|---|
| `LevelRenderer.renderLevel → render` | 6 |
| `Minecraft.destroy → exitWorldAndClose` | 4 |
| `LevelRenderer.renderBlockOutline → submitBlockOutline` | 3 |
| `ItemInHandRenderer.renderHandsWithItems → submitHandsWithItems` | 2 |

Two guards were needed. A first pass paired every removed hook with whatever arrived in the same
mixin, producing `setScreen → exitWorldAndClose` and `getMainCamera → render` — arbitrary matches
from mixins where several hooks changed at once. Only a clean 1:1 counts now, and a substitution that
contradicts a rename derived from the game jars themselves is dropped, since diffing the game is
stronger evidence than one mod's mixin.

Applied across the corpus this takes mixin problems from **168 to 121 (28% fixed)**: iris 90 → 68,
sodium 22 → 15, xaeros 8 → 3, and bobby to zero. It scales with the corpus — every additional pair of
mod versions is more mined decisions.

### The corpus, honestly

18 mods, 26.1 → 26.2, everything applied:

| verdict | count |
|---|---|
| **CLEAN** — would launch | **0** |
| **Links only** — mixins fine, some references broken | 7 |
| **Blocked** — injection points gone | 11 |

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

## Content mods fail differently, and exposed a missing diff

Everything above was tested on client mods — overlays, rendering, HUD. Content mods that add items,
blocks and entities have a different profile: almost no mixins, and their breakage is **class package
moves** rather than deleted hooks.

That found a gap. `member-mine` diffs the members of classes present in both versions; `jar-mine` and
`promote-ladder` read class renames out of mod ports. **Nothing diffed the class lists of the two game
jars directly**, so a package move that no mod happened to demonstrate was never found at all.

```bash
node class-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --from 26.1 --to 26.2
```

**139 package moves** between 26.1 and 26.2, none of which the tables had — the entire
`advancements.criterion` package became `advancements.predicates` and `advancements.triggers`.

It hid because a moved class shows up as a **descriptor mismatch, not a missing name**:

```
ShapedRecipeBuilder.unlockedBy
  wanted  (String, advancements/Criterion)
  actual  (String, advancements/triggers/Criterion)
```

The method is still there. Only a type inside its signature moved — which "is this member present"
answers *no* to, for a reason that looks nothing like a package move. Across the mods tested, 91 of
840 broken references were this shape.

A move is only accepted when a simple name is unique on both sides **and** the members agree, since
Minecraft has dozens of classes called `Builder`. 31 same-name pairs were rejected for having
different members — same name, different class.

Alongside it: nested classes now follow their outer class. A rule for `EntityPredicate` did nothing
for `EntityPredicate$Builder`, which is most of a builder-heavy API.

### Members that kept their name and changed their type

The JVM resolves a field by name **and** descriptor, so a member whose declared type changed breaks
every reference while sitting in plain sight. 26.2 declares `Potions.WATER` as `Holder` where 26.1
had `Holder$Reference` — the field is right there and the old reference still fails.

```bash
node descriptor-mine.mjs --old mc-26.1.jar --new mc-26.2.jar --from 26.1 --to 26.2
```

Only **widenings** are applied: the new type must be a supertype of the old, checked by walking the
target's own hierarchy, so a value that used to be a `Holder$Reference` is still a valid `Holder` and
rewriting the descriptor cannot change what the code receives. 47 of those between 26.1 and 26.2.
Narrowings would hand a caller something weaker than it expects and are reported instead; the 1,231
outright signature changes need code, not a descriptor.

That took **macaws-furniture — custom blocks, items and entities — to zero broken links**, and
illager-invasion from 15 to 11.

## Asking the corpus for what a jar actually needs

The other miners sweep a category and hope the output covers what is needed. `jar-verify` already
produces the exact list of what is missing, and that list is a set of questions:

```bash
node demand-mine.mjs mod.jar --pairs pairs.json --classpath "$MC" --source-classpath mc-26.1.jar
```

```
✓ LevelRenderer.extractVisibleEntities → render   [1 mod: iris]
? Items.PINK_WOOL          — 18 mods searched, no replacement found
? Gui.getChat()            — 18 mods searched, no replacement found
```

Failure becomes precise. "18 mods searched and none solved this" tells you whether to add corpus or
whether it needs a person — which a bare count never did. And a fix matching no known pattern still
surfaces, which is the only way a **missing category** announces itself; every category in this
project so far came from noticing a crash, which is not a discovery process.

Getting the demand list right needed two corrections, both the same mistake in opposite directions.
Checking only declared members reported 40 phantom needs for cloth-config against a real 3, because
`Button.active` and `MutableComponent.getString` are inherited. Then treating "the hierarchy left the
indexed world" as absence swung it to reporting nothing at all, since every walk ends at
`java.lang.Object`. Now: present, provably absent, or **unknowable** — and unknowable is never
reported as missing.

## A corpus without keeping the mods

```bash
node corpus-build.mjs --from 26.1 --to 26.2 --limit 1000 --out corpus/
```

Mining needs two things from a jar: which Minecraft members it references, and which methods its
mixins inject into. Both are names. The jar is megabytes and is never needed again, so each pair is
downloaded, reduced to its index, and deleted — 317 pairs cost **8.8 MB** instead of several GB, and
compress tenfold because the content is almost entirely repeated class names. Resumable per mod, so
losing the network costs nothing.

Scaling the corpus mostly converts guesses into facts rather than finding new substitutions, which is
the better outcome — the single-mod pile was always full of probably-correct answers that could not
be justified:

| corpus | corroborated injection points |
|---|---|
| 18 mods | 7 |
| 171 pairs | 13 |
| 317 pairs | **18** |

`GameRenderer.getMainCamera → mainCamera` went from one witness to nine. And
`Minecraft.setScreen → setScreenAndShow` — the substitution that had to be hand-written as an
override because the miner is structurally blind to it — turned up independently corroborated by four
mods, which is the closest thing to a check on human judgement this project has.

### Static absence is not runtime absence

Fabric API adds methods to vanilla classes by mixin. They exist in no jar on the classpath and
resolve perfectly at runtime, so link checking called them broken:
`EntitySelectorParser.getCustomFlag` was reported missing while five shipping mods called it on 26.2.

A corpus of working builds is the only evidence of that difference short of launching the game, so
`--corpus` uses it: a member other mods still call in their target-version builds is reported as
**loader-provided** rather than missing. It is flagged separately, because it is an inference from
other people's mods rather than something proved against the jars.

This corrected numbers that had been wrong throughout: continuity 7 broken links → **1**, bobby
4 → **2**. Both were counting Fabric API's own extensions as failures.

## Pooling what only observation can teach

Class renames were the least valuable thing to share: they derive from mappings Mojang publishes, so
every install can compute them alone. The categories worth pooling are the ones you can only learn by
watching ports — member renames, relocations, and above all injection points, because a small number
of Minecraft methods are hooked by a great many mods. One install holding sodium and iris learns
facts that unblock mods it does not have.

```bash
node contrib-pack.mjs --out my-contribution.json      # facts AND unanswered questions
node contrib-verify2.mjs my-contribution.json --classpath "$MC" --source-classpath mc-26.1.jar
```

The condition for sharing a fact is that the recipient can **re-derive** it. That is why the packer
refuses guesses — 483 of them here — however plausible: a guess cannot be checked on the far end, and
a pool of unverifiable guesses is indistinguishable from a pool of noise.

Verification proves what is provable and says so about the rest:

| provable | not provable |
|---|---|
| the target member exists | that an injection point is the *semantically right* hook |
| the old one is genuinely gone | |
| a relocation's field really has the host's type | |

So injection points are accepted only on independent agreement and stay a tier below jar-derived
facts. Against a submission with five poisoned entries spliced into 151 honest ones, all five were
rejected and all 151 kept.

**Open questions travel too.** "18 mods searched, no replacement for `Items.PINK_WOOL`" is a request
somebody else's collection may answer, and pooling the questions lets the set find its own gaps
instead of waiting for each person to hit them one at a time.

## Rules get better as people use it

Every install can contribute what it discovers — see [`contrib/`](contrib/README.md). Submissions carry
proof, contain only class-name pairs, and are re-verified by CI against the real jars before merging.

Merging is a second gate, not a formality: `merge-contrib.mjs` re-runs every check that depends on the
current rule set, refuses a batch that disagrees with itself, tags what it lands so it can be reverted
in one command, and deliberately never re-exports a contributed rule — so one mistake can't come back
looking like independent corroboration.

## Licence

MIT — see [LICENSE](LICENSE). Data sources and their terms: [NOTICE.md](NOTICE.md).
