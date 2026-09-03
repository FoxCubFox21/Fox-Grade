# Launch post — r/fabricmc / r/feedthebeast / Fabric Discord #show-your-mods
*(same text works lightly trimmed for all three; post from your account)*

**Title:** I built a mod porter that publishes its error count — 16 mods verified in-game on 26.2, 12 already running on 26.3-pre-1

Every "AI mod porter" I found advertises instant conversion. So I built the opposite: a porter
whose whole design is refusing to guess, and I measured everything.

**What it is:** Fox-Grade ports Fabric mods across Minecraft versions — from source or straight
from the compiled jar. ~79% of a one-version port is mechanical (tables mined by diffing the game's
own jars — no decompilation, untouched classes stay byte-identical). What the tables can't express
goes to a generated compat layer inside the jar. Only what *still* fails goes to an AI, behind
gates: must compile, must fix more than it breaks, must not drop mixin injectors, must not delete a
feature to make things link. A port that would remove behaviour is rejected by default — allowed
only as an explicit last resort, with a receipt written into the jar.

**The receipts, because that's the point:**
- 16 mods verified by actually playing them, one at a time (ledger with per-jar SHA-256 in the repo)
- The first play-test humbled the tooling: 5 of 18 "statically clean" ports failed in-game.
  Every failure became a permanent static check. The gap between "every check passes" and "the
  JVM agrees" went from 28% to ~6%, and that number is *measured*, not estimated
- ~800 plausible-looking mappings were deleted the day compiling proved them wrong
- 26.3 is already mined from the pre-release: 12 of my 16 verified mods run on 26.3-pre-1 today,
  including the Blaze3D→RenderPearl migration mapped weeks early

**What it is not:** a magic button. Renderer-rewrite mods (sodium/iris class of thing) are on an
honest ledger of "here's exactly what blocks this and whose decision it is." Ported jars of other
people's mods are for your own use — the tool is what's shared, not their work.

**Licence, said up front:** source-available under PolyForm Noncommercial — free for personal use,
forks and changes welcome; commercial use (paid services, bundling, company use) needs my written
permission, and I'm askable. If that's a dealbreaker for you, fair enough — it's a deliberate choice.

Repo: github.com/FoxCubFox21/Fox-Grade — the commit history *is* the documentation; every wrong
turn is in there with what fixed it.

---
# Short version — Discord one-liner
Fox-Grade: a Fabric mod porter that refuses to guess. 16 ports verified in-game on 26.2, 12 already
running on 26.3-pre-1, every claim receipted in the repo. The AI is the last resort, not the
product. Free for personal use (PolyForm Noncommercial). github.com/FoxCubFox21/Fox-Grade
