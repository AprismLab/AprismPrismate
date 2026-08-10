#!/usr/bin/env bash
# AprismPrismate real-Fabric 1.21.x smoke harness (v26.1-Alpha.2).
# Launches a genuine Minecraft 1.21.10 instance through Fabric Loader 0.19.3
# (KnotClient) with Prismate installed as a Fabric mod plus version-agnostic
# sample .aje packs, then asserts the Aprism lifecycle ran INSIDE the host
# Fabric loader on the 1.21.x segment of the JE version line.
#
# Sample packs used (all version-independent: Aprism API + classloader only):
#   - examplemod (lifecycle probe)
#   - ressmoke   (resource-directory injection probe)
# prismatemix is intentionally NOT used here: its mixin targets
# net.minecraft.client.Minecraft, which is obfuscated on 1.21.x; remapping is
# the Aprism agent's job, not Prismate's (see Alpha.3 remap boundary).
#
# Requirements:
#   - the 1.21.10 environment built by tools/smoke/setup_fabric121_env.sh
#   - a built Prismate Fabric jar (./gradlew :fabric:shadowJar)
#   - the sample .aje packs (examplemod from ../Aprism/build/smoke, ressmoke
#     from build/smoke-packs)
#   - a JDK 21+ (set PRISMATE_JAVA_HOME or JAVA_HOME)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Parameterized by Minecraft version (default 1.21.10). Mirrors the slugged
# directory layout of setup_fabric121_env.sh so any JE line segment can be
# smoke-tested with the same harness (v26.1-Alpha.5 multi-version matrix).
# Slug is major+minor (1.21.10 -> 121, 1.20.1 -> 120) matching setup layout.
PYTHON="${PYTHON:-python}"
MCVER="${1:-1.21.10}"
SLUG="$("$PYTHON" -c "import sys;p=sys.argv[1].split('.');print(p[0]+p[1])" "$MCVER" 2>/dev/null || printf '%s' "$MCVER" | tr -d '.')"
ENV_DIR="$REPO_ROOT/build/smoke-fabric-$SLUG"

SMOKE_DIR="$REPO_ROOT/build/smoke-fabric-$SLUG-run"
GAMEDIR="$SMOKE_DIR/gamedir"
LOG="$SMOKE_DIR/smoke.log"
MARKER="PRISMATE_FABRIC${SLUG}_SMOKE"

wpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

VJSON="$ENV_DIR/work/$MCVER.json"
[ -s "$VJSON" ] || { echo "FAIL: $VJSON missing; run setup_fabric121_env.sh $MCVER first" >&2; exit 1; }
# Asset index id differs per version (1.20.1 = 9, 1.21.10 = 27); read it from
# the downloaded version manifest rather than hardcoding.
ASSET_INDEX="$("$PYTHON" -c "import json;print(json.load(open('$(wpath "$VJSON")',encoding='utf-8')).get('assets',''))")"
[ -n "$ASSET_INDEX" ] || ASSET_INDEX="legacy"

VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
[ -n "$VERSION" ] || { echo "FAIL: could not read prismateVersion" >&2; exit 1; }

# Prismate's Fabric artifact is named with the build-time minecraftVersion
# pin (26.2), not the runtime target; locate it by globbing the version.
PRISMATE_JAR_POSIX="$(ls "$REPO_ROOT"/fabric/build/libs/AprismPrismate-${VERSION}-Fa-*.jar 2>/dev/null | head -1 || true)"
[ -n "$PRISMATE_JAR_POSIX" ] || { echo "FAIL: Prismate Fabric jar missing for $VERSION (run ./gradlew :fabric:shadowJar)" >&2; exit 1; }
PRISMATE_JAR="$(wpath "$PRISMATE_JAR_POSIX")"

JAVA_BIN="${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}/bin/java.exe"
if [ ! -x "$JAVA_BIN" ]; then
  echo "FAIL: JDK not found at $JAVA_BIN" >&2
  echo "      Set PRISMATE_JAVA_HOME (or JAVA_HOME) to a JDK 21+ installation." >&2
  exit 1
fi

fail() { echo "SMOKE121 FAIL: $*" >&2; exit 1; }

if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
fi
sleep 1

# Pre-flight checks
[ -f "$ENV_DIR/client.jar" ] || fail "1.21.10 client.jar missing: run setup_fabric121_env.sh"
[ -d "$ENV_DIR/natives/windows/x64" ] || fail "1.21.10 natives missing"
[ -f "$ENV_DIR/classpath.txt" ] || fail "1.21.10 classpath.txt missing"
EXAMPLE_AJE="$REPO_ROOT/../Aprism/build/smoke/gamedir/mods/examplemod-1.0.0.aje"
[ -f "$EXAMPLE_AJE" ] || fail "examplemod .aje missing: $EXAMPLE_AJE"
RESSMOKE_AJE="$REPO_ROOT/build/smoke-packs/ressmoke.aje"
[ -f "$RESSMOKE_AJE" ] || fail "ressmoke.aje missing (run build_smoke_packs.py)"

# Fabric runtime deps for this version (committed under tools/smoke/deps-<slug>)
DEPS="$REPO_ROOT/tools/smoke/deps-$SLUG"
FABRIC_CP=""
for j in "$DEPS"/*.jar; do
  FABRIC_CP="${FABRIC_CP:+$FABRIC_CP;}$(wpath "$j")"
done

MC_CP="$(head -1 "$ENV_DIR/classpath.txt" | tr -d '\r')"
[ -n "$MC_CP" ] || fail "classpath not found in $ENV_DIR/classpath.txt"

# Fresh game directory: Prismate + version-agnostic sample packs in mods/
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR/mods" "$GAMEDIR/config" "$GAMEDIR/assets"
cp "$PRISMATE_JAR_POSIX" "$GAMEDIR/mods/"
cp "$EXAMPLE_AJE" "$GAMEDIR/mods/"
cp "$RESSMOKE_AJE" "$GAMEDIR/mods/"
echo "Smoke121: version=$VERSION prismate=$(basename "$PRISMATE_JAR") mc=$MCVER"

ARGS="$SMOKE_DIR/launch_args.txt"
mkdir -p "$SMOKE_DIR"
{
  echo "-Xmx2G"
  echo "-Djava.library.path=$(wpath "$ENV_DIR/natives/windows/x64")"
  echo "-Dprismate.smoke.marker=$MARKER"
  echo "\"-DFabricMcEmu= net.minecraft.client.main.Main \""
  echo "-Dfabric.log.level=info"
  echo "-cp"
  echo "${PRISMATE_JAR};${FABRIC_CP};${MC_CP}"
  echo "net.fabricmc.loader.impl.launch.knot.KnotClient"
  echo "--version"
  echo "$MCVER"
  echo "--gameDir"
  echo "$(wpath "$GAMEDIR")"
  echo "--assetsDir"
  echo "$(wpath "$GAMEDIR/assets")"
  echo "--assetIndex"
  echo "$ASSET_INDEX"
  echo "--versionType"
  echo "release"
  echo "--accessToken"
  echo "0"
} > "$ARGS"

echo "Smoke121: launching real Minecraft $MCVER through Fabric Loader with Prismate..."

# Launch + poll, with ONE retry guarded by log emptiness (v26.1-Alpha.8
# harness hardening). Two distinct failure shapes exist on Windows and must
# not be conflated:
#   (a) the JVM died before writing anything -> the log stays 0 bytes. This is
#       a LAUNCH failure (previous instance's file handles / AV scan still
#       settling) and is retried once after a short wait.
#   (b) the game started (log has content) but the COMPLETE-phase marker never
#       appeared within the timeout -> a REAL pipeline failure, reported
#       immediately without retry.
# The startup baseline is measured on the SUCCESSFUL attempt only.
TIMEOUT_SECS="${PRISMATE_SMOKE_TIMEOUT:-240}"
FOUND=0
ATTEMPT=0
MAX_ATTEMPTS=2
BOOT_MS=0
while [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; do
  ATTEMPT=$((ATTEMPT + 1))
  rm -f "$LOG"
  # Wall-clock startup baseline for this line segment (v26.1-Alpha.5): time
  # from JVM launch until the COMPLETE-phase marker appears. Coarse (poll
  # granularity) but comparable across segments.
  T_START_MS=$(($(date +%s) * 1000))
  "$JAVA_BIN" "@$ARGS" > "$LOG" 2>&1 &
  GAME_PID=$!

  # Poll for the COMPLETE-phase marker: [ExampleMod] onComplete is dispatched
  # in the Aprism COMPLETE phase, which on Fabric fires from the late
  # GAME_READY hook, so its presence proves the whole early + side + complete
  # pipeline ran.
  FOUND=0
  for _ in $(seq 1 "$TIMEOUT_SECS"); do
    sleep 1
    if grep -q "\[ExampleMod\] onComplete" "$LOG" 2>/dev/null; then
      FOUND=1
      break
    fi
  done
  T_END_MS=$(($(date +%s) * 1000))

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$MARKER" >/dev/null 2>&1 || true
  else
    kill "$GAME_PID" >/dev/null 2>&1 || true
  fi

  if [ "$FOUND" -eq 1 ]; then
    BOOT_MS=$((T_END_MS - T_START_MS))
    break
  fi

  # Log empty => launch failure (a). Retry once after letting the OS settle.
  if [ ! -s "$LOG" ] && [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; then
    echo "Smoke121: JVM produced no output (launch failure); retrying once after a settle..." >&2
    sleep 8
    continue
  fi
  # Non-empty log without the marker => real failure (b); do not retry.
  break
done

if [ "$FOUND" -ne 1 ]; then
  echo "--- last 50 log lines ---" >&2
  tail -50 "$LOG" >&2 || true
  fail "ExampleMod onComplete not observed within ${TIMEOUT_SECS}s"
fi

echo "Smoke121: lifecycle assertions..."
grep -q "AprismPrismate .* booting on Fabric" "$LOG" || fail "Prismate boot line missing"
grep -q "\[ExampleMod\] onPreInitialize" "$LOG" || fail "onPreInitialize missing"
grep -q "\[ExampleMod\] onInitialize" "$LOG" || fail "onInitialize missing"
grep -q "\[ExampleMod\] onSetup" "$LOG" || fail "onSetup missing"
grep -q "\[ExampleMod\] onComplete" "$LOG" || fail "onComplete missing"
grep -q "AprismPrismate Load Report" "$LOG" || fail "Prismate Load Report missing"
grep -q "failed 0" "$LOG" || fail "load report reports failures"

echo "Smoke121: resource injection assertions..."
grep -q "\[RESSMOKE\] resource visible=true" "$LOG" \
  || fail "ressmoke resources not visible through the host classloader"

# Startup baseline for this line segment (v26.1-Alpha.5): wall-clock ms from
# JVM launch to the COMPLETE-phase marker, written per version.
BOOT_MS=$((T_END_MS - T_START_MS))
echo "SMOKE121 BASELINE: total boot = ${BOOT_MS} ms (Prismate $VERSION, MC $MCVER, Fabric)"
echo "$BOOT_MS" > "$SMOKE_DIR/boot_ms.txt"

echo "SMOKE121 PASS: real-Fabric Prismate lifecycle verified ($VERSION on MC $MCVER)"
exit 0
