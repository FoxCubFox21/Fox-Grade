#!/bin/bash
# Fox-Grade mod build. No gradle — plain javac + jar against the jars a normal Fabric install
# already has on disk. Run from a machine that has launched Fabric 26.2 at least once.
#
# ALWAYS caps javac at 512 MB — a runaway javac wedged a build box once, so no exceptions.
set -euo pipefail
cd "$(dirname "$0")"

MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
LIB="$MC_DIR/libraries"
MC_JAR="$MC_DIR/versions/26.2/26.2.jar"

# Fabric API ships its modules nested inside the fat jar; extract the four we compile against.
FA_JAR=$(ls "$MC_DIR/mods"/fabric-api-*.jar 2>/dev/null | head -1 || true)
FA_TMP=$(mktemp -d)
if [[ -n ${FA_JAR:-} ]]; then
  (cd "$FA_TMP" && unzip -o -q "$FA_JAR" 'META-INF/jars/fabric-lifecycle-events-v1-*.jar' \
      'META-INF/jars/fabric-api-base-*.jar' 'META-INF/jars/fabric-screen-api-v1-*.jar' \
      'META-INF/jars/fabric-rendering-v1-*.jar' 'META-INF/jars/fabric-entity-events-v1-*.jar')
fi

CP="$MC_JAR"
for f in "$LIB"/net/fabricmc/fabric-loader/*/fabric-loader-*.jar \
         "$LIB"/com/google/code/gson/gson/*/gson-*.jar \
         "$LIB"/org/ow2/asm/asm/*/asm-*.jar \
         "$LIB"/org/ow2/asm/asm-tree/*/asm-tree-*.jar \
         "$LIB"/org/ow2/asm/asm-commons/*/asm-commons-*.jar \
         "$LIB"/com/mojang/brigadier/*/brigadier-*.jar \
         "$LIB"/org/lwjgl/lwjgl-glfw/*/lwjgl-glfw-*.jar \
         "$LIB"/org/joml/joml/*/joml-*.jar \
         "$LIB"/org/jspecify/jspecify/*/jspecify-*.jar \
         "$LIB"/it/unimi/dsi/fastutil/*/fastutil-*.jar \
         "$LIB"/com/mojang/datafixerupper/*/datafixerupper-*.jar \
         "$FA_TMP"/META-INF/jars/*.jar; do
  [[ -f $f ]] && CP="$CP:$f"
done

BUILD=build/classes
DIST=dist
rm -rf "$BUILD"
mkdir -p "$BUILD" "$DIST"

find src/main/java -name '*.java' > /tmp/foxgrade-sources.txt
javac -J-Xmx512m -nowarn -d "$BUILD" -cp "$CP" @/tmp/foxgrade-sources.txt

VERSION=$(grep '"version"' src/main/resources/fabric.mod.json | head -1 | sed -E 's/.*"([^"]+)"[^"]*$/\1/')
OUT="$PWD/$DIST/foxgrade-${VERSION}.jar"
rm -f "$OUT"
( cd "$BUILD" && jar cf "$OUT" . )
( cd src/main/resources && jar uf "$OUT" . )
rm -rf "$FA_TMP"
echo "wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
