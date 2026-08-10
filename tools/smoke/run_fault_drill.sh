#!/usr/bin/env bash
# AprismPrismate fault-injection drill harness (v26.1-Alpha.8).
# Launches a genuine Minecraft instance through Fabric Loader with Prismate
# installed plus TWO sample packs:
#   - examplemod  (healthy lifecycle probe - must reach onComplete)
#   - faultsmoke  (its entrypoint throws on purpose in onInitialize)
# and asserts Prismate's per-mod failure isolation + named-failure reporting
# IN THE LIVE GAME:
#   1. the healthy pack still completes its full lifecycle;
#   2. the load report names the failing pack and its reason
#      ("[lifecycle] faultsmoke ... intentional fault-injection failure");
#   3. the report counter reflects exactly one failure ("failed 1").
# This is the real-game half of the Alpha.8 "deliberately broken pack ->
# complete, actionable report" acceptance (the headless half lives in
# EmbeddedRuntimeTest).
#
# Parameterized by Minecraft version (default 1.21.10) so the drill can run
# on every JE line segment of the v26.1 matrix.
#
# Requirements:
#   - the version environment built by tools/smoke/setup_fabric121_env.sh
#   - a built Prismate Fabric jar (./gradlew :fabric:shadowJar)
#   - examplemod .aje from ../Aprism/build/smoke and faultsmoke.aje from
#     build/smoke-packs (python tools/smoke/build_smoke_packs.py)
#   - a JDK 21+ (set PRISMATE_JAVA_HOME or JAVA_HOME)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PYTHON="${PYTHON:-python}"
MCVER="${1:-1.21.10}"
SLUG="$("$PYTHON" -c "import sys;p=sys.argv[1].split('.');print(p[0]+p[1])" "$MCVER" 2>/dev/null || printf '%s' "$MCVER" | tr -d '.')"
ENV_DIR="$REPO_ROOT/build/smoke-fabric-$SLUG"

SMOKE_DIR="$REPO_ROOT/build/fault-drill-$SLUG"
GAMEDIR="$SMOKE_DIR/gamedir"
LOG="$SMOKE_DIR/drill.log"
MARKER="PRISMATE_FAULTDRILL${SLUG}"

wpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

VJSON="$ENV_DIR/work/$MCVER.json"
[ -s "$VJSON" ] || { echo "FAIL: $VJSON missing; run setup_fabric121_env.sh $MCVER first" >&2; exit 1; }
ASSET_INDEX="$("$PYTHON" -c "import json;print(json.load(open('$(wpath "$VJSON")',encoding='utf-8')).get('assets',''))")"
[ -n "$ASSET_INDEX" ] || ASSET_INDEX="legacy"

VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
[ -n "$VERSION" ] || { echo "FAIL: could not read prismateVersion" >&2; exit 1; }

PRISMATE_JAR_POSIX="$(ls "$REPO_ROOT"/fabric/build/libs/AprismPrismate-${VERSION}-Fa-*.jar 2>/dev/null | head -1 || true)"
[ -n "$PRISMATE_JAR_POSIX" ] || { echo "FAIL: Prismate Fabric jar missing for $VERSION (run ./gradlew :fabric:shadowJar)" >&2; exit 1; }
PRISMATE_JAR="$(wpath "$PRISMATE_JAR_POSIX")"

JAVA_BIN="${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}/bin/java.exe"
if [ ! -x "$JAVA_BIN" ]; then
  echo "FAIL: JDK not found at $JAVA_BIN" >&2
  echo "      Set PRISMATE_JAVA_HOME (or JAVA_HOME) to a JDK 21+ installation." >&2
  exit 1
fi

fail() { echo "FAULT-DRILL FAIL: $*" >&2; exit 1; }

if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
fi
# Settle: a previous instance of this harness (or a sibling matrix run) may
# have been killed seconds ago; Windows can still be releasing its file
# handles. Give the OS a moment so the fresh JVM does not die on startup.
sleep 3

# Pre-flight checks
[ -f "$ENV_DIR/client.jar" ] || fail "$MCVER client.jar missing: run setup_fabric121_env.sh $MCVER"
[ -d "$ENV_DIR/natives/windows/x64" ] || fail "$MCVER natives missing"
[ -f "$ENV_DIR/classpath.txt" ] || fail "$MCVER classpath.txt missing"
EXAMPLE_AJE="$REPO_ROOT/../Aprism/build/smoke/gamedir/mods/examplemod-1.0.0.aje"
[ -f "$EXAMPLE_AJE" ] || fail "examplemod .aje missing: $EXAMPLE_AJE"
FAULT_AJE="$REPO_ROOT/build/smoke-packs/faultsmoke.aje"
[ -f "$FAULT_AJE" ] || fail "faultsmoke.aje missing (run build_smoke_packs.py)"

DEPS="$REPO_ROOT/tools/smoke/deps-$SLUG"
FABRIC_CP=""
for j in "$DEPS"/*.jar; do
  FABRIC_CP="${FABRIC_CP:+$FABRIC_CP;}$(wpath "$j")"
done

MC_CP="$(head -1 "$ENV_DIR/classpath.txt" | tr -d '\r')"
[ -n "$MC_CP" ] || fail "classpath not found in $ENV_DIR/classpath.txt"

# Fresh game directory: Prismate + healthy pack + deliberately broken pack.
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR/mods" "$GAMEDIR/config" "$GAMEDIR/assets"
cp "$PRISMATE_JAR_POSIX" "$GAMEDIR/mods/"
cp "$EXAMPLE_AJE" "$GAMEDIR/mods/"
cp "$FAULT_AJE" "$GAMEDIR/mods/"
echo "FaultDrill: version=$VERSION prismate=$(basename "$PRISMATE_JAR") mc=$MCVER"

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

rm -f "$LOG"
echo "FaultDrill: launching real Minecraft $MCVER through Fabric Loader (1 broken + 1 healthy pack)..."

# Launch + poll, with ONE retry guarded by log emptiness.
#
# Two distinct failure shapes exist on Windows and must not be conflated:
#   (a) the JVM died before writing anything -> the log stays 0 bytes. This is
#       a LAUNCH failure (previous instance's file locks / AV scan still
#       settling) and is retried once after a short wait.
#   (b) the game started (log has content) but the healthy pack never reached
#       onComplete within the timeout -> a REAL isolation/report failure,
#       reported immediately without retry.
TIMEOUT_SECS="${PRISMATE_SMOKE_TIMEOUT:-240}"
FOUND=0
ATTEMPT=0
MAX_ATTEMPTS=2
while [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; do
  ATTEMPT=$((ATTEMPT + 1))
  rm -f "$LOG"
  "$JAVA_BIN" "@$ARGS" > "$LOG" 2>&1 &
  GAME_PID=$!

  FOUND=0
  for _ in $(seq 1 "$TIMEOUT_SECS"); do
    sleep 1
    if grep -q "\[ExampleMod\] onComplete" "$LOG" 2>/dev/null; then
      FOUND=1
      break
    fi
  done

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$MARKER" >/dev/null 2>&1 || true
  else
    kill "$GAME_PID" >/dev/null 2>&1 || true
  fi

  if [ "$FOUND" -eq 1 ]; then
    break
  fi

  # Log empty => launch failure (a). Retry once after letting the OS settle.
  if [ ! -s "$LOG" ] && [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; then
    echo "FaultDrill: JVM produced no output (launch failure); retrying once after a settle..." >&2
    sleep 8
    continue
  fi
  # Non-empty log without the marker => real failure (b); do not retry.
  break
done

if [ "$FOUND" -ne 1 ]; then
  echo "--- last 50 log lines ---" >&2
  tail -50 "$LOG" >&2 || true
  fail "healthy pack did not reach onComplete within ${TIMEOUT_SECS}s (isolation broken?)"
fi

echo "FaultDrill: isolation assertions (healthy pack completes despite broken neighbour)..."
grep -q "\[ExampleMod\] onPreInitialize" "$LOG" || fail "healthy pack onPreInitialize missing"
grep -q "\[ExampleMod\] onInitialize" "$LOG" || fail "healthy pack onInitialize missing"
grep -q "\[ExampleMod\] onSetup" "$LOG" || fail "healthy pack onSetup missing"
grep -q "\[ExampleMod\] onComplete" "$LOG" || fail "healthy pack onComplete missing"

echo "FaultDrill: named-failure assertions (broken pack reported with reason)..."
grep -q "AprismPrismate Load Report" "$LOG" || fail "Prismate Load Report missing"
grep -q "failed 1" "$LOG" || fail "load report does not report exactly 1 failure"
grep -q "\[lifecycle\] faultsmoke" "$LOG" \
  || fail "load report does not name the failing pack (faultsmoke) with its stage"
grep -q "intentional fault-injection failure" "$LOG" \
  || fail "load report does not carry the failure reason"

echo "FaultDrill: report-file assertion..."
REPORT_FILE="$GAMEDIR/prismate/reports/load-report.txt"
[ -f "$REPORT_FILE" ] || fail "load-report.txt not written"
grep -q "faultsmoke" "$REPORT_FILE" || fail "load-report.txt does not name faultsmoke"

echo "FAULT-DRILL PASS: broken pack isolated + named in live game ($VERSION on MC $MCVER)"
exit 0
