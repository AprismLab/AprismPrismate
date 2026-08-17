# AprismPrismate real-game regression harness (PowerShell + mdl)
# v26.2-Alpha.3: replaces the old bash smoke harnesses with mdl-managed instances.
#
# This script creates clean Fabric and NeoForge instances, installs the freshly
# built Prismate jar + sample .aje packs, launches the game, polls the log for
# lifecycle markers, and reports PASS/FAIL per harness.
#
# Usage: powershell -File tools\smoke\run_mdl_regression.ps1
# Requires: mdl on PATH, JDK 21+25, built Prismate jars (./gradlew shadowJar)

param(
    [string]$PrismateJar = "",
    [int]$TimeoutSec = 120
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# Read version from gradle.properties
$Version = (Select-String -Path "$RepoRoot\gradle.properties" -Pattern "^prismateVersion\s*=\s*(.+)").Matches.Groups[1].Value.Trim()
$McVer = (Select-String -Path "$RepoRoot\gradle.properties" -Pattern "^minecraftVersion\s*=\s*(.+)").Matches.Groups[1].Value.Trim()

$FabricJar = "$RepoRoot\fabric\build\libs\AprismPrismate-$Version-Fa-$McVer.jar"
$NeoForgeJar = "$RepoRoot\neoforge\build\libs\AprismPrismate-$Version-N-$McVer.jar"

$ExampleAje = "$RepoRoot\..\Aprism\build\smoke\gamedir\mods\examplemod-1.0.0.aje"
$RessmokeAje = "$RepoRoot\build\smoke-packs\ressmoke.aje"
$PrismatemixAje = "$RepoRoot\build\smoke-packs\prismatemix.aje"
$FaultsmokeAje = "$RepoRoot\build\smoke-packs\faultsmoke.aje"
$SoakCore = "$RepoRoot\build\smoke-soak\soakcore.aje"
$SoakApi = "$RepoRoot\build\smoke-soak\soakapi.aje"
$SoakConsumer = "$RepoRoot\build\smoke-soak\soakconsumer.aje"

$Results = @()

function Invoke-Harness {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    Write-Host ""
    Write-Host "===== [$Name] =====" -ForegroundColor Cyan
    try {
        & $Action
        $Results += "PASS  $Name"
        Write-Host "  PASS" -ForegroundColor Green
    } catch {
        $Results += "FAIL  $Name ($($_.Exception.Message))"
        Write-Host "  FAIL: $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Get-InstanceLog {
    param([string]$Instance)
    $instDir = "$env:APPDATA\mdl\instances\$Instance"
    $logDir = "$instDir\logs"
    if (Test-Path "$logDir\latest.log") {
        return Get-Content "$logDir\latest.log" -Raw
    }
    # Fallback: check for launch_detached log
    if (Test-Path "$env:APPDATA\mdl\logs\launch_detached.log") {
        return Get-Content "$env:APPDATA\mdl\logs\launch_detached.log" -Raw
    }
    return ""
}

function Wait-ForMarker {
    param(
        [string]$Instance,
        [string]$Pattern,
        [int]$Timeout = $TimeoutSec
    )
    $elapsed = 0
    while ($elapsed -lt $Timeout) {
        Start-Sleep -Seconds 2
        $elapsed += 2
        $log = Get-InstanceLog -Instance $Instance
        if ($log -match $Pattern) {
            return $true
        }
    }
    return $false
}

function Stop-Instance {
    param([string]$Instance)
    try {
        mdl stop $Instance 2>$null
    } catch {}
    Start-Sleep -Seconds 3
}

function Clean-Instance {
    param([string]$Instance)
    try {
        mdl delete $Instance --force 2>$null
    } catch {}
}

# Pre-flight checks
Write-Host "AprismPrismate real-game regression ($Version) - mdl harness" -ForegroundColor Yellow

if (-not (Test-Path $FabricJar)) {
    Write-Host "FATAL: Fabric jar not found: $FabricJar" -ForegroundColor Red
    Write-Host "  Run: .\gradlew :fabric:shadowJar"
    exit 1
}
if (-not (Test-Path $ExampleAje)) {
    Write-Host "FATAL: examplemod.aje not found: $ExampleAje" -ForegroundColor Red
    Write-Host "  Run the Aprism smoke env setup first"
    exit 1
}

# ============================================================================
# Harness 1: Fabric 26.2 lifecycle + mixin + resources
# ============================================================================
Invoke-Harness "Fabric 26.2 lifecycle+mixin+resources" {
    $inst = "prismate-fab-262"
    Clean-Instance $inst
    mdl create $inst --mc-version $McVer --loader fabric --no-despotes --no-install 2>$null
    mdl mod install $inst $FabricJar
    mdl mod install $inst $ExampleAje
    mdl mod install $inst $RessmokeAje
    mdl mod install $inst $PrismatemixAje

    mdl launch $inst --detach --username PrismateTest 2>$null
    $found = Wait-ForMarker -Instance $inst -Pattern "APRISM-MIXIN-PROOF.*woven into net.minecraft.client.Minecraft" -Timeout $TimeoutSec
    Stop-Instance $inst

    if (-not $found) {
        throw "Mixin marker not found within ${TimeoutSec}s"
    }

    $log = Get-InstanceLog -Instance $inst
    if ($log -notmatch "AprismPrismate.*booting on Fabric") { throw "Prismate boot line missing" }
    if ($log -notmatch "\[ExampleMod\] onPreInitialize") { throw "onPreInitialize missing" }
    if ($log -notmatch "\[ExampleMod\] onInitialize") { throw "onInitialize missing" }
    if ($log -notmatch "\[ExampleMod\] onSetup") { throw "onSetup missing" }
    if ($log -notmatch "\[ExampleMod\] onComplete") { throw "onComplete missing" }
    if ($log -notmatch "AprismPrismate Load Report") { throw "Load Report missing" }
    if ($log -notmatch "failed 0") { throw "load report reports failures" }
    if ($log -notmatch "\[RESSMOKE\] resource visible=true") { throw "ressmoke resource injection failed" }
    if ($log -notmatch "APRISM-MIXIN-PROOF.*woven into net.minecraft.client.Minecraft") { throw "mixin passthrough failed" }

    Clean-Instance $inst
}

# ============================================================================
# Harness 2: NeoForge 26.2 lifecycle + report
# ============================================================================
Invoke-Harness "NeoForge 26.2 lifecycle+report" {
    $inst = "prismate-neo-262"
    Clean-Instance $inst
    mdl create $inst --mc-version $McVer --loader neoforge --no-despotes --no-install 2>$null
    mdl mod install $inst $NeoForgeJar
    mdl mod install $inst $ExampleAje

    mdl launch $inst --detach --username PrismateTest 2>$null
    $found = Wait-ForMarker -Instance $inst -Pattern "\[ExampleMod\] onComplete" -Timeout $TimeoutSec
    Stop-Instance $inst

    if (-not $found) {
        throw "onComplete marker not found within ${TimeoutSec}s"
    }

    $log = Get-InstanceLog -Instance $inst
    if ($log -notmatch "AprismPrismate.*booting on") { throw "Prismate boot line missing" }
    if ($log -notmatch "\[ExampleMod\] onPreInitialize") { throw "onPreInitialize missing" }
    if ($log -notmatch "\[ExampleMod\] onInitialize") { throw "onInitialize missing" }
    if ($log -notmatch "\[ExampleMod\] onSetup") { throw "onSetup missing" }
    if ($log -notmatch "\[ExampleMod\] onComplete") { throw "onComplete missing" }
    if ($log -notmatch "AprismPrismate Load Report") { throw "Load Report missing" }

    Clean-Instance $inst
}

# ============================================================================
# Harness 3: Fabric 26.2 multi-mod soak (dependency ordering)
# ============================================================================
Invoke-Harness "Fabric 26.2 multi-mod soak" {
    $inst = "prismate-soak-262"
    Clean-Instance $inst
    mdl create $inst --mc-version $McVer --loader fabric --no-despotes --no-install 2>$null
    mdl mod install $inst $FabricJar
    mdl mod install $inst $SoakCore
    mdl mod install $inst $SoakApi
    mdl mod install $inst $SoakConsumer

    mdl launch $inst --detach --username PrismateTest 2>$null
    $found = Wait-ForMarker -Instance $inst -Pattern "\[SOAKCONSUMER\].*onComplete" -Timeout $TimeoutSec
    Stop-Instance $inst

    if (-not $found) {
        throw "soakconsumer onComplete not found within ${TimeoutSec}s"
    }

    $log = Get-InstanceLog -Instance $inst
    if ($log -notmatch "AprismPrismate Load Report") { throw "Load Report missing" }
    if ($log -notmatch "failed 0") { throw "load report reports failures" }

    Clean-Instance $inst
}

# ============================================================================
# Summary
# ============================================================================
Write-Host ""
Write-Host "===== REGRESSION SUMMARY ($Version) =====" -ForegroundColor Yellow
foreach ($r in $Results) {
    if ($r -match "^PASS") {
        Write-Host "  $r" -ForegroundColor Green
    } else {
        Write-Host "  $r" -ForegroundColor Red
    }
}

if ($Results -match "^FAIL") {
    Write-Host "REGRESSION FAIL" -ForegroundColor Red
    exit 1
} else {
    Write-Host "REGRESSION PASS: all harnesses green" -ForegroundColor Green
    exit 0
}
