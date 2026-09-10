# Draft launch post — not published

For r/feedthebeast or r/fabricmc. Written to be posted by the author; nothing here has been sent anywhere.

---

**Title:** Fox-Grade — a mod that ports your dead mods to newer Minecraft, at launch, on your own machine

---

That mod you love that stopped updating two versions ago? Drop its jar in `mods/` and launch the game.

Fox-Grade rewrites old Fabric, Quilt, NeoForge and Forge mods to run on a newer Minecraft. It happens
automatically at startup, entirely on your machine — nothing is uploaded and nothing is redistributed. The
moment the original author publishes a real build for your version, the port retires itself and gets out of
the way. **The author's build always beats a port.**

## It works, and here are the receipts

130 mods, every one launched into a real world on a real client: **86 boot.**

I also ran it head to head against [Retromod](https://modrinth.com/mod/retromod), on the 104 mods both tools
were run against. Same pass rule for both: the game reaches world load, stays up, and the mod is really in the
loaded-mod list.

| | boots | of corpus | only under this tool | does not boot |
|---|---|---|---|---|
| **Fox-Grade** | **70** | **67%** | **33** | **34** |
| Retromod | 37 | 36% | 0 | 67 |

To be straight about that comparison: Retromod got there first and the mixin-surgery approach is its idea —
Fox-Grade's is a from-scratch reimplementation. Every one of the 33 is a mod Retromod crashes on, and none go
the other way, but both tools fail on plenty. The full per-mod table, including all 44 Fox-Grade misses and the
reason for each, is public.

## Where it runs

| Loader | Fox-Grade |
|---|---|
| Fabric | 86 of 130 |
| Quilt | 61 of 105 |
| NeoForge | 16 of 40 |
| Forge | being measured |

## Versions

Fourteen Minecraft versions have had a corpus run against them, from 1.20.6 up to 26.2. A version only gets
listed once mods have actually been launched on it — having the translation tables is not the same as knowing
it works.

Corpus sizes differ between versions, so those numbers aren't comparable row to row: 26.2 is measured against
130 mods, the older lanes against 13–14. Both honest, not the same measurement.

## Honest about the limits

Heavyweight rendering mods are out of scope and always will be. Anything Fox-Grade can't prove safe is disabled
and **listed by name** rather than guessed at. Originals are never deleted, every port is reversible, and a port
that crashes your game retires itself so it can't take you down twice.

## Where to get it

Source and every test result: <GITHUB LINK>

A Modrinth release is in review. Until it lands you can build it yourself with `mod/build.sh` — so for now this
is really for people happy to compile a jar. I'd rather say that than pretend there's a download button.

Happy to answer anything, including about the mods it fails on.
