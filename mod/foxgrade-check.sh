#!/bin/bash
# Fox-Grade standalone checker: "will this mod port to 26.2?" without launching the game.
#   ./foxgrade-check.sh some-old-mod.jar [more.jar...]        # report only
#   ./foxgrade-check.sh --out ./ported some-old-mod.jar        # also write the ported jar(s)
# Runs the exact pipeline the in-game port runs, against the jars a Fabric 26.2 install already has.
set -euo pipefail
cd "$(dirname "$0")"
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
MC="${MC_VERSION:-26.2}"
LIB="$MC_DIR/libraries"
MC_JAR="$MC_DIR/versions/$MC/$MC.jar"
[[ -f $MC_JAR ]] || { echo "no $MC client jar at $MC_JAR — launch Minecraft $MC once first" >&2; exit 1; }
FG_JAR=$(ls -t dist/foxgrade-*.jar 2>/dev/null | head -1 || true)
[[ -n $FG_JAR ]] || { echo "no dist/foxgrade-*.jar — run ./build.sh first" >&2; exit 1; }
FA_JAR=$(ls "$MC_DIR/mods"/fabric-api-*.jar 2>/dev/null | head -1 || true)
FA_TMP="${TMPDIR:-/tmp}/foxgrade-check-fabric"
if [[ -n ${FA_JAR:-} && ! -d $FA_TMP ]]; then
  mkdir -p "$FA_TMP" && (cd "$FA_TMP" && unzip -o -q "$FA_JAR" 'META-INF/jars/*.jar')
fi
CP="${EXTRA_CP:+$EXTRA_CP:}$FG_JAR:$MC_JAR"   # EXTRA_CP: a dir/jar searched first (e.g. a trial bridge table)
for f in "$LIB"/net/fabricmc/fabric-loader/*/fabric-loader-*.jar "$LIB"/com/google/code/gson/gson/*/gson-*.jar "$LIB"/com/google/guava/guava/*/guava-*.jar "$LIB"/com/mojang/authlib/*/authlib-*.jar \
         "$LIB"/org/ow2/asm/asm/*/asm-*.jar "$LIB"/org/ow2/asm/asm-tree/*/asm-tree-*.jar "$LIB"/org/ow2/asm/asm-commons/*/asm-commons-*.jar \
         "$LIB"/org/ow2/asm/asm-util/*/asm-util-*.jar "$LIB"/org/ow2/asm/asm-analysis/*/asm-analysis-*.jar \
         "$LIB"/com/mojang/brigadier/*/brigadier-*.jar "$LIB"/org/joml/joml/*/joml-*.jar "$LIB"/org/jspecify/jspecify/*/jspecify-*.jar \
         "$LIB"/it/unimi/dsi/fastutil/*/fastutil-*.jar "$LIB"/com/mojang/datafixerupper/*/datafixerupper-*.jar \
         "$LIB"/org/lwjgl/lwjgl-glfw/*/lwjgl-glfw-*.jar "$LIB"/org/slf4j/slf4j-api/*/slf4j-api-*.jar "$LIB"/org/apache/logging/log4j/log4j-slf4j2-impl/*/*.jar \
         "$LIB"/org/apache/logging/log4j/log4j-core/*/log4j-core-*.jar "$LIB"/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar \
         "$LIB"/net/fabricmc/sponge-mixin/*/sponge-mixin-*.jar "$FA_TMP"/META-INF/jars/*.jar; do
  [[ -f $f ]] && CP="$CP:$f"
done
export FOXGRADE_FABRIC_MODULES="$FA_TMP/META-INF/jars"
exec java -Xmx1g ${FG_LOADER:+-Dfoxgrade.loader=$FG_LOADER} -cp "$CP" foxgrade.CheckMain "$MC" "$MC_DIR" "$@"
