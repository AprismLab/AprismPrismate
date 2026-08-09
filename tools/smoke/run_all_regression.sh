#!/usr/bin/env bash
# AprismPrismate unified real-game regression runner (v26.0-Alpha.8).
# Runs the three real-game harnesses in sequence against the CURRENT
# gradle.properties version:
#   1. run_fabric_smoke.sh   - Fabric 0.16.14: lifecycle + mixin + resources
#   2. run_neoforge_smoke.sh - NeoForge 26.2.0.53-beta: lifecycle + report
#   3. soak/run_soak_smoke.sh - Fabric: multi-pack dependency soak + baseline
# Exits non-zero on the first failing harness and prints a summary line per
# harness. Intended as the pre-release gate for every v26.0 Alpha.
#
# Requirements: JDK 25 (PRISMATE_JAVA_HOME or JAVA_HOME), the Aprism smoke
# environment (../Aprism/build/smoke), the NeoForge smoke environment
# (tools/smoke/setup_neoforge_env.sh), built Prismate jars for the current
# version (./gradlew :fabric:shadowJar :neoforge:shadowJar), and the soak
# packs (python tools/smoke/soak/build_soak_packs.py build/soak-packs
# tools/smoke/soak-classes).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
RESULTS=()
STATUS=0

run_harness() {
    local name="$1"; shift
    echo ""
    echo "===== [$name] ====="
    if bash "$@"; then
        RESULTS+=("PASS  $name")
    else
        RESULTS+=("FAIL  $name")
        STATUS=1
    fi
}

echo "AprismPrismate unified real-game regression ($VERSION)"

run_harness "Fabric lifecycle+mixin+resources" "$SCRIPT_DIR/run_fabric_smoke.sh"
run_harness "NeoForge lifecycle+report" "$SCRIPT_DIR/run_neoforge_smoke.sh"
run_harness "Fabric multi-mod soak" "$SCRIPT_DIR/soak/run_soak_smoke.sh"

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
