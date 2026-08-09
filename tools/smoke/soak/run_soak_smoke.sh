#!/usr/bin/env bash
# AprismPrismate multi-mod soak harness (Windows, v26.0-Alpha.5).
# Launches a genuine Minecraft 26.2 instance through Fabric Loader 0.16.14
# with Prismate installed plus THREE soak .aje packs that exercise:
#   - multi-pack discovery/extraction/injection in one boot
#   - provides-alias dependency resolution (soakconsumer depends on the
#     virtual id soak-api provided by soakapi)
#   - dependency-resolved dispatch ordering (provider must init before the
#     consumer)
# and records a startup performance baseline from the in-game load report.
#
# Reuses the Fabric smoke harness environment (client.jar, libraries, natives,
# assets) from ../Aprism/build/smoke.
#
# Requirements:
#   - a built Prismate Fabric jar (./gradlew :fabric:shadowJar)
#   - the Aprism smoke environment prepared
#   - the soak packs built (python tools/smoke/soak/build_soak_packs.py ...)
#   - JDK 25 (set PRISMATE_JAVA_HOME or JAVA_HOME to a JDK 25)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APRISM_SMOKE="$(cd "$REPO_ROOT/../Aprism/build/smoke" && pwd)"

SMOKE_DIR="$REPO_ROOT/build/smoke-soak"
GAMEDIR="$SMOKE_DIR/gamedir"
LOG="$SMOKE_DIR/smoke.log"
MARKER="PRISMATE_SOAK_SMOKE"

if command -v cygpath >/dev/null 2>&1; then
  W_APRISM_SMOKE="$(cygpath -m "$APRISM_SMOKE")"
  W_SMOKE_DIR="$(cygpath -m "$SMOKE_DIR")"
  W_GAMEDIR="$(cygpath -m "$GAMEDIR")"
else
  W_APRISM_SMOKE="$APRISM_SMOKE"
  W_SMOKE_DIR="$SMOKE_DIR"
  W_GAMEDIR="$GAMEDIR"
fi

VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
[ -n "$VERSION" ] || { echo "FAIL: could not read prismateVersion" >&2; exit 1; }
MCVER="$(sed -n 's/^minecraftVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"

PRISMATE_JAR_POSIX="$REPO_ROOT/fabric/build/libs/AprismPrismate-${VERSION}-Fa-${MCVER}.jar"
if command -v cygpath >/dev/null 2>&1; then
  PRISMATE_JAR="$(cygpath -m "$PRISMATE_JAR_POSIX")"
else
  PRISMATE_JAR="$PRISMATE_JAR_POSIX"
fi

JAVA_BIN="${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}/bin/java.exe"
if [ ! -x "$JAVA_BIN" ]; then
  echo "FAIL: JDK 25 not found at $JAVA_BIN" >&2
  echo "      Set PRISMATE_JAVA_HOME (or JAVA_HOME) to a JDK 25 installation." >&2
  exit 1
fi

fail() { echo "SOAK FAIL: $*" >&2; exit 1; }

# Kill any game process from a previous soak run (matched by our marker).
if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
fi
sleep 1

# Pre-flight checks
[ -f "$PRISMATE_JAR_POSIX" ] || fail "Prismate jar missing: $PRISMATE_JAR_POSIX (run ./gradlew :fabric:shadowJar)"
[ -f "$APRISM_SMOKE/client.jar" ] || fail "client.jar missing: run Aprism tools/smoke/setup_smoke_env.sh"
[ -d "$APRISM_SMOKE/natives/windows/x64" ] || fail "natives missing in the Aprism smoke env"
SOAK_PACKS="$REPO_ROOT/build/soak-packs"
for p in soakcore soakapi soakconsumer; do
  [ -f "$SOAK_PACKS/$p.aje" ] || fail "$p.aje missing (run tools/smoke/soak/build_soak_packs.py)"
done

# Fabric runtime deps (committed under tools/smoke/deps)
DEPS="$REPO_ROOT/tools/smoke/deps"
FABRIC_CP=""
for j in "$DEPS"/*.jar; do
  if command -v cygpath >/dev/null 2>&1; then
    FABRIC_CP="${FABRIC_CP:+$FABRIC_CP;}$(cygpath -m "$j")"
  else
    FABRIC_CP="${FABRIC_CP:+$FABRIC_CP;}$j"
  fi
done

MC_CP="$(grep "client.jar" "$APRISM_SMOKE/classpath.txt" | head -1 | tr -d '\r')"
[ -n "$MC_CP" ] || fail "classpath not found in $APRISM_SMOKE/classpath.txt"

# Fresh game directory: Prismate + the three soak packs.
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR/mods" "$GAMEDIR/config"
cp "$PRISMATE_JAR_POSIX" "$GAMEDIR/mods/"
cp "$SOAK_PACKS"/soakcore.aje "$GAMEDIR/mods/"
cp "$SOAK_PACKS"/soakapi.aje "$GAMEDIR/mods/"
cp "$SOAK_PACKS"/soakconsumer.aje "$GAMEDIR/mods/"

echo "Soak: version=$VERSION prismate=$(basename "$PRISMATE_JAR") mc=$MCVER packs=3"

ARGS="$SMOKE_DIR/launch_args.txt"
mkdir -p "$SMOKE_DIR"
{
  echo "-Xmx2G"
  echo "-Djava.library.path=$W_APRISM_SMOKE/natives/windows/x64"
  echo "-Dprismate.smoke.marker=$MARKER"
  echo "\"-DFabricMcEmu= net.minecraft.client.main.Main \""
  echo "-Dfabric.log.level=info"
  echo "-cp"
  echo "${PRISMATE_JAR};${FABRIC_CP};${MC_CP}"
  echo "net.fabricmc.loader.impl.launch.knot.KnotClient"
  echo "--version"
  echo "$MCVER"
  echo "--gameDir"
  echo "$W_GAMEDIR"
  echo "--assetsDir"
  echo "$W_APRISM_SMOKE/gamedir/assets"
  echo "--assetIndex"
  echo "32"
  echo "--versionType"
  echo "release"
  echo "--accessToken"
  echo "0"
} > "$ARGS"

rm -f "$LOG"
echo "Soak: launching real Minecraft $MCVER through Fabric Loader with Prismate + 3 soak packs..."
"$JAVA_BIN" "@$ARGS" > "$LOG" 2>&1 &
GAME_PID=$!

# Poll for the last soak COMPLETE marker (all three mods reach COMPLETE).
TIMEOUT_SECS="${PRISMATE_SOAK_TIMEOUT:-240}"
FOUND=0
for _ in $(seq 1 "$TIMEOUT_SECS"); do
  sleep 1
  if grep -q "\[SOAK\] consumer complete" "$LOG" 2>/dev/null; then
    FOUND=1
    break
  fi
done

# Stop the game regardless of outcome.
if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
else
  kill "$GAME_PID" >/dev/null 2>&1 || true
fi

if [ "$FOUND" -ne 1 ]; then
  echo "--- last 50 log lines ---" >&2
  tail -50 "$LOG" >&2 || true
  fail "consumer COMPLETE not observed within ${TIMEOUT_SECS}s"
fi

echo "Soak: multi-pack lifecycle assertions..."
for marker in "[SOAK] core init" "[SOAK] core complete" \
              "[SOAK] provider init" "[SOAK] provider complete" \
              "[SOAK] consumer init" "[SOAK] consumer complete"; do
  # -F: fixed-string match; the square brackets in [SOAK] must not be
  # interpreted as a regex character class.
  grep -Fq "$marker" "$LOG" || fail "missing soak marker: $marker"
done

echo "Soak: dependency-ordering assertion (provider init BEFORE consumer init)..."
PROVIDER_LINE=$(grep -n "\[SOAK\] provider init" "$LOG" | head -1 | cut -d: -f1)
CONSUMER_LINE=$(grep -n "\[SOAK\] consumer init" "$LOG" | head -1 | cut -d: -f1)
if [ -z "$PROVIDER_LINE" ] || [ -z "$CONSUMER_LINE" ]; then
  fail "could not locate provider/consumer init markers"
fi
if [ "$PROVIDER_LINE" -ge "$CONSUMER_LINE" ]; then
  fail "provider init (line $PROVIDER_LINE) did not precede consumer init (line $CONSUMER_LINE)"
fi

echo "Soak: load report assertions..."
grep -q "AprismPrismate Load Report" "$LOG" || fail "Prismate Load Report missing"
grep -q "failed 0" "$LOG" || fail "load report reports failures"
grep -q "Loaded 6" "$LOG" || fail "expected 6 loaded units (3 extraction + 3 classpath)"

echo "Soak: startup performance baseline..."
BOOT_MS=$(grep -o "Total boot: [0-9]* ms" "$LOG" | head -1 | grep -o "[0-9]*")
echo "SOAK BASELINE: total boot = ${BOOT_MS} ms (Prismate $VERSION, MC $MCVER, Fabric, 3 soak packs)"
echo "$BOOT_MS" > "$SMOKE_DIR/boot_ms.txt"

echo "SOAK PASS: multi-mod soak verified ($VERSION on MC $MCVER)"
exit 0
