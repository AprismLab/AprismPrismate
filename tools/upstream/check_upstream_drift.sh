#!/usr/bin/env bash
# AprismPrismate upstream drift check (v26.1-Alpha.1)
# Author: BlockConnect@StarsailsClover
#
# Aprism keeps developing in parallel; this script tells you how far the
# sibling checkout has moved past the sync pin recorded in gradle.properties
# (`aprismSyncPin`), then re-runs Prismate's full headless suite against the
# moved upstream so drift (new/changed API) surfaces immediately.
#
# Usage: bash tools/upstream/check_upstream_drift.sh [--sync]
#   (no args)  report drift + rebuild/re-test Prismate against HEAD
#   --sync     additionally fast-forward the recorded pin to the upstream HEAD
#              (only after the suite is green; the caller commits the bump)
#
# Exit codes: 0 = no drift OR drift present and Prismate is green against it;
#             non-zero = Prismate fails against the drifted upstream (the
#             composite build means CI/local tests already see the drift).

set -uo pipefail

PRISMATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APRISM_ROOT="$(cd "$PRISMATE_ROOT/../Aprism" 2>/dev/null && pwd)" || {
    echo "[DRIFT] ERROR: sibling Aprism checkout not found next to Prismate" >&2
    exit 2
}
PROPS="$PRISMATE_ROOT/gradle.properties"
PIN=$(sed -n 's/^aprismSyncPin *= *//p' "$PROPS" | tr -d '\r')

if [[ -z "$PIN" ]]; then
    echo "[DRIFT] ERROR: no aprismSyncPin recorded in gradle.properties" >&2
    exit 2
fi

cd "$APRISM_ROOT"
UPSTREAM_HEAD=$(git rev-parse HEAD)
if ! git cat-file -e "$PIN^{commit}" 2>/dev/null; then
    echo "[DRIFT] WARNING: recorded pin $PIN not found in the Aprism history;" >&2
    echo "[DRIFT]          was Aprism rebased? Treating HEAD as the new baseline." >&2
    PIN="$UPSTREAM_HEAD"
fi

NEW_COMMITS=$(git rev-list --count "$PIN..$UPSTREAM_HEAD" 2>/dev/null || echo "?")
echo "[DRIFT] Prismate sync pin : $PIN"
echo "[DRIFT] Aprism HEAD       : $UPSTREAM_HEAD"
echo "[DRIFT] commits since pin : $NEW_COMMITS"
if [[ "$NEW_COMMITS" != "0" ]]; then
    echo "[DRIFT] --- new upstream commits (newest first) ---"
    git log --oneline "$PIN..$UPSTREAM_HEAD" | head -20
fi

echo "[DRIFT] re-running the Prismate headless suite against the drifted upstream..."
cd "$PRISMATE_ROOT"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot}" \
    ./gradlew build --console=plain
STATUS=$?

if [[ $STATUS -ne 0 ]]; then
    echo "[DRIFT] RESULT: Prismate FAILED against upstream $UPSTREAM_HEAD" >&2
    echo "[DRIFT] The composite build is exposing upstream drift; adapt Prismate before syncing the pin." >&2
    exit $STATUS
fi

echo "[DRIFT] RESULT: Prismate is GREEN against upstream $UPSTREAM_HEAD"
if [[ "${1:-}" == "--sync" ]]; then
    if [[ -f "$PROPS" ]]; then
        # Rewrite the pin in place (works with either line ending).
        sed -i "s/^aprismSyncPin *=.*/aprismSyncPin = $UPSTREAM_HEAD/" "$PROPS"
        echo "[DRIFT] sync pin updated to $UPSTREAM_HEAD (commit the bump)"
    fi
fi
exit 0
