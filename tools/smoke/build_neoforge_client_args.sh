#!/usr/bin/env bash
# Builds a NeoForge CLIENT launch argfile by merging:
#   - the vanilla MC 26.2 client libraries (from the Aprism smoke classpath),
#     excluding client.jar (the NeoForge patched client jar replaces it and is
#     located by FML via libraryDirectory)
#   - the NeoForge FML boot libraries from tools/smoke/neoforge-cache win_args
#     template, resolved against build/smoke-neoforge/libraries
# The NeoForge-patched client jar and NeoForge universal jar are NOT placed on
# the classpath; FML locates them from libraryDirectory in production mode.
#
# Usage: build_neoforge_client_args.sh [--vanilla|--prismate]
#   --vanilla    empty mods dir (environment health check)
#   --prismate   (default) Prismate + sample .aje packs in mods/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APRISM_SMOKE="$(cd "$REPO_ROOT/../Aprism/build/smoke" && pwd)"
ENV_DIR="$REPO_ROOT/build/smoke-neoforge"
LIBS="$ENV_DIR/libraries"
RUN="$ENV_DIR/run"
GAMEDIR="$RUN/gamedir"
NEOFORGE_VERSION="26.2.0.53-beta"

MODE="${1:---prismate}"
MODE="${MODE#--}"

to_win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# --- vanilla client libraries (proven MC 26.2 client classpath) ---
VANILLA_CP="$(grep "client.jar" "$APRISM_SMOKE/classpath.txt" | head -1 | tr -d '\r')"
VANILLA_ENTRIES=()
IFS=';' read -ra V_RAW <<< "$VANILLA_CP"
for e in "${V_RAW[@]}"; do
  [ -z "$e" ] && continue
  case "$e" in
    *client.jar) continue ;;  # replaced by the NeoForge patched client jar
  esac
  VANILLA_ENTRIES+=("$e")
done

# --- NeoForge FML boot libraries (from the official server win_args template,
# resolved against our libraries dir). The NeoForge-patched client jar and the
# NeoForge universal jar are EXCLUDED: FML locates them from libraryDirectory
# in production mode; putting them on -cp flips FML into DEV mode. ---
WIN_ARGS="$SCRIPT_DIR/neoforge-cache/win_args.txt"
NF_ENTRIES=()
IFS=';' read -ra N_RAW <<< "$(grep -A1 "^-classpath" "$WIN_ARGS" | tail -1 | tr -d '\r')"
for e in "${N_RAW[@]}"; do
  [ -z "$e" ] && continue
  rel="${e#libraries/}"
  case "$rel" in
    net/neoforged/minecraft-client-patched/*|net/neoforged/neoforge/*) continue ;;
  esac
  [ -f "$LIBS/$rel" ] || { echo "FAIL: FML lib missing in libraries: $rel" >&2; exit 1; }
  NF_ENTRIES+=("$(to_win "$LIBS/$rel")")
done

FULL_CP=""
for e in "${VANILLA_ENTRIES[@]}"; do
  FULL_CP="${FULL_CP:+$FULL_CP;}$e"
done
for e in "${NF_ENTRIES[@]}"; do
  FULL_CP="${FULL_CP:+$FULL_CP;}$e"
done

# --- game directory ---
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR/mods" "$GAMEDIR/config"
printf 'earlyWindowControl=false\n' > "$GAMEDIR/config/fml.toml"

if [ "$MODE" = "prismate" ]; then
  VERSION="$(sed -n 's/^prismateVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
  MCVER="$(sed -n 's/^minecraftVersion = //p' "$REPO_ROOT/gradle.properties" | tr -d '\r')"
  cp "$REPO_ROOT/neoforge/build/libs/AprismPrismate-${VERSION}-N-${MCVER}.jar" "$GAMEDIR/mods/"
  cp "$APRISM_SMOKE/gamedir/mods/examplemod-1.0.0.aje" "$GAMEDIR/mods/"
  for p in prismatemix ressmoke; do
    [ -f "$REPO_ROOT/build/smoke-packs/$p.aje" ] && cp "$REPO_ROOT/build/smoke-packs/$p.aje" "$GAMEDIR/mods/"
  done
fi

# --- argfile ---
W_LIBS="$(to_win "$LIBS")"
W_GAMEDIR="$(to_win "$GAMEDIR")"
W_APRISM_SMOKE="$(to_win "$APRISM_SMOKE")"
mkdir -p "$RUN"
ARGS="$RUN/launch_args_${MODE}.txt"
{
  echo "-Xmx2G"
  echo "-Djava.library.path=$W_APRISM_SMOKE/natives/windows/x64"
  echo "-Dprismate.smoke.marker=PRISMATE_NEOFORGE_SMOKE"
  echo "-DlibraryDirectory=$W_LIBS"
  echo "-Djava.net.preferIPv6Addresses=system"
  echo "--add-opens"
  echo "java.base/java.lang.invoke=ALL-UNNAMED"
  echo "--add-exports"
  echo "jdk.naming.dns/com.sun.jndi.dns=java.naming"
  echo "-cp"
  echo "$FULL_CP"
  echo "net.neoforged.fml.startup.Client"
  echo "--version"
  echo "26.2"
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
  echo "--fml.neoForgeVersion"
  echo "$NEOFORGE_VERSION"
  echo "--fml.mcVersion"
  echo "26.2"
  echo "--fml.neoFormVersion"
  echo "2"
} > "$ARGS"

echo "[neoforge-client] argfile: $ARGS (mode=$MODE)"
echo "[neoforge-client] vanilla entries: ${#VANILLA_ENTRIES[@]}, FML entries: ${#NF_ENTRIES[@]}"
