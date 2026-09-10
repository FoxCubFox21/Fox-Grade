#!/usr/bin/env python3
"""A dedicated server for one Minecraft version, so a pre-1.20 lane can reach a world.

The client lane loads a world with --quickPlaySingleplayer, which arrived in 1.20. Older clients ignore it and sit
at the main menu, so every mod there was recorded STALL no matter how it ported. Those clients do honour --server
and --port, which predate quickPlay by years, so the way into a world on them is to join one.

Offline mode, because the lane's client authenticates with a dummy token. One fixed port per version so two lanes
can never land on the same socket.

Usage: make-server.py <version>...
"""
import json, os, pathlib, subprocess, sys, urllib.request

HOME = pathlib.Path.home()
UA = {"User-Agent": "fox-grade-harness"}


def port_for(version):
    """A stable port per version: 25600 + a small hash, well clear of the default 25565."""
    return 25600 + (sum(ord(c) for c in version) % 300)


def server_jar(version):
    manifest = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=UA), timeout=30))
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        raise SystemExit(f"Mojang publishes no version {version!r}")
    meta = json.load(urllib.request.urlopen(urllib.request.Request(entry["url"], headers=UA), timeout=60))
    dl = (meta.get("downloads") or {}).get("server")
    if not dl:
        raise SystemExit(f"{version}: Mojang publishes no server jar for it")
    return dl["url"]


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    for version in sys.argv[1:]:
        sd = HOME / f"mc-porttest-srv-{version.replace('.', '_')}"
        sd.mkdir(parents=True, exist_ok=True)
        jar = sd / "server.jar"
        if not jar.exists():
            url = server_jar(version)
            print(f"  {version}: fetching server jar")
            jar.write_bytes(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=600).read())
        (sd / "eula.txt").write_text("eula=true\n")
        port = port_for(version)
        # Flat world, no mobs, nothing to do: the lane only needs a world that exists and stays up. view-distance
        # is small because the client is measured on whether it got in, not on what it can see.
        (sd / "server.properties").write_text(
            f"online-mode=false\nserver-port={port}\nlevel-name=TESTWORLD\nlevel-type=flat\n"
            "spawn-protection=0\nview-distance=6\nsimulation-distance=4\nmax-tick-time=-1\n"
            "sync-chunk-writes=false\nspawn-monsters=false\nspawn-npcs=false\nspawn-animals=false\n"
            "enable-status=true\nmotd=fox-grade harness\n")
        print(f"  {version}: server ready at {sd}, port {port}")


if __name__ == "__main__":
    main()
