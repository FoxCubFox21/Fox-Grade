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
  # The directory name has to be usable as a Java package segment: a part may not begin with a digit or hold a dot,
  # and a jar carrying foxgrade/shimset/26.1.2/ is rejected outright by anything that validates module packages.
  # Forge does, with "Invalid package name: '26' is not a Java identifier", before a single mod loads.
  alt_dir="v${alt_ver//[^A-Za-z0-9]/_}"
  alt_out="build/shimset/$alt_dir"; rm -rf "$alt_out"; mkdir -p "$alt_out"
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
    mkdir -p "$BUILD/foxgrade/shimset/$alt_dir"
    ( cd "$alt_out" && tar cf - . ) | ( cd "$BUILD/foxgrade/shimset/$alt_dir" && tar xf - )
    sed 's|.*/foxgrade/shim/|foxgrade/shim/|; s|\.java$||' /tmp/fg-shim-bad.txt 2>/dev/null | sort -u > "$BUILD/foxgrade/shimset/$alt_dir/UNAVAILABLE.txt" || true
    echo "  shimset $alt_ver: $kept classes, $dropped source file(s) not available on that version"
  fi
done

# --- Forge entry point ------------------------------------------------------------------------------------------
# Same story as the NeoForge locator and a different SPI, because Forge kept the older interface the two share an
# ancestor in: a locator returns mod files it built itself rather than handing paths to a pipeline. Compiled against
# Forge, riding in the same jar, loaded only where its service file means something.
FORGE_CP=""
for f in libs/forge/*.jar; do [[ -f $f ]] && FORGE_CP="$FORGE_CP:$f"; done
if [[ -n $FORGE_CP && -d src/forge/java ]]; then
  find src/forge/java -name '*.java' > /tmp/foxgrade-forge-sources.txt
  if javac -J-Xmx512m -nowarn -d "$BUILD" -cp "$CP:$BUILD$FORGE_CP" @/tmp/foxgrade-forge-sources.txt 2>/tmp/foxgrade-forge-errors.txt; then
    echo "  Forge locator compiled"
  else
    echo "  Forge locator SKIPPED (see /tmp/foxgrade-forge-errors.txt) — every other loader is unaffected"
    find "$BUILD/foxgrade/forge" -name '*.class' -delete 2>/dev/null || true
  fi
fi

# --- the manifest must not be narrower than the supported set ---------------------------------------------------
# Twice now, a version was added to Targets and the loader refused Fox-Grade before Targets could speak, because
# fabric.mod.json still named a newer minimum. The two are saying related things and drifted apart both times, so
# the build compares them instead of trusting anyone to remember.
python3 - <<'PYCHECK' || exit 1
import json, pathlib, re, sys
targets = pathlib.Path("src/main/java/foxgrade/Targets.java").read_text()
block = re.search(r"SUPPORTED\s*=\s*Set\.of\(([^)]*)\)", targets, re.S)
supported = re.findall(r'"([^"]+)"', block.group(1)) if block else []
meta = json.loads(pathlib.Path("src/main/resources/fabric.mod.json").read_text())
declared = meta.get("depends", {}).get("minecraft", "")
floor = declared.lstrip(">=").strip()

def key(v):
    return [int(x) if x.isdigit() else 0 for x in v.split(".")]

if not supported:
    print("  ! could not read Targets.SUPPORTED; manifest range unchecked", file=sys.stderr)
elif not declared.startswith(">="):
    print(f"  manifest pins minecraft {declared!r}; supported targets are {sorted(supported)}")
elif min(map(key, supported)) < key(floor):
    oldest = min(supported, key=key)
    print(f"  ! fabric.mod.json requires minecraft {declared}, but Targets supports {oldest}.", file=sys.stderr)
    print(f"  ! Fabric Loader would refuse Fox-Grade on {oldest} before Targets is consulted.", file=sys.stderr)
    sys.exit(1)
PYCHECK

# --- only supported targets ship --------------------------------------------------------------------------------
# Each target's tables are about three megabytes, and there are a dozen versions worth deriving. Shipping them all
# would make the download grow with every version anyone has ever considered, most of which are not offered.
#
# So the resources directory is where tables are kept and the jar is where supported ones go: a target's files are
# packaged only if Targets lists it, or if it is the family representative for something Targets lists. Deriving a
# candidate's tables costs nothing to anyone until it has been measured and turned on.
python3 - <<'PYPACK'
import json, pathlib, re, shutil
res = pathlib.Path("src/main/resources/foxgrade")
stage = pathlib.Path("build/resources/foxgrade")
targets = pathlib.Path("src/main/java/foxgrade/Targets.java").read_text()
supported = set(re.findall(r'"([^"]+)"', re.search(r"SUPPORTED\s*=\s*Set\.of\(([^)]*)\)", targets, re.S).group(1)))
family = dict(re.findall(r'"([^"]+)",\s*"([^"]+)"', re.search(r"FAMILY\s*=[^;]*?of\(([^)]*)\)", targets, re.S).group(1)))
# A supported version needs its own inventory and its family's heavy tables.
keep_inventory = set(supported)
keep_tables = {family.get(v, v) for v in supported}
if stage.exists():
    shutil.rmtree(stage.parent)
stage.mkdir(parents=True)
skipped = []
for f in sorted(res.iterdir()):
    m = re.fullmatch(r"mc-(.+)\.classes\.json\.gz", f.name)
    n = re.fullmatch(r"intermediary-to-mojang\.(.+)\.json\.gz", f.name)
    if m and m.group(1) not in keep_inventory:
        skipped.append(f.name); continue
    if n and n.group(1) not in keep_tables:
        skipped.append(f.name); continue
    shutil.copy2(f, stage / f.name)
for sub in res.iterdir():
    if sub.is_dir():
        shutil.copytree(sub, stage / sub.name, dirs_exist_ok=True)
print(f"  packaging tables for {sorted(keep_inventory)}; {len(skipped)} unsupported table file(s) left out")
PYPACK

VERSION=$(grep '"version"' src/main/resources/fabric.mod.json | head -1 | sed -E 's/.*"([^"]+)"[^"]*$/\1/')
OUT="$PWD/$DIST/foxgrade-${VERSION}.jar"
rm -f "$OUT"
( cd "$BUILD" && jar cf "$OUT" . )
( cd src/main/resources && jar uf "$OUT" $(ls | grep -v '^foxgrade$') )
( cd build/resources && jar uf "$OUT" foxgrade )
[[ -d src/neoforge/resources && -f "$BUILD/foxgrade/neoforge/FoxGradeLocator.class" ]] && ( cd src/neoforge/resources && jar uf "$OUT" . )
[[ -d src/forge/resources && -f "$BUILD/foxgrade/forge/FoxGradeForgeLocator.class" ]] && ( cd src/forge/resources && jar uf "$OUT" . )
rm -rf "$FA_TMP"
echo "wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
