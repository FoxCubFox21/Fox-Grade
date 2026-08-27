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

## What it won't do

- **Rewrite mods you downloaded.** It ports source. A compiled jar with no source is out of scope.
- **Invent replacements.** When an API was deleted rather than renamed, it says so and explains the
  new pattern instead of guessing a class that compiles but is wrong.
- **Handle pre-1.14 confidently.** Mojang published no mappings before 1.14.4; coverage drops to ~65%
  there. It declines rather than guesses, so accuracy holds.
- **Replace testing.** Compiling clean is necessary, not sufficient. Every port ships a report.

## Rules get better as people use it

Every install can contribute what it discovers — see [`contrib/`](contrib/README.md). Submissions carry
proof, contain only class-name pairs, and are re-verified by CI against the real jars before merging.

## Licence

MIT — see [LICENSE](LICENSE). Data sources and their terms: [NOTICE.md](NOTICE.md).
