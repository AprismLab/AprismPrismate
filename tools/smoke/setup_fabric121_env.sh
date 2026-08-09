#!/usr/bin/env bash
# AprismPrismate - build the real Fabric 1.21.10 smoke environment (v26.1-Alpha.2)
# Author: BlockConnect@StarsailsClover
#
# Downloads the genuine Minecraft 1.21.10 client + its Java libraries + LWJGL
# natives (Windows x64) plus the Fabric Loader runtime deps for 1.21.10, into
# build/smoke-fabric-121/. Assets are intentionally NOT downloaded (the
# lifecycle assertions do not require them; the 26.2 smoke already proved an
# empty assets dir boots). This keeps the disk footprint small (~115 MB).
#
# Produces:
#   build/smoke-fabric-121/client.jar
#   build/smoke-fabric-121/libraries/...     (Maven-layout jars)
#   build/smoke-fabric-121/natives/windows/x64/*.dll
#   build/smoke-fabric-121/classpath.txt     (;-separated library classpath)
#   tools/smoke/deps-121/                    (Fabric runtime deps, committed)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_DIR="$REPO_ROOT/build/smoke-fabric-121"
DEPS_DIR="$REPO_ROOT/tools/smoke/deps-121"
WORK="$ENV_DIR/work"
PYTHON="${PYTHON:-python}"

MC_VERSION="1.21.10"
FABRIC_LOADER_VERSION="0.19.3"
ASSET_INDEX_ID="27"

fail() { echo "SETUP FAIL: $*" >&2; exit 1; }

# Windows-native python cannot open POSIX-style paths (/c/...). Convert to
# drive-letter form before handing any path to python or to a JVM.
wpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

mkdir -p "$ENV_DIR" "$WORK" "$DEPS_DIR"

echo "=== [1/6] Fetching Minecraft $MC_VERSION version manifest ==="
VLIST="$WORK/version_manifest_v2.json"
if [ ! -s "$VLIST" ]; then
    curl -fsSL --max-time 60 "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -o "$VLIST"
fi
PKG_URL="$("$PYTHON" - "$(wpath "$VLIST")" "$MC_VERSION" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for v in data["versions"]:
    if v["id"] == sys.argv[2]:
        print(v["url"]); break
PY
)"
[ -n "$PKG_URL" ] || fail "could not locate $MC_VERSION in the version manifest"
echo "package manifest: $PKG_URL"

VJSON="$WORK/$MC_VERSION.json"
if [ ! -s "$VJSON" ]; then
    curl -fsSL --max-time 60 "$PKG_URL" -o "$VJSON"
fi

echo "=== [2/6] Downloading the client jar ==="
CLIENT_JAR="$ENV_DIR/client.jar"
CLIENT_URL="$("$PYTHON" -c "import json;print(json.load(open('$(wpath "$VJSON")',encoding='utf-8'))['downloads']['client']['url'])")"
if [ ! -s "$CLIENT_JAR" ]; then
    curl -fsSL --max-time 600 "$CLIENT_URL" -o "$CLIENT_JAR"
fi
echo "client.jar: $(du -h "$CLIENT_JAR" | cut -f1)"

echo "=== [3/6] Downloading Java libraries ==="
LIB_DIR="$ENV_DIR/libraries"
"$PYTHON" - "$(wpath "$VJSON")" "$(wpath "$LIB_DIR")" <<'PY'
import json, os, subprocess, sys
vjson, lib_dir = sys.argv[1], sys.argv[2]
data = json.load(open(vjson, encoding="utf-8"))
todo = []
for lib in data["libraries"]:
    dl = lib.get("downloads", {}).get("artifact") or {}
    url, path = dl.get("url"), dl.get("path")
    if not url or not path:
        continue
    # skip libs gated off by rules (e.g. macOS/linux-only) unless they apply to windows
    ok = True
    for rule in lib.get("rules", []):
        action = rule.get("action")
        oses = rule.get("os", {})
        name = oses.get("name")
        if action == "allow" and name and name != "windows":
            ok = False
        elif action == "disallow" and (not name or name == "windows"):
            ok = False
    if ok:
        todo.append((url, path))
os.makedirs(lib_dir, exist_ok=True)
for i, (url, path) in enumerate(todo, 1):
    target = os.path.join(lib_dir, path)
    if os.path.exists(target) and os.path.getsize(target) > 0:
        continue
    os.makedirs(os.path.dirname(target), exist_ok=True)
    print(f"  [{i}/{len(todo)}] {path}")
    subprocess.run(["curl", "-fsSL", "--max-time", "300", url, "-o", target], check=True)
print(f"libraries ready: {len(todo)} artifacts")
PY

echo "=== [4/6] Extracting LWJGL natives (windows x64) from libraries ==="
NAT_DIR="$ENV_DIR/natives/windows/x64"
mkdir -p "$NAT_DIR"
# The natives-windows (x64) jars are already present in libraries/ as part of
# the library set. Extract their .dll files. (arm64/x86 classifier jars have a
# suffix and are intentionally skipped.)
while IFS= read -r -d '' j; do
    "$PYTHON" - "$(wpath "$j")" "$(wpath "$NAT_DIR")" <<'PY'
import sys, zipfile, os
jar, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.endswith(".dll"):
            base = os.path.basename(name)
            target = os.path.join(out, base)
            if not os.path.exists(target):
                with z.open(name) as src, open(target, "wb") as dst:
                    dst.write(src.read())
PY
done < <(find "$LIB_DIR" -name "*-natives-windows.jar" -print0)
echo "natives extracted: $(ls "$NAT_DIR" | wc -l) dlls"

echo "=== [5/6] Downloading Fabric Loader runtime deps for $MC_VERSION ==="
MAVEN_FABRIC="https://maven.fabricmc.net"
fetch_dep() { # $1=group $2=artifact $3=version
    local g="$1" a="$2" v="$3"
    local jar="$DEPS_DIR/$a-$v.jar"
    if [ ! -s "$jar" ]; then
        local gpath="${g//./\/}"
        curl -fsSL --max-time 300 "$MAVEN_FABRIC/$gpath/$a/$v/$a-$v.jar" -o "$jar"
    fi
    echo "  dep: $(basename "$jar")"
}
fetch_dep "net.fabricmc" "fabric-loader" "$FABRIC_LOADER_VERSION"
fetch_dep "net.fabricmc" "sponge-mixin" "0.17.3+mixin.0.8.7"
fetch_dep "net.fabricmc" "intermediary" "$MC_VERSION"
fetch_dep "org.ow2.asm" "asm" "9.10.1"
fetch_dep "org.ow2.asm" "asm-analysis" "9.10.1"
fetch_dep "org.ow2.asm" "asm-commons" "9.10.1"
fetch_dep "org.ow2.asm" "asm-tree" "9.10.1"
fetch_dep "org.ow2.asm" "asm-util" "9.10.1"

echo "=== [6/6] Writing classpath.txt ==="
# Exclude the vanilla ASM jar(s) from the classpath: Fabric Loader ships its
# own ASM (deps-121) and its classpath verifier rejects duplicate ASM classes.
CP="$("$PYTHON" - "$(wpath "$ENV_DIR")" "$(wpath "$CLIENT_JAR")" <<'PY'
import json, os, sys
env_dir, client = sys.argv[1], sys.argv[2]
lib_dir = os.path.join(env_dir, "libraries")
entries = []
for root, _, files in os.walk(lib_dir):
    for f in files:
        if not f.endswith(".jar"):
            continue
        # Fabric supplies its own ASM; the vanilla ow2/asm copy would trip
        # LoaderUtil.verifyClasspath (duplicate ASM classes). Exclude any jar
        # under the org.ow2.asm group regardless of version.
        if "ow2" in root.replace("\\", "/"):
            continue
        entries.append(os.path.join(root, f))
entries.append(client)
print(";".join(entries))
PY
)"
echo "$CP" > "$ENV_DIR/classpath.txt"
echo "classpath entries: $(echo "$CP" | tr ';' '\n' | wc -l)"

echo ""
echo "SETUP COMPLETE: $ENV_DIR"
echo "  client.jar + libraries + natives ready; assets intentionally omitted."
