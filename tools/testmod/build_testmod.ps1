# AprismPrismate :: fallen-trees TestMod builder (v26.7 line vehicle)
# Builds tools/testmod/fallen_trees.aje from source + resources.
# Usage:  powershell -File tools\testmod\build_testmod.ps1 [-ApiJar <path>]
# The Aprism api jar is required to compile against IAprismMod/@AprismMod.

param(
    [string]$ApiJar = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "build"
$jdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$javac = Join-Path $jdk "bin\javac.exe"
$jarExe = Join-Path $jdk "bin\jar.exe"

if (-not $ApiJar) {
    $candidate = Get-ChildItem (Join-Path $root "..\..\..\Aprism\aprism-api\build\libs") `
        -Filter "aprism-api-v26*.jar" |
        Sort-Object Name -Descending |
        Where-Object { $_.Name -notmatch "Alpha|sources" } |
        Select-Object -First 1
    if (-not $candidate) { throw "No aprism-api jar found; pass -ApiJar" }
    $ApiJar = $candidate.FullName
}
Write-Host "Compiling against: $ApiJar"

New-Item -ItemType Directory -Path (Join-Path $out "classes") -Force | Out-Null

& $javac -cp $ApiJar -d (Join-Path $out "classes") `
    (Get-ChildItem (Join-Path $root "src") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $jarExe --create --file (Join-Path $out "fallentrees.jar") `
    -C (Join-Path $out "classes") .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

# Manifest WITHOUT entrypoints map: the entrypoint is annotation-discovered
# (@AprismMod) - exercises the v26.5 annotation-scan surface on both loaders.
$manifest = @'
{
  "schemaVersion": 1,
  "id": "fallentrees",
  "version": "0.1.0",
  "displayName": "Fallen Trees TestMod",
  "description": "Long-term dual-loader parity vehicle: fallen-tree worldgen content",
  "environment": "*",
  "entrypoints": {},
  "mixins": [],
  "depends": {},
  "platforms": {},
  "accessWidener": null,
  "provides": [],
  "custom": {}
}
'@
$tmpManifest = Join-Path $out "aprism.manifest.json"
[System.IO.File]::WriteAllText($tmpManifest, $manifest)

Push-Location $root
try {
    & $jarExe --create --file (Join-Path $out "fallen_trees.aje") `
        -C $out "aprism.manifest.json" `
        -C $out "fallentrees.jar" `
        -C "resources" "."
    if ($LASTEXITCODE -ne 0) { throw "aje packaging failed" }
} finally {
    Pop-Location
}

Write-Host "Built: $(Join-Path $out 'fallen_trees.aje')"
