# Version coverage

Every version Fox-Grade will port for, and how a real corpus of mods fared on it. A version is listed only
after a corpus has been run against it on a real client launched into a real world — tables existing is not
the same as knowing it works, and this file is the difference.

**Corpus sizes differ between versions and the numbers are not comparable across rows.** 26.2 is measured
against 130 mods; the older lanes use 13–14 mods built for the version before them, because Fox-Grade ports
forward and a target has to be measured with mods older than itself. A 7/14 and an 86/130 are both honest
and are not the same measurement.

| Minecraft | mods that boot | corpus |
|---|---|---|
| 26.2 | **86** | 130 |
| 26.1.1 | **6** | 13 |
| 1.21.11 | **7** | 14 |
| 1.21.10 | **7** | 14 |
| 1.21.9 | **7** | 14 |
| 1.21.8 | **7** | 14 |
| 1.21.7 | **7** | 14 |
| 1.21.6 | **7** | 14 |
| 1.21.5 | **7** | 14 |
| 1.21.4 | **9** | 14 |
| 1.21.3 | **8** | 14 |
| 1.21.2 | **8** | 14 |
| 1.21.1 | **7** | 14 |
| 1.20.6 | **9** | 14 |
| 1.20.4 | **11** | 14 |
| 1.20.5 | **9** | 14 |
| 1.20.2 | **11** | 14 |
| 1.20.1 | **8** | 14 |
| 1.19.4 | **10** | 14 |
| 1.19.2 | **9** | 14 |

## How a version reaches a world

`--quickPlaySingleplayer`, which opens a singleplayer world straight from the command line, arrived in 1.20. Rows
from 1.20 upward are measured that way. An older client ignores the argument and sits at the main menu, so 1.19.2
and 1.19.4 are measured by joining a dedicated server of their own version instead — offline mode, flat world. The
mod still runs in a real client in a real world, which is what the pass rule asks, but the world is flat and
server-generated rather than the singleplayer test world, so a fault that only shows during singleplayer world
generation would not be caught on those two rows.

Below 1.19.2 nothing is measured at all: Minecraft up to 1.18.2 ships LWJGL 3.2.x, which has no arm64 macOS
natives, so the client cannot start on the machine these lanes run on.

## Shares another version's tables

Some releases changed no API at all from the one before, declaring an identical class set — checked, not
assumed. They use that version's translation tables and keep their own class inventory, because the
inventory is where a single added method would matter.

| Version | Uses tables from |
|---|---|
| 26.1, 26.1.1 | 26.1.2 |
| 1.21 | 1.21.1 |

## Not supported

1.14.4 has translation tables but cannot be measured: it is the oldest version those tables cover, so there
is no older corpus to port *forward* from, and Fox-Grade does not port backwards.
