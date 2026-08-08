#!/usr/bin/env bash
# AprismPrismate real-Fabric smoke harness (Windows).
# Launches a genuine Minecraft 26.2 instance through Fabric Loader 0.16.14
# (KnotClient) with Prismate installed as a Fabric mod plus a sample .aje,
# then asserts the Aprism lifecycle ran INSIDE the host Fabric loader.
#
# Reuses the Aprism workspace's smoke environment (client.jar, libraries,
# natives, assets) from ../Aprism/build/smoke.
#
# Requirements:
#   - a built Prismate Fabric jar (./gradlew :fabric:shadowJar)
#   - the Aprism smoke environment prepared (Aprism tools/smoke/setup_smoke_env.sh)
#   - the Fabric runtime deps in tools/smoke/deps/ (committed)
#   - JDK 25 (set PRISMATE_JAVA_HOME or JAVA_HOME to a JDK 25)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APRISM_SMOKE="$(cd "$REPO_ROOT/../Aprism/build/smoke" && pwd)"

SMOKE_DIR="$REPO_ROOT/build/smoke-fabric"
GAMEDIR="$SMOKE_DIR/gamedir"
LOG="$SMOKE_DIR/smoke.log"
MARKER="PRISMATE_FABRIC_SMOKE"

# Windows java.exe needs drive-letter paths (C:/...), not POSIX (/c/...).
if command -v cygpath >/dev/null 2>&1; then
  W_APRISM_SMOKE="$(cygpath -m "$APRISM_SMOKE")"
  W_SMOKE_DIR="$(cygpath -m "$SMOKE_DIR")"
  W_GAMEDIR="$(cygpath -m "$GAMEDIR")"
  W_REPO_ROOT="$(cygpath -m "$REPO_ROOT")"
else
  W_APRISM_SMOKE="$APRISM_SMOKE"
  W_SMOKE_DIR="$SMOKE_DIR"
  W_GAMEDIR="$GAMEDIR"
  W_REPO_ROOT="$REPO_ROOT"
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

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

# Kill any game process from a previous smoke run (matched by our marker).
if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
fi
sleep 1

# Pre-flight checks
[ -f "$PRISMATE_JAR_POSIX" ] || fail "Prismate jar missing: $PRISMATE_JAR_POSIX (run ./gradlew :fabric:shadowJar)"
[ -f "$APRISM_SMOKE/client.jar" ] || fail "client.jar missing: run Aprism tools/smoke/setup_smoke_env.sh"
[ -d "$APRISM_SMOKE/natives/windows/x64" ] || fail "natives missing in the Aprism smoke env"
SAMPLE_AJE="$APRISM_SMOKE/gamedir/mods/examplemod-1.0.0.aje"
[ -f "$SAMPLE_AJE" ] || fail "sample .aje missing: $SAMPLE_AJE"

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

# Reuse the proven Minecraft classpath recorded by the Aprism smoke env.
MC_CP="$(grep "client.jar" "$APRISM_SMOKE/classpath.txt" | head -1 | tr -d '\r')"
[ -n "$MC_CP" ] || fail "classpath not found in $APRISM_SMOKE/classpath.txt"

# Fresh game directory: Prismate + sample packs in mods/
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR/mods" "$GAMEDIR/config"
cp "$PRISMATE_JAR_POSIX" "$GAMEDIR/mods/"
cp "$SAMPLE_AJE" "$GAMEDIR/mods/"
# Mixin passthrough proof: patches net.minecraft.client.Minecraft.<init>.
# Uses Prismate's own prismatemix sample (the Aprism mixinproof pack violates
# the host Mixin environment's package-ownership rule: its entrypoint lives in
# the same package its mixin config owns).
SMOKE_PACKS="$REPO_ROOT/build/smoke-packs"
MIXIN_AJE="$SMOKE_PACKS/prismatemix.aje"
[ -f "$MIXIN_AJE" ] || fail "prismatemix.aje missing (run build_smoke_packs.py)"
cp "$MIXIN_AJE" "$GAMEDIR/mods/"
# Resource injection proof: built by the same script
RESSMOKE_AJE="$SMOKE_PACKS/ressmoke.aje"
if [ -f "$RESSMOKE_AJE" ]; then
  cp "$RESSMOKE_AJE" "$GAMEDIR/mods/"
else
  echo "Smoke: ressmoke.aje not built; skipping resource assertions"
fi
# Offline mode: no Microsoft auth needed; Fabric accepts --accessToken 0
echo "Smoke: version=$VERSION prismate=$(basename "$PRISMATE_JAR") mc=$MCVER"

# Build the JVM argument file (@file form, one arg per line).
ARGS="$SMOKE_DIR/launch_args.txt"
mkdir -p "$SMOKE_DIR"
{
  echo "-Xmx2G"
  echo "-Djava.library.path=$W_APRISM_SMOKE/natives/windows/x64"
  echo "-Dprismate.smoke.marker=$MARKER"
  echo "\"-DFabricMcEmu= net.minecraft.client.main.Main \""
  echo "-Dfabric.log.level=info"
  echo "-Dmixin.debug=true"
  echo "-Dmixin.debug.export=true"
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
echo "Smoke: launching real Minecraft $MCVER through Fabric Loader with Prismate..."
"$JAVA_BIN" "@$ARGS" > "$LOG" 2>&1 &
GAME_PID=$!

# Poll the game log. The mixin marker is injected at the TAIL of
# Minecraft.<init>, so its presence proves the whole constructor ran — which
# in turn guarantees every mod entrypoint (dispatched earlier inside <init>)
# already fired. Polling for it, rather than for onComplete, avoids killing the
# game before the constructor finishes.
TIMEOUT_SECS="${PRISMATE_SMOKE_TIMEOUT:-180}"
FOUND=0
for _ in $(seq 1 "$TIMEOUT_SECS"); do
  sleep 1
  if grep -q "\[APRISM-MIXIN-PROOF\] woven into net.minecraft.client.Minecraft" "$LOG" 2>/dev/null; then
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
  fail "ExampleMod onComplete not observed within ${TIMEOUT_SECS}s"
fi

echo "Smoke: lifecycle assertions..."
grep -q "AprismPrismate .* booting on Fabric" "$LOG" || fail "Prismate boot line missing"
grep -q "\[ExampleMod\] onPreInitialize" "$LOG" || fail "onPreInitialize missing"
grep -q "\[ExampleMod\] onInitialize" "$LOG" || fail "onInitialize missing"
grep -q "\[ExampleMod\] onSetup" "$LOG" || fail "onSetup missing"
grep -q "\[ExampleMod\] onComplete" "$LOG" || fail "onComplete missing"
grep -q "AprismPrismate Load Report" "$LOG" || fail "Prismate Load Report missing"
grep -q "failed 0" "$LOG" || fail "load report reports failures"

echo "Smoke: mixin passthrough assertions..."
grep -q "\[APRISM-MIXIN-PROOF\] mod 'prismatemix' initialized" "$LOG" \
  || fail "prismatemix entrypoint did not run"
grep -q "\[APRISM-MIXIN-PROOF\] woven into net.minecraft.client.Minecraft" "$LOG" \
  || fail "prismatemix Mixin did not weave into the real Minecraft class"

echo "Smoke: resource injection assertions..."
grep -q "\[RESSMOKE\] resource visible=true" "$LOG" \
  || fail "ressmoke resources not visible through the host classloader"

echo "SMOKE PASS: real-Fabric Prismate lifecycle verified ($VERSION on MC $MCVER)"
exit 0
