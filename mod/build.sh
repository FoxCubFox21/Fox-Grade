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
      'META-INF/jars/fabric-rendering-v1-*.jar' 'META-INF/jars/fabric-entity-events-v1-*.jar' 'META-INF/jars/fabric-resource-loader-v1-*.jar' 'META-INF/jars/fabric-resource-loader-v0-*.jar')
fi

CP="$MC_JAR"
for f in "$LIB"/net/fabricmc/fabric-loader/*/fabric-loader-*.jar \
         "$LIB"/com/google/code/gson/gson/*/gson-*.jar "$LIB"/com/google/guava/guava/*/guava-*.jar "$LIB"/com/mojang/authlib/*/authlib-*.jar "$LIB"/io/netty/netty-buffer/4.2.15.Final/netty-buffer-*.jar "$LIB"/io/netty/netty-common/4.2.15.Final/netty-common-*.jar \
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

# --- NeoForge entry point ---------------------------------------------------------------------------------------
# Compiled separately, against NeoForge rather than Fabric, and only when its jars are vendored in libs/neoforge.
# The classes ride in the same jar; Fabric never loads them (nothing reads NeoForge's service file there), and on
# NeoForge the service registration makes Fox-Grade a mod-file locator, which is what removes the restart step.
NF_CP=""
for f in libs/neoforge/*.jar; do [[ -f $f ]] && NF_CP="$NF_CP:$f"; done
if [[ -n $NF_CP && -d src/neoforge/java ]]; then
  find src/neoforge/java -name '*.java' > /tmp/foxgrade-nf-sources.txt
  if javac -J-Xmx512m -nowarn -d "$BUILD" -cp "$CP:$BUILD$NF_CP" @/tmp/foxgrade-nf-sources.txt 2>/tmp/foxgrade-nf-errors.txt; then
    echo "  NeoForge locator compiled"
  else
    echo "  NeoForge locator SKIPPED (see /tmp/foxgrade-nf-errors.txt) — the Fabric build is unaffected"
    find "$BUILD/foxgrade/neoforge" -name '*.class' -delete 2>/dev/null || true
  fi
fi

# --- Shim sets for additional target versions -------------------------------------------------------------------
# Fox-Grade injects shims as compiled classes, and a shim compiled against one Minecraft is not valid on another. To
# target a second version, the shim package — which is self-contained, importing nothing from the engine — is compiled
# again against that version's client jar and shipped under foxgrade/shimset/<version>/.
#
# Not every shim can exist on every version, and that is the point of doing it this way. A shim that will not compile
# against a version is dropped for that version only, and the port report then says the reference is unresolved. The
# alternative — shipping one set built for the newest game and hoping — is how you get a port that loads and then
# fails somewhere the report never mentioned.
#
# Dropping is iterative because failures cascade: a shim can be fine in itself and fail only because something it uses
# was dropped a round earlier. Each round removes the files javac named and recompiles, until what is left compiles.
# ALT_MC_JARS is a space-separated list of version=path/to/<version>.jar.
ALT_MC_JARS="${ALT_MC_JARS:-}"
for spec in $ALT_MC_JARS; do
  alt_ver="${spec%%=*}"; alt_jar="${spec#*=}"
  [[ -f $alt_jar ]] || { echo "  shimset $alt_ver SKIPPED (no jar at $alt_jar)"; continue; }
  # Swap the client jar at the head of CP and keep the rest verbatim. Splitting CP on ':' and re-joining would be
  # the obvious way and is wrong: the library paths run through "Application Support", so word splitting eats them.
  alt_cp="$alt_jar${CP#"$MC_JAR"}"
  alt_out="build/shimset/$alt_ver"; rm -rf "$alt_out"; mkdir -p "$alt_out"
  find src/main/java/foxgrade/shim -name '*.java' > /tmp/fg-shim-sources.txt
  dropped=0
  for round in 1 2 3 4 5 6; do
    if javac -J-Xmx512m -nowarn -Xmaxerrs 10000 -d "$alt_out" -cp "$alt_cp" @/tmp/fg-shim-sources.txt 2>/tmp/fg-shim-errors.txt; then
      break
    fi
    grep -oE "^[^:]+\.java" /tmp/fg-shim-errors.txt | sort -u > /tmp/fg-shim-bad.txt
    [[ -s /tmp/fg-shim-bad.txt ]] || { echo "  shimset $alt_ver FAILED (see /tmp/fg-shim-errors.txt)"; break; }
    dropped=$((dropped + $(wc -l < /tmp/fg-shim-bad.txt)))
    grep -vxF -f /tmp/fg-shim-bad.txt /tmp/fg-shim-sources.txt > /tmp/fg-shim-keep.txt || true
    mv /tmp/fg-shim-keep.txt /tmp/fg-shim-sources.txt
    rm -rf "$alt_out"; mkdir -p "$alt_out"
  done
  kept=$(find "$alt_out" -name '*.class' | wc -l | tr -d ' ')
  if [[ $kept -gt 0 ]]; then
    mkdir -p "$BUILD/foxgrade/shimset/$alt_ver"
    ( cd "$alt_out" && tar cf - . ) | ( cd "$BUILD/foxgrade/shimset/$alt_ver" && tar xf - )
    sed 's|.*/foxgrade/shim/|foxgrade/shim/|; s|\.java$||' /tmp/fg-shim-bad.txt 2>/dev/null | sort -u > "$BUILD/foxgrade/shimset/$alt_ver/UNAVAILABLE.txt" || true
    echo "  shimset $alt_ver: $kept classes, $dropped source file(s) not available on that version"
  fi
done

VERSION=$(grep '"version"' src/main/resources/fabric.mod.json | head -1 | sed -E 's/.*"([^"]+)"[^"]*$/\1/')
OUT="$PWD/$DIST/foxgrade-${VERSION}.jar"
rm -f "$OUT"
( cd "$BUILD" && jar cf "$OUT" . )
( cd src/main/resources && jar uf "$OUT" . )
[[ -d src/neoforge/resources && -f "$BUILD/foxgrade/neoforge/FoxGradeLocator.class" ]] && ( cd src/neoforge/resources && jar uf "$OUT" . )
rm -rf "$FA_TMP"
echo "wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
