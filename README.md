# Fox-Grade

Port Minecraft Fabric mods across versions — from source **or straight from the compiled jar** —
and get told plainly when it doesn't know.

```bash
# the whole mechanical pipeline, one command, no AI involved
node port-pipeline.mjs mod.jar --from 26.1 --to 26.2 \
  --classpath "$(node build-classpath.mjs --version 26.2)" --out ported.jar
```


## Loaders

**Fabric** is the primary lane and the one the numbers below are measured on.

**Quilt.** Quilt-only mods (a `quilt.mod.json`, no `fabric.mod.json`) are accepted: the manifest becomes a
`fabric.mod.json`, Quilt's loader API and QSL entrypoint interfaces are bridged, and the port ships a 26.2
`quilt.mod.json` alongside, so Quilt still sees a Quilt mod. Fox-Grade runs on Quilt Loader 26.2 (0.31 beta) too;
Quilt scans sub-folders of `mods/`, so the first jar goes in `fox-grade-inbox/` next to `mods/` and from then on
the folders are marked for Quilt to skip. QSL's own API modules are not bridged.

**NeoForge**, and here NeoForge is the better host. Fabric and Quilt commit to their mod set before any mod code
runs, so a port only takes effect after a relaunch. NeoForge asks registered locators for candidates while
discovery is still open, so Fox-Grade ports the inbox and hands the jar straight to the loader — the mod is live
on the same launch, with no restart at all.

Two things differ on that host and both are handled. Every NeoForge mod jar is its own JPMS module and two modules
may not own one package, so shims named for the class they stand in for move under the port's own namespace rather
than shipping a jar the loader rejects before it starts. And NeoForge ships its loader and its modding API as one
product, reshaping both between Minecraft versions — which Fabric does not — so a NeoForge mod hits NeoForge's own
moved classes before it reaches a Minecraft one. `tools/gen-neoforge-bridges.py` diffs two NeoForge releases for
those, and emits only what it can prove, leaving judgement calls to a person.

## What it sends

One request, and only when the panel is open: Modrinth's public read-only API, asking whether the author has
published a build of that mod for the version you are running. It carries the mod id, the Minecraft version and a
Fox-Grade user-agent, plus the IP any request carries. One query per mod per session, four-second timeout, gives up
quietly. Clicking to install an official build downloads it from Modrinth, checksum-verified.

Second request, once a day at most: a rules feed published in this project's repository — anti-rules and bridge
rows found since the build, so an installed copy keeps improving without waiting for a release. It is a download and
carries nothing about you beyond the request itself. It can only add to what shipped, it is cached so porting works
offline, and `"rulesFeed": false` in `fox-grade.config.json` stops it being made at all.

Nothing else leaves the machine: no telemetry, no account, no usage reporting, nothing uploaded. The translation
tables ship inside the jar rather than being fetched, so porting needs no connection at all.

## The receipts

Everything below is measured, versioned in this repo, and was falsified-then-fixed in public
commit history rather than asserted.

| Claim | Evidence |
|---|---|
| **16 mods verified in a real client** on 26.2 | `PLAYTEST.26.1-26.2.md` — every mod tested by hand, one at a time, with per-jar SHA-256. Failures logged with the same care as passes. |
| **12 of those already run on 26.3-pre-1** | ported weeks before release, from tables mined off the pre-release jar |
| Static-clean ≠ works: **72% → ~94%** | the first play-test found 5 failures in 18 "clean" ports; every failure became a permanent static check, re-measured |
| ~79% of a typical one-version port needs **no AI at all** | mapping tables mined from the game's own jars; AI is the last rung of a four-rung ladder, not the product |
| The tool **refuses** rather than guesses | a port that would delete behaviour is rejected by default; `--last-resort` allows it only after every real attempt, receipted in `REMOVED.md` inside the jar |

## How it works — the escalation ladder

Each rung costs more and only gets what the cheaper one could not do:

1. **Tables** (`jar-remap`) — renames, package moves, registry constants, mined by diffing real
   game jars (or Mojang's published mappings for obfuscated versions). Constant-pool string edits:
   no decompilation, untouched classes stay byte-identical.
2. **The compat bridge** (`jar-bridge`) — for members that *moved* or became method calls
   (`Blocks.RED_BED` → `Blocks.BED.red()`): a generated class shipped inside the jar, call sites
   redirected by same-size same-stack-effect bytecode substitution. Verified by the JVM's own
   verifier, not by hope.
3. **Consultation** (`jar-tier2`) — an AI ports only the classes that still fail, gated: must
   compile, must fix more than it breaks, must not drop mixin injectors, must not stub silently.
4. **Investigation** (`jar-tier3`) — an agent with javap and both versions' jars, a mission brief,
   and a budget, for classes whose answer has to be *found*. Same gates judge the result.

Independent checkers keep every rung honest: `jar-verify` (link resolution as the JVM does it),
`mixin-check` (annotation strings, `@At` targets, refusal to accuse selectors that resolve),
`inherit-check` (overrides that died silently, class/interface flips), a JVM-verifier gate
measured relative to the original jar, and `port-report` (exactly which classes changed, so
testing is a short list).

## What it will not do

- **Guess.** A missing mapping is reported, not invented. ~800 plausible-looking rules were
  deleted the day compiling proved them wrong; the mining rules that produced them were fixed.
- **Silently remove features.** The failure this project is built around is the mod that *loads*
  and quietly stops doing its job.
- **Redistribute anything that isn't ours.** Mojang's mappings are used, never shipped — only
  derived old→new pairs. Ported jars of other people's mods are for your own use; their licenses
  are theirs.
- **Pretend static-clean means working.** Every claim of "verified" in this repo means a human
  played it.

## Status

Active. 26.3 tables are already mined from the pre-release (`*.26.2-26.3p.json`, quarantined until
release day). The decision ledger (`DECISIONS.26.1-26.2.md`) lists every currently-blocked mod with
the reason and whose move it is — that file is the honest to-do list.
