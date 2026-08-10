#!/usr/bin/env bash
# AprismPrismate unified real-game regression runner.
# v26.1-Alpha.5 grew this into a MULTI-VERSION MATRIX over the JE line:
#   Fabric line segments (run_fabric121_smoke.sh, parameterized):
#     - 1.21.10: lifecycle + resources   (JDK 21 runtime)
#     - 1.20.1:  lifecycle + resources   (JDK 21 runtime)
#   MC 26.2 (the original v26.0 triple):
#     1. run_fabric_smoke.sh   - Fabric 0.16.14: lifecycle + mixin + resources
#     2. run_neoforge_smoke.sh - NeoForge 26.2.0.53-beta: lifecycle + report
#     3. soak/run_soak_smoke.sh - Fabric: multi-pack dependency soak + baseline
# Exits non-zero on the first failing harness and prints a summary line per
# harness. Intended as the pre-release gate for every Alpha.
#
# Requirements: JDK 25 (PRISMATE_JAVA_HOME or JAVA_HOME) for the 26.2 trio;
# JDK 21 (PRISMATE_JAVA21_HOME or auto-detected) for the line segments; the
# Aprism smoke environment (../Aprism/build/smoke); the NeoForge smoke
# environment (tools/smoke/setup_neoforge_env.sh); the 1.21.10 and 1.20.1
# environments (tools/smoke/setup_fabric121_env.sh [version]); built Prismate
# jars for the current version; and the soak packs.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
RESULTS=()
STATUS=0

# JDK 21 for the 1.20/1.21 line segments (the embedded Aprism API is Java 21
# bytecode; MC 1.20+ is forward-compatible with newer JVMs, so JDK 21 is the
# lowest proven runtime and is used for both segments).
JAVA21_HOME="${PRISMATE_JAVA21_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot}"

run_harness() {
    local name="$1"; shift
    echo ""
    echo "===== [$name] ====="
    if "$@"; then
        RESULTS+=("PASS  $name")
    else
        RESULTS+=("FAIL  $name")
        STATUS=1
    fi
}

# Runs a harness with a specific JAVA_HOME (PRISMATE_JAVA_HOME) without
# disturbing the caller's environment.
with_java() {
    local jdk="$1"; shift
    PRISMATE_JAVA_HOME="$jdk" bash "$@"
}

echo "AprismPrismate unified real-game regression ($VERSION) - multi-version matrix"

# --- JE line segments (Fabric, parameterized harness) ---
run_harness "Fabric 1.21.10 lifecycle+resources" \
    with_java "$JAVA21_HOME" "$SCRIPT_DIR/run_fabric121_smoke.sh" 1.21.10
run_harness "Fabric 1.20.1 lifecycle+resources" \
    with_java "$JAVA21_HOME" "$SCRIPT_DIR/run_fabric121_smoke.sh" 1.20.1

# --- Failure-injection drill (v26.1-Alpha.8): on each Fabric line segment,
# launch with 1 deliberately-broken pack + 1 healthy pack and assert the
# broken pack is isolated and named in the report while the healthy pack
# still completes its lifecycle. ---
run_harness "Fabric 1.21.10 fault-injection drill" \
    with_java "$JAVA21_HOME" "$SCRIPT_DIR/run_fault_drill.sh" 1.21.10
run_harness "Fabric 1.20.1 fault-injection drill" \
    with_java "$JAVA21_HOME" "$SCRIPT_DIR/run_fault_drill.sh" 1.20.1

# --- MC 26.2 trio (original v26.0 gate) ---
run_harness "Fabric 26.2 lifecycle+mixin+resources" \
    with_java "${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}" "$SCRIPT_DIR/run_fabric_smoke.sh"
run_harness "NeoForge 26.2 lifecycle+report" \
    with_java "${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}" "$SCRIPT_DIR/run_neoforge_smoke.sh"
run_harness "Fabric 26.2 multi-mod soak" \
    with_java "${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}" "$SCRIPT_DIR/soak/run_soak_smoke.sh"

echo ""
echo "===== REGRESSION SUMMARY ($VERSION) ====="
for r in "${RESULTS[@]}"; do
    echo "  $r"
done

if [ "$STATUS" -ne 0 ]; then
    echo "REGRESSION FAIL: one or more harnesses failed"
else
    echo "REGRESSION PASS: all real-game harnesses green"
fi
exit "$STATUS"
