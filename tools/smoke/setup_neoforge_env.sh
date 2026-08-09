#!/usr/bin/env bash
# Sets up the NeoForge smoke environment for AprismPrismate (idempotent).
# Builds build/smoke-neoforge/libraries in Maven layout:
#   - vanilla MC 26.2 libraries copied from the Aprism smoke environment
#   - NeoForge-specific libraries downloaded from maven.neoforged.net
#   - the NeoForge-patched client jar (generated once by installertools)
#
# Prerequisites:
#   - Aprism smoke environment prepared (../Aprism/build/smoke)
#   - tools/smoke/neoforge-cache/installertools-fatjar.jar (downloaded once)
#   - tools/smoke/neoforge-cache/neoforge-installer.jar (downloaded once)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APRISM_SMOKE="$(cd "$REPO_ROOT/../Aprism/build/smoke" && pwd)"
CACHE="$SCRIPT_DIR/neoforge-cache"
ENV_DIR="$REPO_ROOT/build/smoke-neoforge"
LIBS="$ENV_DIR/libraries"

NEOFORGE_VERSION="26.2.0.53-beta"
MAVEN="https://maven.neoforged.net/releases"

mkdir -p "$LIBS"

# 1. Vanilla MC libraries (Maven layout, already downloaded by Aprism smoke).
echo "[setup-neoforge] copying vanilla MC libraries from Aprism smoke env..."
for d in "$APRISM_SMOKE"/*/; do
  base="$(basename "$d")"
  case "$base" in
    natives|gamedir|assets|com) # com is handled below with version guards
      ;;
  esac
  case "$base" in
    at|com|io|it|net|org|commons-codec|commons-io)
      cp -rn "$d" "$LIBS/$base" 2>/dev/null || true
      ;;
  esac
done

# 2. NeoForge-specific libraries.
echo "[setup-neoforge] downloading NeoForge libraries..."
declare -a NF_LIBS=(
  "net/neoforged/fancymodloader/earlydisplay/11.0.16/earlydisplay-11.0.16.jar"
  "net/neoforged/fancymodloader/loader/11.0.16/loader-11.0.16.jar"
  "net/neoforged/accesstransformers/11.0.2/accesstransformers-11.0.2.jar"
  "net/neoforged/accesstransformers/at-parser/11.0.2/at-parser-11.0.2.jar"
  "net/neoforged/mergetool/2.0.7/mergetool-2.0.7-api.jar"
  "net/neoforged/bus/8.0.5/bus-8.0.5.jar"
  "net/neoforged/JarJarSelector/0.5.1/JarJarSelector-0.5.1.jar"
  "net/neoforged/JarJarMetadata/0.5.1/JarJarMetadata-0.5.1.jar"
  "net/neoforged/srgutils/1.0.10/srgutils-1.0.10.jar"
  "com/electronwill/night-config/toml/3.9.0/toml-3.9.0.jar"
  "com/electronwill/night-config/core/3.9.0/core-3.9.0.jar"
  "net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar"
  "net/minecrell/terminalconsoleappender/1.3.0/terminalconsoleappender-1.3.0.jar"
  "net/jodah/typetools/0.6.3/typetools-0.6.3.jar"
  "org/apache/maven/maven-artifact/3.9.16/maven-artifact-3.9.16.jar"
  "org/codehaus/plexus/plexus-utils/3.6.1/plexus-utils-3.6.1.jar"
  "org/jline/jline-reader/3.20.0/jline-reader-3.20.0.jar"
  "org/jline/jline-terminal/3.20.0/jline-terminal-3.20.0.jar"
  "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"
  "org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar"
  "org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar"
  "org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar"
  "org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar"
)
for rel in "${NF_LIBS[@]}"; do
  target="$LIBS/$rel"
  if [ -f "$target" ]; then continue; fi
  mkdir -p "$(dirname "$target")"
  curl -sfL --max-time 300 -o "$target" "$MAVEN/$rel" \
    || { echo "FAILED: $rel" >&2; exit 1; }
done
# libraries.minecraft.net extras not in the vanilla set
for rel in \
  "com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar" \
  "com/google/errorprone/error_prone_annotations/2.48.0/error_prone_annotations-2.48.0.jar" \
  "com/google/j2objc/j2objc-annotations/3.1/j2objc-annotations-3.1.jar"; do
  target="$LIBS/$rel"
  if [ -f "$target" ]; then continue; fi
  mkdir -p "$(dirname "$target")"
  curl -sfL --max-time 120 -o "$target" "https://libraries.minecraft.net/$rel" \
    || { echo "FAILED: $rel" >&2; exit 1; }
done

# 3. NeoForge universal jar + patched client jar.
UNIVERSAL="$LIBS/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-universal.jar"
if [ ! -f "$UNIVERSAL" ]; then
  mkdir -p "$(dirname "$UNIVERSAL")"
  echo "[setup-neoforge] downloading NeoForge universal jar..."
  curl -sfL --max-time 600 -o "$UNIVERSAL" \
    "$MAVEN/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-universal.jar"
fi
PATCHED="$LIBS/net/neoforged/minecraft-client-patched/$NEOFORGE_VERSION/minecraft-client-patched-$NEOFORGE_VERSION.jar"
if [ ! -f "$PATCHED" ]; then
  mkdir -p "$(dirname "$PATCHED")"
  echo "[setup-neoforge] patching Minecraft client jar..."
  mkdir -p "$ENV_DIR/tmp"
  unzip -oq "$CACHE/neoforge-installer.jar" "data/client.lzma" -d "$ENV_DIR/tmp"
  JAVA_BIN="${PRISMATE_JAVA_HOME:-${JAVA_HOME:-}}/bin/java.exe"
  "$JAVA_BIN" -jar "$CACHE/installertools-fatjar.jar" \
    --task PROCESS_MINECRAFT_JAR --no-mod-manifest \
    --input "$(cygpath -m "$APRISM_SMOKE/client.jar")" \
    --output "$(cygpath -m "$PATCHED")" \
    --extract-libraries-to "$(cygpath -m "$LIBS")" \
    --apply-patches "$(cygpath -m "$ENV_DIR/tmp/data/client.lzma")"
fi

echo "[setup-neoforge] done: $(find "$LIBS" -name '*.jar' | wc -l) jars in $LIBS"
