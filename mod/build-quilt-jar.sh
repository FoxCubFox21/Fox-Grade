#!/bin/zsh
# The Quilt-flavoured build: the same jar as dist/foxgrade-<v>.jar plus Fox-Grade's own quilt.mod.json, so Quilt Loader
# lists Fox-Grade as a Quilt mod (it keeps fabric.mod.json too, so the file still works on Fabric). Modrinth wants a
# distinct file per version entry, which this is. Run after ./build.sh.
set -e
cd "$(dirname "$0")"
v=$(grep -m1 '"version"' src/main/resources/fabric.mod.json | sed 's/.*: *"\([^"]*\)".*/\1/')
src=$(ls dist/foxgrade-$v.jar); out="dist/foxgrade-$v+quilt.jar"
cp "$src" "$out"
tmp=$(mktemp -d); sed "s/\"version\": \"1.1.0\"/\"version\": \"$v\"/" quilt/quilt.mod.json > "$tmp/quilt.mod.json"
(cd "$tmp" && zip -q -u "$OLDPWD/$out" quilt.mod.json)
rm -rf "$tmp"
echo "wrote $out"; unzip -l "$out" | grep -c "mod.json"
