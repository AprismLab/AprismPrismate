#!/usr/bin/env bash
# AprismPrismate real-NeoForge smoke harness (Windows).
# Launches a genuine Minecraft 26.2 instance through NeoForge 26.2.0.53-beta
# (FML 11, production mode) with Prismate installed as a NeoForge mod plus
# sample .aje packs, then asserts the Aprism lifecycle ran inside the live
# game.
#
# Delegates environment + argfile construction to setup_neoforge_env.sh and
# build_neoforge_client_args.sh (merged vanilla-client + FML-boot classpath,
# patched client jar located by FML via libraryDirectory).
#
# Requirements:
#   - tools/smoke/setup_neoforge_env.sh run once (builds build/smoke-neoforge)
#   - a built Prismate NeoForge jar (./gradlew :neoforge:shadowJar)
#   - JDK 25 (set PRISMATE_JAVA_HOME or JAVA_HOME to a JDK 25)
#
# Note on scope (FACT.md Alpha.3 / OPEN-5): the Alpha.3 exit criterion is the
# .aje lifecycle running on real NeoForge. Host Mixin passthrough and
# resource-dir injection are degraded-path items tracked for Alpha.4+, so this
# harness asserts the lifecycle and Load Report only.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_DIR="$REPO_ROOT/build/smoke-neoforge"
RUN="$ENV_DIR/run"
GAMEDIR="$RUN/gamedir"
LOG="$RUN/smoke.log"
MARKER="PRISMATE_NEOFORGE_SMOKE"

JAVA_BIN="${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}/bin/java.exe"
if [ ! -x "$JAVA_BIN" ]; then
  echo "FAIL: JDK 25 not found at $JAVA_BIN" >&2; exit 1
fi

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

# Kill any game process from a previous smoke run (matched by our marker).
if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
fi
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$MARKER*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1 || true
sleep 2

# Pre-flight
[ -d "$ENV_DIR/libraries" ] || fail "NeoForge libs missing: run tools/smoke/setup_neoforge_env.sh"
VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
MCVER="$(sed -n 's/^minecraftVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
PRISMATE_JAR="$REPO_ROOT/neoforge/build/libs/AprismPrismate-${VERSION}-N-${MCVER}.jar"
[ -f "$PRISMATE_JAR" ] || fail "Prismate NeoForge jar missing: $PRISMATE_JAR (run ./gradlew :neoforge:shadowJar)"

# Build the merged-client argfile + game dir (Prismate + sample packs).
bash "$SCRIPT_DIR/build_neoforge_client_args.sh" --prismate

rm -f "$LOG"
echo "Smoke: launching real Minecraft $MCVER through NeoForge with Prismate..."
"$JAVA_BIN" "@$RUN/launch_args_prismate.txt" > "$LOG" 2>&1 &
GAME_PID=$!

# Poll for the lifecycle completion marker (ExampleMod onComplete).
TIMEOUT_SECS="${PRISMATE_SMOKE_TIMEOUT:-240}"
FOUND=0
for _ in $(seq 1 "$TIMEOUT_SECS"); do
  sleep 1
  if grep -q "\[ExampleMod\] onComplete" "$LOG" 2>/dev/null; then
    FOUND=1; break
  fi
done

# Stop the game regardless of outcome.
if command -v pkill >/dev/null 2>&1; then
  pkill -f "$MARKER" >/dev/null 2>&1 || true
else
  kill "$GAME_PID" >/dev/null 2>&1 || true
fi
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$MARKER*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1 || true

if [ "$FOUND" -ne 1 ]; then
  echo "--- last 50 log lines ---" >&2
  tail -50 "$LOG" >&2 || true
  fail "ExampleMod onComplete not observed within ${TIMEOUT_SECS}s"
fi

echo "Smoke: lifecycle assertions..."
grep -q "AprismPrismate .* booting on NeoForge" "$LOG" || fail "Prismate boot line missing"
grep -q "\[ExampleMod\] onPreInitialize" "$LOG" || fail "onPreInitialize missing"
grep -q "\[ExampleMod\] onInitialize" "$LOG" || fail "onInitialize missing"
grep -q "\[ExampleMod\] onSetup" "$LOG" || fail "onSetup missing"
grep -q "\[ExampleMod\] onComplete" "$LOG" || fail "onComplete missing"
grep -q "AprismPrismate Load Report" "$LOG" || fail "Prismate Load Report missing"
grep -q "failed 0" "$LOG" || fail "load report reports failures"

echo "SMOKE PASS: real-NeoForge Prismate lifecycle verified ($VERSION on MC $MCVER)"
exit 0
