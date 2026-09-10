#!/bin/zsh
# The versions left, on a build whose own manifest no longer refuses to load on them.
#
# Fox-Grade hard-depended on the mod id "fabric-api", which did not exist before ~1.19 — the API shipped as plain
# "fabric" then. So on every pre-1.19 target the loader refused Fox-Grade while the API sat in mods/ under its old
# name, and the lane recorded that as a porting failure. It is a recommendation now, since porting needs no Fabric
# API at all and only the 26.x-only panel does.
set -u
cd ~/foxgrade-work/batch2
# 1.19.x reaches a world by joining a local server of its own version, because --quickPlaySingleplayer arrived in
# 1.20 and older clients ignore it. 1.17.1, 1.18.2, 1.16.5 and 1.15.2 stay out for a different reason: LWJGL 3.2.x
# has no arm64 macOS natives, so the game cannot start on this machine however it is asked to.
./measure-all.sh 1.19.2 1.19.4 1.20.4
echo "[rest2] DONE"
