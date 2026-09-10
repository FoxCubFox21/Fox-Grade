#!/bin/bash
# Fox-Grade mod build. No gradle — plain javac + jar against the jars a normal Fabric install
# already has on disk. Run from a machine that has launched Fabric 26.2 at least once.
#
# Compiled with --release 17, not the build JDK's own version. Fox-Grade's shims are injected INTO other people's
# mods, so their class-file version becomes that mod's problem: built on javac 25 they came out major 69, and
# Forge's annotation scanner cannot parse those — it then found no @Mod class in any port and refused every mod in
# the lane with "the following classes are missing, but are reported in the mods.toml". 21 is what Minecraft 26.2
# itself requires, so nothing is given up.
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
javac -J-Xmx512m --release 17 -nowarn -d "$BUILD" -cp "$CP" @/tmp/foxgrade-sources.txt

# --- NeoForge entry point ---------------------------------------------------------------------------------------
# Compiled separately, against NeoForge rather than Fabric, and only when its jars are vendored in libs/neoforge.
# The classes ride in the same jar; Fabric never loads them (nothing reads NeoForge's service file there), and on
# NeoForge the service registration makes Fox-Grade a mod-file locator, which is what removes the restart step.
NF_CP=""
for f in libs/neoforge/*.jar; do [[ -f $f ]] && NF_CP="$NF_CP:$f"; done
if [[ -n $NF_CP && -d src/neoforge/java ]]; then
  find src/neoforge/java -name '*.java' > /tmp/foxgrade-nf-sources.txt
  if javac -J-Xmx512m --release 17 -nowarn -d "$BUILD" -cp "$CP:$BUILD$NF_CP" @/tmp/foxgrade-nf-sources.txt 2>/tmp/foxgrade-nf-errors.txt; then
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
    if javac -J-Xmx512m --release 17 -nowarn -Xmaxerrs 10000 -d "$alt_out" -cp "$alt_cp" @/tmp/fg-shim-sources.txt 2>/tmp/fg-shim-errors.txt; then
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
  if javac -J-Xmx512m --release 17 -nowarn -d "$BUILD" -cp "$CP:$BUILD$FORGE_CP" @/tmp/foxgrade-forge-sources.txt 2>/tmp/foxgrade-forge-errors.txt; then
    echo "  Forge locator compiled"
  else
    echo "  Forge locator SKIPPED (see /tmp/foxgrade-forge-errors.txt) — every other loader is unaffected"
    find "$BUILD/foxgrade/forge" -name '*.class' -delete 2>/dev/null || true
  fi
fi

# --- measurement builds -----------------------------------------------------------------------------------------
# A version cannot be measured until Fox-Grade will load on it, and cannot be supported until it is measured. The
# manifest floor is what makes that circular: it tracks the oldest supported version, so the harness cannot get in.
#
# MEASURE_FLOOR=<version> breaks the circle for the harness alone. The built jar declares that floor and carries
# every target's tables, so a lane can open a version with -Dfoxgrade.target and find out how it does. The sources
# are untouched, so nothing about what ships changes, and the build says loudly which kind of jar it made — a jar
# that loads where it has not been measured is a fine thing to test with and not a thing to publish.
MEASURE_FLOOR="${MEASURE_FLOOR:-}"
if [[ -n $MEASURE_FLOOR ]]; then
  echo "  ** MEASUREMENT BUILD: floor $MEASURE_FLOOR, all targets packaged — do not ship this jar **"
fi

# --- the manifest must not be narrower than the supported set ---------------------------------------------------
# Twice now, a version was added to Targets and the loader refused Fox-Grade before Targets could speak, because
# fabric.mod.json still named a newer minimum. The two are saying related things and drifted apart both times, so
# the build compares them instead of trusting anyone to remember.
[[ -z $MEASURE_FLOOR ]] && python3 - <<'PYCHECK'
import json, pathlib, re, sys
targets = pathlib.Path("src/main/java/foxgrade/Targets.java").read_text()
RELEASE = "17"
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

# The java gate is the same kind of promise, about the class files rather than the target. javac is told
# --release 17, so claiming a higher floor strands users on older Minecraft for no reason, and claiming a
# lower one hands them a jar their JVM cannot read. Neither is caught until someone's game will not start.
declared_java = meta.get("depends", {}).get("java", "")
if declared_java.lstrip(">=").strip() != RELEASE:
    print(f"  ! fabric.mod.json requires java {declared_java}, but javac is told --release {RELEASE}.", file=sys.stderr)
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
import json, os, pathlib, re, shutil
res = pathlib.Path("src/main/resources/foxgrade")
stage = pathlib.Path("build/resources/foxgrade")
targets = pathlib.Path("src/main/java/foxgrade/Targets.java").read_text()
supported = set(re.findall(r'"([^"]+)"', re.search(r"SUPPORTED\s*=\s*Set\.of\(([^)]*)\)", targets, re.S).group(1)))
measure = os.environ.get("MEASURE_FLOOR", "")
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
    if not measure:
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
# A measurement jar and a shipping jar are very different things wearing the same name: one declares a floor it has
# not earned and carries every target table, the other declares only what is measured. run-version.sh takes the
# newest dist jar, so a plain build between two lanes silently handed the harness a shipping jar -- which refuses to
# load on the version being measured and records that refusal as the mod failing. Separate names, so neither can be
# picked up as the other.
if [[ -n $MEASURE_FLOOR ]]; then
  OUT="$PWD/$DIST/foxgrade-${VERSION}-measure.jar"
else
  OUT="$PWD/$DIST/foxgrade-${VERSION}.jar"
fi
rm -f "$OUT"
( cd "$BUILD" && jar cf "$OUT" . )
if [[ -n $MEASURE_FLOOR ]]; then
  mkdir -p build/meta
  python3 -c "
import json, pathlib, sys
m = json.loads(pathlib.Path('src/main/resources/fabric.mod.json').read_text())
m.setdefault('depends', {})['minecraft'] = '>=' + sys.argv[1]
# Fabric APIs mod id was plain fabric before ~1.19 and fabric-api after. A hard dependency on either name refuses
# Fox-Grade outright on the other era: on 1.17.1 the loader said it requires any version of fabric-api and found
# none, while the API sat in mods/ under its old id. A Fabric manifest cannot express either-of-these, and porting
# needs no Fabric API at all, only the panel does, and the panel is 26.x-only. So it is a recommendation rather
# than a requirement, which loads everywhere and still tells a user they want it.
dep = m.setdefault('depends', {})
if 'fabric-api' in dep:
    m.setdefault('recommends', {})['fabric-api'] = dep.pop('fabric-api')
pathlib.Path('build/meta/fabric.mod.json').write_text(json.dumps(m, indent=2) + '\n')
" "$MEASURE_FLOOR"
  ( cd src/main/resources && jar uf "$OUT" $(ls | grep -vE '^(foxgrade|fabric.mod.json)$') )
  ( cd build/meta && jar uf "$OUT" fabric.mod.json )
else
  ( cd src/main/resources && jar uf "$OUT" $(ls | grep -v '^foxgrade$') )
fi
( cd build/resources && jar uf "$OUT" foxgrade )
[[ -d src/neoforge/resources && -f "$BUILD/foxgrade/neoforge/FoxGradeLocator.class" ]] && ( cd src/neoforge/resources && jar uf "$OUT" . )
[[ -d src/forge/resources && -f "$BUILD/foxgrade/forge/FoxGradeForgeLocator.class" ]] && ( cd src/forge/resources && jar uf "$OUT" . )
rm -rf "$FA_TMP"
echo "wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
